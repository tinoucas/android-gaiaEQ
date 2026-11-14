#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["bleak>=0.22"]
# ///
"""
Query a Qualcomm GAIA device (e.g. BGVP M4, QCC5181) over BLE to find out
whether its firmware implements a user EQ.

Only *get* commands from a hard-coded whitelist are ever sent; nothing that
sets EQ, resets, or upgrades. Responses are printed raw as well as decoded,
because the decoding of the EQ payloads is from memory of Qualcomm's ADK and
may be off.

Run:   pipx run gaia_query.py                 # find "BGVP M4" by name
       pipx run gaia_query.py --address XX:XX:...
       pipx run gaia_query.py --pair          # LE-pair first if GAIA refuses
       pipx run gaia_query.py --rfcomm        # GAIA over classic SPP (uses existing bond)

GAIA v3 PDU over BLE:  vendor_id(2, BE) | command_id(2, BE) | payload
  command_id = feature(7 bits) << 9 | type(2 bits) << 7 | command(7 bits)
  type: 0 command, 1 notification, 2 response, 3 error
"""

import argparse
import asyncio
import socket
import struct
import sys
import threading
import warnings

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError

warnings.filterwarnings("ignore", message="Using default MTU value")

Q = "-d102-11e1-9b23-00025b00a5a5"
GAIA_CMD = "00001101" + Q
GAIA_RESP = "00001102" + Q

V3_VENDOR = 0x001D  # Qualcomm
V2_VENDOR = 0x000A  # CSR (legacy GAIA)

FEATURES = {
    0x00: "Core",
    0x01: "Earbud (TWS)",
    0x02: "ANC",
    0x03: "Voice UI",
    0x04: "Debug",
    0x05: "Music Processing (EQ)",
    0x06: "Upgrade",
    0x07: "Voice Processing",
}

ERRORS = {
    0: "feature not supported",
    1: "command not supported",
    2: "failed: not authenticated",
    3: "failed: insufficient resources",
    4: "in progress",
    5: "invalid parameter",
}

# Whitelist: (feature, command) -> name. Only these can be sent.
CORE, MUSIC = 0x00, 0x05
# Commands that change device state. Only sent when explicitly requested.
SETTERS = {
    (0x05, 0x03): "Set selected EQ set",
    (0x05, 0x06): "Set user EQ band configuration",
}

SAFE = {
    (CORE, 0x00): "Get API version",
    (CORE, 0x01): "Get supported features",
    (CORE, 0x02): "Get supported features (next)",
    (CORE, 0x03): "Get serial number",
    (CORE, 0x04): "Get variant name",
    (CORE, 0x05): "Get application version",
    (MUSIC, 0x00): "Get EQ state",
    (MUSIC, 0x01): "Get available EQ presets",
    (MUSIC, 0x02): "Get selected EQ set",
    (MUSIC, 0x04): "Get user EQ number of bands",
    (MUSIC, 0x05): "Get user EQ band configuration",
}



def hx(b: bytes) -> str:
    return b.hex(" ") if b else "(empty)"


def text(b: bytes) -> str:
    return b.decode("utf-8", "replace").rstrip("\x00")


# ---------------------------------------------------------------- transports
# Each link delivers whole GAIA PDUs (vendor|command|payload) to `feed`, and
# sends whole PDUs with `send`.

class BleLink:
    def __init__(self, client: BleakClient, timeout: float):
        self.c = client
        self.timeout = timeout

    async def open(self, feed):
        await self.c.start_notify(GAIA_RESP, lambda _s, d: feed(bytes(d)))

    async def send(self, pkt: bytes):
        # An unencrypted write to a protected endpoint can stall forever on BlueZ.
        try:
            await asyncio.wait_for(self.c.write_gatt_char(GAIA_CMD, pkt, response=True),
                                   self.timeout + 5)
        except asyncio.TimeoutError:
            sys.exit("write to GAIA command endpoint stalled -- link probably needs "
                     "encryption; re-run with --pair")


