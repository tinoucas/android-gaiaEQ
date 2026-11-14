#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy>=1.26", "bleak>=0.22"]
# ///
"""
Load an AutoEq preset into the BGVP M4's 5-band user EQ (over GAIA/RFCOMM).

The M4 only lets you set the *gain* of 5 fixed peaking bands
(120, 500, 1500, 5000, 12000 Hz, Q 0.7), so the AutoEq curve is fitted:
the script computes the AutoEq target response, then solves for the 5 band
gains (plus a free overall level, which is just volume) that best match it.

Run:   pipx run m4_autoeq.py --search "Blessing 2"            # find + fit + apply
       pipx run m4_autoeq.py --search "Blessing 2" --dry-run  # just show the fit
       pipx run m4_autoeq.py "path/to/X ParametricEQ.txt"     # local file (or URL)
       pipx run m4_autoeq.py --gains 0,0,-6,0,0               # set bands by hand
       pipx run m4_autoeq.py --flat                           # all bands 0 dB, flat preset

Music must be playing for the preset selection (63 = user EQ) to stick; band
gains are stored either way.
"""

import argparse
import asyncio
import os
import re
import struct
import sys
import urllib.parse
import urllib.request

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gaia_query import (MUSIC, Gaia, RfcommLink, classic_address,  # noqa: E402
                        pick_gaia_channel)

FS = 48000.0
M4_BANDS = [120, 500, 1500, 5000, 12000]
M4_Q = 0x0B33 / 4096  # as reported by the device (~0.70)
USER_SET = 63

AUTOEQ_RAW = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
SOURCE_PRIORITY = ["oratory1990", "crinacle", "HypetheSonics", "Rtings",
                   "Innerfidelity", "Headphone.com Legacy"]

# Evaluation grid: 20 Hz - 20 kHz, log-spaced; trust < 10 kHz more.
F = np.geomspace(20, 20000, 300)
W = np.where(F <= 10000, 1.0, 0.3)


# ----------------------------------------------------------- filter maths

def biquad_db(kind: str, fc: float, gain: float, q: float, f=F) -> np.ndarray:
    """RBJ cookbook magnitude response in dB. kind: PK, LSC, HSC."""
    A = 10 ** (gain / 40)
    w0 = 2 * np.pi * fc / FS
    cw, alpha = np.cos(w0), np.sin(w0) / (2 * q)
    if kind == "PK":
        b = [1 + alpha * A, -2 * cw, 1 - alpha * A]
        a = [1 + alpha / A, -2 * cw, 1 - alpha / A]
    elif kind in ("LSC", "LS"):
        s = 2 * np.sqrt(A) * alpha
        b = [A * ((A + 1) - (A - 1) * cw + s), 2 * A * ((A - 1) - (A + 1) * cw),
             A * ((A + 1) - (A - 1) * cw - s)]
        a = [(A + 1) + (A - 1) * cw + s, -2 * ((A - 1) + (A + 1) * cw),
             (A + 1) + (A - 1) * cw - s]
    elif kind in ("HSC", "HS"):
        s = 2 * np.sqrt(A) * alpha
        b = [A * ((A + 1) + (A - 1) * cw + s), -2 * A * ((A - 1) + (A + 1) * cw),
             A * ((A + 1) + (A - 1) * cw - s)]
        a = [(A + 1) - (A - 1) * cw + s, 2 * ((A - 1) - (A + 1) * cw),
             (A + 1) - (A - 1) * cw - s]
    else:
        raise ValueError(f"unsupported filter type {kind}")
    z = np.exp(-1j * 2 * np.pi * f / FS)
    h = (b[0] + b[1] * z + b[2] * z * z) / (a[0] + a[1] * z + a[2] * z * z)
    return 20 * np.log10(np.abs(h))


def m4_response(gains, f=F) -> np.ndarray:
    return sum(biquad_db("PK", fc, g, M4_Q, f) for fc, g in zip(M4_BANDS, gains))


# ----------------------------------------------------------- preset parsing

FILTER_RE = re.compile(
    r"Filter\s*\d*:\s*ON\s+(PK|LSC|HSC|LS|HS)\s+Fc\s+([\d.]+)\s*Hz\s+"
    r"Gain\s+(-?[\d.]+)\s*dB\s+Q\s+([\d.]+)", re.I)