class RfcommLink:
    """GAIA over classic RFCOMM. Frame: 0xFF | version | flags | len | PDU [| checksum]
    flags bit0 = checksum present, bit1 = 2-byte length."""

    def __init__(self, addr: str, channel: int, debug: bool):
        self.addr, self.channel, self.debug = addr, channel, debug

    async def open(self, feed):
        loop = asyncio.get_running_loop()
        self.s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM,
                               socket.BTPROTO_RFCOMM)
        self.s.settimeout(15)
        await loop.run_in_executor(None, self.s.connect, (self.addr, self.channel))
        self.s.settimeout(None)
        threading.Thread(target=self._reader, args=(loop, feed), daemon=True).start()

    def _reader(self, loop, feed):
        buf = b""
        while True:
            try:
                d = self.s.recv(1024)
            except OSError:
                break
            if not d:
                break
            if self.debug:
                print(f"      (raw rx: {hx(d)})")
            buf += d
            while True:
                k = buf.find(b"\xff")
                if k < 0:
                    buf = b""
                    break
                buf = buf[k:]
                if len(buf) < 4:
                    break
                flags = buf[2]
                if flags & 2:
                    if len(buf) < 5:
                        break
                    n, hdr = int.from_bytes(buf[3:5], "big"), 5
                else:
                    n, hdr = buf[3], 4
                total = hdr + 4 + n + (1 if flags & 1 else 0)
                if len(buf) < total:
                    break
                pdu, buf = buf[hdr:hdr + 4 + n], buf[total:]
                loop.call_soon_threadsafe(feed, pdu)
        print("      (RFCOMM link closed)")

    async def send(self, pkt: bytes):
        frame = bytes([0xFF, 0x01, 0x00, len(pkt) - 4]) + pkt
        await asyncio.get_running_loop().run_in_executor(None, self.s.sendall, frame)

    def close(self):
        try:
            self.s.close()
        except OSError:
            pass


# ---------------------------------------------------------------- SDP (classic)

def _de_parse(b: bytes, i: int):
    """Parse one SDP data element at b[i]; returns (value, next_index)."""
    h = b[i]
    t, s = h >> 3, h & 7
    i += 1
    if t == 0:
        return None, i
    if s < 5:
        n = (1, 2, 4, 8, 16)[s]
    else:
        k = (1, 2, 4)[s - 5]
        n, i = int.from_bytes(b[i:i + k], "big"), i + k
    raw, j = b[i:i + n], i + n
    if t == 1:
        return int.from_bytes(raw, "big"), j
    if t == 2:
        return int.from_bytes(raw, "big", signed=True), j
    if t == 3:
        if len(raw) == 16:
            h_ = raw.hex()
            return f"{h_[:8]}-{h_[8:12]}-{h_[12:16]}-{h_[16:20]}-{h_[20:]}", j
        return f"{int.from_bytes(raw, 'big'):08x}" + SIG_BASE, j
    if t in (4, 8):
        return raw.decode("utf-8", "replace").rstrip("\x00"), j
    if t == 5:
        return bool(raw[0]), j
    if t in (6, 7):
        out, k = [], i
        while k < j:
            v, k = _de_parse(b, k)
            out.append(v)
        return out, j
    return raw, j


def sdp_records(addr: str, timeout: float = 15) -> list[dict]:
    """ServiceSearchAttributeRequest for every record with L2CAP in it."""
    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
    s.settimeout(timeout)
    s.connect((addr, 1))  # PSM 1 = SDP
    pattern = b"\x35\x03\x19\x01\x00"         # DES{ UUID16 0x0100 (L2CAP) }
    attrs = b"\x35\x05\x0a\x00\x00\xff\xff"    # DES{ range 0x0000-0xFFFF }
    data, cont, tid = b"", b"\x00", 0
    try:
        while True:
            tid += 1
            params = pattern + b"\xff\xff" + attrs + cont
            s.send(struct.pack(">BHH", 0x06, tid, len(params)) + params)
            r = s.recv(4096)
            if r[0] != 0x07:
                raise RuntimeError(f"SDP error response: {hx(r)}")
            cnt = struct.unpack(">H", r[5:7])[0]
            data += r[7:7 + cnt]
            cont = r[7 + cnt:]
            if not cont or cont[0] == 0:
                break
    finally:
        s.close()
    lists, _ = _de_parse(data, 0)
    return [dict(zip(rec[0::2], rec[1::2])) for rec in lists]