def target_from_text(txt: str) -> tuple[np.ndarray, str]:
    if "GraphicEQ:" in txt:
        pts = [p.split() for p in txt.split("GraphicEQ:", 1)[1].split(";") if p.strip()]
        fr = np.array([float(p[0]) for p in pts])
        db = np.array([float(p[1]) for p in pts])
        return np.interp(np.log(F), np.log(fr), db), f"GraphicEQ ({len(pts)} points)"
    filters = FILTER_RE.findall(txt)
    if not filters:
        sys.exit("no 'Filter N: ON PK/LSC/HSC ...' lines or GraphicEQ found in preset")
    t = sum(biquad_db(k.upper(), float(fc), float(g), float(q)) for k, fc, g, q in filters)
    return t, f"ParametricEQ ({len(filters)} filters)"


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "m4-autoeq"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


def search_autoeq(query: str, pick: int | None) -> tuple[str, str]:
    """Return (label, ParametricEQ URL) for an IEM name from AutoEq's index."""
    index = fetch(AUTOEQ_RAW + "INDEX.md")
    rows = re.findall(r"^- \[(.+?)\]\(\./(.+?)\) by (.+?)(?: on (.+))?$", index, re.M)
    q = query.lower()
    hits = [r for r in rows if q in r[0].lower()]
    if not hits:
        sys.exit(f"no AutoEq result matching {query!r}")
    exact = [r for r in hits if r[0].lower() == q]
    names = sorted({r[0] for r in (exact or hits)})
    if pick is None and len(names) > 1:
        print(f"{len(names)} models match {query!r}; be more specific (or --pick N):")
        for i, r in enumerate(hits):
            print(f"  [{i}] {r[0]}  ({r[2]}{' on ' + r[3] if r[3] else ''})")
        sys.exit(1)
    if pick is not None:
        if not 0 <= pick < len(hits):
            sys.exit(f"--pick must be 0..{len(hits) - 1}")
        row = hits[pick]
    else:
        cands = [r for r in hits if r[0] == names[0]]
        rank = lambda r: SOURCE_PRIORITY.index(r[2]) if r[2] in SOURCE_PRIORITY else 99
        row = min(cands, key=rank)
        if len(cands) > 1:
            others = ", ".join(r[2] for r in cands if r is not row)
            print(f"(also measured by: {others}; choose with --pick)")
    name, path, source, rig = row
    leaf = path.rsplit("/", 1)[-1]
    url = f"{AUTOEQ_RAW}{path}/{leaf}%20ParametricEQ.txt"
    return f"{name} ({source}{' on ' + rig if rig else ''})", url


# ----------------------------------------------------------- fitting

def fit(target: np.ndarray, lo: float, hi: float) -> tuple[np.ndarray, float, float]:
    """Fit 5 band gains + free offset to target (weighted LSQ, gains clipped)."""
    g = np.zeros(len(M4_BANDS))
    sw = np.sqrt(W)
    for _ in range(60):
        base = m4_response(g)
        # Jacobian of response wrt each gain (numerical), plus offset column
        J = np.empty((len(F), len(g) + 1))
        for k in range(len(g)):
            d = g.copy()
            d[k] += 0.01
            J[:, k] = (m4_response(d) - base) / 0.01
        J[:, -1] = 1.0
        off = np.average(target - base, weights=W)
        r = target - (base + off)
        step, *_ = np.linalg.lstsq(J * sw[:, None], r * sw, rcond=None)
        g = np.clip(g + step[:-1], lo, hi)
        if np.max(np.abs(step[:-1])) < 1e-4:
            break
    resp = m4_response(g)
    off = np.average(target - resp, weights=W)
    err = np.sqrt(np.average((target - resp - off) ** 2, weights=W))
    return g, off, err


def show(target, gains, off, err):
    print("\nM4 band gains:")
    for fc, gn in zip(M4_BANDS, gains):
        bar = ("-" * int(round(-gn * 2))).rjust(24) + "|" + "+" * int(round(gn * 2))
        print(f"  {fc:>6} Hz  {gn:+6.2f} dB  {bar}")
    print(f"\nWeighted RMS error vs AutoEq curve: {err:.2f} dB  "
          f"(overall level offset {off:+.1f} dB, i.e. volume)")
    resp = m4_response(gains) + off
    print("\n     freq   AutoEq   M4 fit   diff")
    for fr in [20, 50, 100, 200, 500, 1000, 2000, 3000, 5000, 8000, 10000, 15000]:
        i = int(np.argmin(np.abs(F - fr)))
        print(f"  {fr:>7}  {target[i]:+6.1f}   {resp[i]:+6.1f}   {resp[i] - target[i]:+5.1f}")


# ----------------------------------------------------------- device

async def apply(gains, select: int, args):
    addr = args.address or classic_address(args.name)
    if not addr:
        sys.exit(f"no paired classic device named like {args.name!r}; pass --address")
    ch = args.channel or pick_gaia_channel(addr)
    if not ch:
        sys.exit("no GAIA RFCOMM channel found")
    link = RfcommLink(addr, ch, False)
    g = Gaia(link, 3.0)
    await g.start()
    try:
        raw = [int(round(x * 60)) for x in gains]
        n = len(raw)
        payload = bytes([0, n - 1]) + struct.pack(f">{n}h", *raw)
        r = await g.v3(MUSIC, 0x06, payload, allow_set=True)
        if not (r and r[0]):
            print("multi-band write refused, writing bands one at a time")
            for b, v in enumerate(raw):
                await g.v3(MUSIC, 0x06, struct.pack(">BBh", b, b, v), allow_set=True)

        st = await g.v3(MUSIC, 0x00)
        await g.v3(MUSIC, 0x03, bytes([select]), allow_set=True)
        sel = await g.v3(MUSIC, 0x02)
        selected = sel[1][0] if sel and sel[0] and sel[1] else None

        print("\nRead back:")
        for b in range(n):
            r = await g.v3(MUSIC, 0x05, bytes([b, b]))
            if r and r[0] and len(r[1]) >= 9:
                fq, _q, _t, gn = struct.unpack(">HHBh", r[1][2:9])
                print(f"  band {b}: {fq:>5} Hz  {gn / 60:+.2f} dB")
        print(f"  selected set: {selected}")
        if selected != select:
            idle = st and st[0] and st[1] and st[1][0] == 0
            print(f"\n!! set {select} didn't stick"
                  + (" -- EQ is idle; start playback and re-run" if idle else ""))
    finally:
        link.close()


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("preset", nargs="?", help="AutoEq ParametricEQ/GraphicEQ file or URL")
    src.add_argument("--search", metavar="NAME", help="look up an IEM in AutoEq's index")
    src.add_argument("--flat", action="store_true", help="reset bands to 0 dB, select flat")
    src.add_argument("--gains", metavar="G0,G1,G2,G3,G4",
                     help="set the 5 band gains in dB directly (no AutoEq)")
    ap.add_argument("--pick", type=int, help="choose among several AutoEq measurements")
    ap.add_argument("--max-boost", type=float, default=6.0,
                    help="max positive band gain in dB (default 6; 0 = cuts only)")
    ap.add_argument("--max-cut", type=float, default=12.0, help="max cut in dB (default 12)")
    ap.add_argument("--dry-run", action="store_true", help="show the fit, don't touch the M4")
    ap.add_argument("--name", default="BGVP M4")
    ap.add_argument("--address", help="classic BD address of the M4")
    ap.add_argument("--channel", type=int, help="GAIA RFCOMM channel")
    args = ap.parse_args()
    if args.max_boost > 6:
        sys.exit("refusing --max-boost above 6 dB")

    if args.flat:
        gains, select = np.zeros(len(M4_BANDS)), 0
        print("Resetting user EQ to flat and selecting set 0")
    elif args.gains:
        gains = np.array([float(x) for x in args.gains.split(",")])
        if len(gains) != len(M4_BANDS):
            sys.exit(f"--gains needs {len(M4_BANDS)} comma-separated values")
        if gains.max() > args.max_boost or gains.min() < -args.max_cut:
            sys.exit(f"gains must be within -{args.max_cut}..+{args.max_boost} dB")
        select = USER_SET
        print("Setting gains: " + ", ".join(f"{f} Hz {g:+.1f}" for f, g in zip(M4_BANDS, gains)))
    else:
        if args.search:
            label, url = search_autoeq(args.search, args.pick)
            print(f"AutoEq: {label}\n  {urllib.parse.unquote(url)}")
            txt = fetch(url)
        elif re.match(r"https?://", args.preset):
            txt = fetch(args.preset)
        else:
            with open(args.preset, encoding="utf-8") as fh:
                txt = fh.read()
        target, kind = target_from_text(txt)
        print(f"Target from {kind}")
        gains, off, err = fit(target, -args.max_cut, args.max_boost)
        show(target, gains, off, err)
        select = USER_SET
        if max(gains) > 0:
            print(f"\nNote: {max(gains):+.1f} dB peak boost and the M4 exposes no preamp; "
                  "if loud tracks distort, re-run with --max-boost 0.")

    if args.dry_run:
        return
    asyncio.run(apply(gains, select, args))


if __name__ == "__main__":
    main()