def rfcomm_channel(rec: dict) -> int | None:
    for proto in rec.get(0x0004) or []:
        if isinstance(proto, list) and proto and proto[0] == "00000003" + SIG_BASE \
                and len(proto) > 1:
            return proto[1]
    return None


SIG_BASE = "-0000-1000-8000-00805f9b34fb"
SPP_UUID = "00001101" + SIG_BASE
GAIA_RFCOMM_UUID = "00001107" + Q


def pick_gaia_channel(addr: str) -> int | None:
    print(f"SDP query {addr} ...")
    recs = sdp_records(addr)
    best = None
    for rec in recs:
        classes = [c for c in (rec.get(0x0001) or []) if isinstance(c, str)]
        ch = rfcomm_channel(rec)
        name = rec.get(0x0100, "")
        tag = ""
        if GAIA_RFCOMM_UUID in classes:
            tag, best = "  <-- GAIA", ch
        elif SPP_UUID in classes:
            tag = "  <-- SPP"
            best = best if best is not None else ch
        print(f"   rfcomm={ch!s:>4}  {name!r:<28} {', '.join(c[:8] if c.endswith(SIG_BASE) else c for c in classes)}{tag}")
    return best


def classic_address(name: str) -> str | None:
    """Find the bonded classic address of a device whose name contains `name`."""
    import subprocess
    out = subprocess.run(
        ["busctl", "--system", "--json=short", "call", "org.bluez", "/",
         "org.freedesktop.DBus.ObjectManager", "GetManagedObjects"],
        capture_output=True, text=True, check=True).stdout
    import json
    hits = []
    for _path, ifs in json.loads(out)["data"][0].items():
        dev = ifs.get("org.bluez.Device1")
        if not dev:
            continue
        g = lambda k: dev.get(k, {}).get("data")
        if name.lower() in (g("Name") or "").lower() and g("Paired") \
                and g("AddressType") == "public":
            hits.append((g("Address"), g("Name"), g("Connected")))
    if len(hits) > 1:
        print(f"Several paired devices match {name!r}; pick one with --address:")
        for addr, nm, conn in hits:
            print(f"   {addr}  {nm!r}  {'connected' if conn else 'not connected'}")
        sys.exit(1)
    return hits[0][0] if hits else None


# ---------------------------------------------------------------- GAIA client

class Gaia:
    def __init__(self, link, timeout: float):
        self.link = link
        self.timeout = timeout
        self.q: asyncio.Queue[bytes] = asyncio.Queue()

    async def start(self):
        await self.link.open(self.q.put_nowait)

    async def _write(self, pkt: bytes):
        await self.link.send(pkt)

    async def _await_reply(self, match) -> bytes | None:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + self.timeout
        while True:
            left = deadline - loop.time()
            if left <= 0:
                return None
            try:
                pkt = await asyncio.wait_for(self.q.get(), left)
            except asyncio.TimeoutError:
                return None
            if match(pkt):
                return pkt
            print(f"      (unsolicited: {hx(pkt)})")

    async def v3(self, feature: int, cmd: int, payload: bytes = b"",
                 allow_set: bool = False):
        """Send a whitelisted v3 command. Returns (ok, payload) or None on timeout."""
        name = SAFE.get((feature, cmd))
        if name is None and allow_set:
            name = SETTERS.get((feature, cmd))
        if name is None:
            raise ValueError(f"refusing to send non-whitelisted command {feature}:{cmd}")
        cid = (feature << 9) | cmd
        pkt = struct.pack(">HH", V3_VENDOR, cid) + payload
        print(f"\n>> [{FEATURES.get(feature, feature)}] {name}  tx: {hx(pkt)}")
        await self._write(pkt)

        def match(p: bytes) -> bool:
            if len(p) < 4:
                return False
            v, c = struct.unpack(">HH", p[:4])
            return v == V3_VENDOR and c >> 9 == feature and c & 0x7F == cmd \
                and (c >> 7) & 3 in (2, 3)

        r = await self._await_reply(match)
        if r is None:
            print("<< no reply (timeout)")
            return None
        kind = (struct.unpack(">H", r[2:4])[0] >> 7) & 3
        body = r[4:]
        if kind == 3:
            code = body[0] if body else -1
            print(f"<< ERROR {code}: {ERRORS.get(code, 'unknown')}  rx: {hx(r)}")
            return False, body
        print(f"<< ok  rx: {hx(r)}")
        return True, body

    async def v2_api_version(self):
        pkt = struct.pack(">HH", V2_VENDOR, 0x0300)
        print(f"\n>> [GAIA v2] Get API version  tx: {hx(pkt)}")
        await self._write(pkt)

        def match(p: bytes) -> bool:
            return len(p) >= 4 and struct.unpack(">HH", p[:4]) == (V2_VENDOR, 0x8300)

        r = await self._await_reply(match)
        print("<< no reply (timeout)" if r is None else f"<< rx: {hx(r)}")
        return r


async def run(g: Gaia):
    r = await g.v3(CORE, 0x00)
    if r and r[0]:
        b = r[1]
        if len(b) >= 2:
            print(f"   GAIA protocol v{b[0]}, API {'.'.join(map(str, b[1:]))}")
    else:
        print("\nNo GAIA v3 answer; trying legacy GAIA v2.")
        await g.v2_api_version()
        print("\nv2 EQ commands aren't implemented here; stopping.")
        return

    # Supported features (paged with more_to_come flag)
    features: dict[int, int] = {}
    r = await g.v3(CORE, 0x01)
    while r and r[0]:
        b = r[1]
        more, pairs = (b[0], b[1:]) if b else (0, b"")
        for i in range(0, len(pairs) - 1, 2):
            features[pairs[i]] = pairs[i + 1]
        if not more:
            break
        r = await g.v3(CORE, 0x02)
    if features:
        print("\n   Supported features:")
        for f, ver in sorted(features.items()):
            print(f"     0x{f:02X} {FEATURES.get(f, '?'):<24} v{ver}")

    for cmd, fmt in ((0x03, "serial"), (0x04, "variant"), (0x05, "app version")):
        r = await g.v3(CORE, cmd)
        if r and r[0]:
            print(f"   {fmt}: {text(r[1])!r}")

    if MUSIC not in features:
        print("\n== Verdict: Music Processing (EQ) feature NOT advertised by firmware.")
        if features:
            return
        print("   (feature list was empty/unreadable, probing Music Processing anyway)")

    nbands = await eq_dump(g)
    print(f"\n== Verdict: firmware implements Qualcomm user EQ with {nbands} bands."
          if nbands else "\n== Verdict: no user EQ bands reported.")


async def eq_dump(g: Gaia) -> int:
    r = await g.v3(MUSIC, 0x00)
    if r and r[0] and r[1]:
        print(f"   EQ state: {r[1][0]} (meaning not confirmed)")
    r = await g.v3(MUSIC, 0x01)
    if r and r[0] and r[1]:
        n, ids = r[1][0], list(r[1][1:])
        print(f"   {n} presets: {ids}  (63 = user EQ set in Qualcomm ADK)")
    r = await g.v3(MUSIC, 0x02)
    if r and r[0] and r[1]:
        print(f"   selected set: {r[1][0]}")

    r = await g.v3(MUSIC, 0x04)
    nbands = r[1][0] if r and r[0] and r[1] else 0
    print(f"   user EQ bands: {nbands}")
    for band in range(nbands):
        r = await g.v3(MUSIC, 0x05, bytes([band, band]))
        if not (r and r[0]):
            continue
        b = r[1]
        # Tentative layout: start, end, then per band freq u16, Q u16 (/4096),
        # filter type u8, gain i16 (/60 dB). Raw bytes printed above regardless.
        if len(b) >= 9:
            freq, qv, ftype, gain = struct.unpack(">HHBh", b[2:9])
            print(f"   band {band}: {freq} Hz  Q={qv / 4096:.2f}  type={ftype}  "
                  f"gain={gain / 60:+.2f} dB   (tentative decode)")
    return nbands


async def eq_set(g: Gaia, args) -> None:
    if args.set_band is not None:
        if args.gain is None:
            sys.exit("--set-band needs --gain")
        if args.gain > 6:
            sys.exit("refusing boosts above +6 dB")
        b, gain = args.set_band, round(args.gain * 60)
        if args.freq is None:
            payload = struct.pack(">BBh", b, b, gain)
            fmt = "gain-only"
        else:
            payload = struct.pack(">BBHHBh", b, b, args.freq, round(args.q * 4096),
                                  args.ftype, gain)
            fmt = "full (freq, Q, type, gain)"
        print(f"\n## setting band {b} using {fmt} payload")
        await g.v3(MUSIC, 0x06, payload, allow_set=True)
    if args.select is not None:
        print(f"\n## selecting EQ set {args.select}")
        await g.v3(MUSIC, 0x03, bytes([args.select]), allow_set=True)
    await asyncio.sleep(0.5)  # let any notifications arrive


async def dispatch(g: Gaia, args):
    if args.select is None and args.set_band is None:
        await run(g)
        return
    await eq_set(g, args)
    print("\n## read back")
    await eq_dump(g)


async def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--name", default="BGVP M4")
    ap.add_argument("--address", help="BLE address (BLE mode) or BD address (--rfcomm)")
    ap.add_argument("--rfcomm", action="store_true",
                    help="use classic RFCOMM (SPP) instead of BLE; needs a classic bond")
    ap.add_argument("--channel", type=int, help="RFCOMM channel (default: from SDP)")
    ap.add_argument("--pair", action="store_true", help="LE-pair before talking GAIA")
    ap.add_argument("--timeout", type=float, default=3.0, help="per-command reply timeout")
    ap.add_argument("--debug", action="store_true", help="dump raw RFCOMM bytes")
    w = ap.add_argument_group("EQ changes (write to device)")
    w.add_argument("--select", type=int, help="select EQ set (0 = flat, 63 = user)")
    w.add_argument("--set-band", type=int, help="user EQ band index to change")
    w.add_argument("--gain", type=float, help="band gain in dB (max +6)")
    w.add_argument("--freq", type=int, help="band frequency in Hz (uses full payload)")
    w.add_argument("--q", type=float, default=0.7, help="band Q with --freq (default 0.7)")
    w.add_argument("--ftype", type=int, default=13, help="filter type with --freq (default 13)")
    args = ap.parse_args()

    if args.rfcomm:
        addr = args.address or classic_address(args.name)
        if not addr:
            sys.exit(f"no paired classic device named like {args.name!r}; pass --address")
        ch = args.channel or pick_gaia_channel(addr)
        if not ch:
            sys.exit("no GAIA/SPP RFCOMM service found in SDP records")
        print(f"\nConnecting RFCOMM {addr} channel {ch} ...")
        link = RfcommLink(addr, ch, args.debug)
        g = Gaia(link, args.timeout)
        await g.start()
        print("connected")
        try:
            await (dispatch(g, args))
        finally:
            link.close()
        return

    if args.address:
        dev = await BleakScanner.find_device_by_address(args.address, timeout=10)
    else:
        dev = await BleakScanner.find_device_by_name(args.name, timeout=10)
    if dev is None:
        sys.exit("device not found over BLE (is it on and in range?)")
    print(f"Found {dev.name} at {dev.address}")

    async with BleakClient(dev, timeout=30) as client:
        try:
            await client._backend._acquire_mtu()  # bigger notifications if possible
        except Exception:
            pass
        print(f"connected, MTU={client.mtu_size}")
        if args.pair:
            print("LE pairing ... (accept any prompt from your desktop)")
            await asyncio.wait_for(client.pair(), 60)
            print("paired")
        g = Gaia(BleLink(client, args.timeout), args.timeout)
        try:
            await g.start()
        except BleakError as e:
            sys.exit(f"can't subscribe to GAIA responses: {e}\n"
                     f"-> try again with --pair, or use --rfcomm")
        await dispatch(g, args)

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
