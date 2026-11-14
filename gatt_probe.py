#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["bleak>=0.22"]
# ///
"""
Read-only probe for a Bluetooth receiver (e.g. BGVP M4, QCC5181) to see whether
it exposes Qualcomm GAIA -- the control channel vendor apps use for user EQ.

  1. BLE scan, print advertisement data
  2. BLE connect, enumerate all GATT services/characteristics/descriptors,
     read readable characteristics (never writes anything)
  3. Ask BlueZ (D-Bus) for the device's known UUIDs, which include classic
     BR/EDR SDP records (GAIA-over-RFCOMM) if the device was paired to this PC

Run:   pipx run gatt_probe.py                 # scan for names containing "M4"
       pipx run gatt_probe.py --name BGVP
       pipx run gatt_probe.py --address AA:BB:CC:DD:EE:FF
       pipx run gatt_probe.py --list           # just list everything nearby

Put the neckband in pairing mode and disconnect it from your phone first,
otherwise it may not advertise or accept a connection.
"""

import argparse
import asyncio
import sys
import warnings

warnings.filterwarnings("ignore", message="Using default MTU value")

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError

SIG_BASE = "-0000-1000-8000-00805f9b34fb"
QCOM_BASE = "-d102-11e1-9b23-00025b00a5a5"  # CSR / Qualcomm vendor UUID base

QCOM_KNOWN = {
    "00001100" + QCOM_BASE: "GAIA service (BLE)",
    "00001101" + QCOM_BASE: "GAIA command endpoint",
    "00001102" + QCOM_BASE: "GAIA response endpoint",
    "00001103" + QCOM_BASE: "GAIA data endpoint",
    "00001107" + QCOM_BASE: "GAIA over RFCOMM/SPP (classic)",
    "00001016" + QCOM_BASE: "CSR OTA update service (probably)",
}

# Bluetooth SIG company identifiers worth flagging in manufacturer data
COMPANY_IDS = {
    0x000A: "Qualcomm Technologies Intl (ex-CSR)",
    0x001D: "Qualcomm",
}

# SIG services that hint at device-side audio processing / presets
INTERESTING_SIG = {
    0x1854: "Hearing Access Service -- has preset support",
    0x1844: "Volume Control",
    0x1843: "Audio Input Control",
    0x1845: "Volume Offset Control",
}


def label(uuid: str) -> str:
    u = uuid.lower()
    if u in QCOM_KNOWN:
        return f"*** QUALCOMM: {QCOM_KNOWN[u]} ***"
    if u.endswith(QCOM_BASE):
        return "*** QUALCOMM/CSR vendor UUID (unknown) ***"
    if u.endswith(SIG_BASE):
        short = int(u[:8], 16)
        if short in INTERESTING_SIG:
            return f"<-- {INTERESTING_SIG[short]}"
        return ""
    return "<-- vendor-specific 128-bit UUID"


def fmt_bytes(b: bytes, limit: int = 64) -> str:
    shown = b[:limit]
    hx = shown.hex(" ")
    asc = "".join(chr(c) if 32 <= c < 127 else "." for c in shown)
    more = f" (+{len(b) - limit} bytes)" if len(b) > limit else ""
    return f"[{len(b)}] {hx}  |{asc}|{more}"


async def scan(timeout: float):
    print(f"Scanning BLE for {timeout:.0f}s ...", file=sys.stderr)
    found = await BleakScanner.discover(timeout=timeout, return_adv=True)
    return list(found.values())  # [(BLEDevice, AdvertisementData)]


def print_adv(dev, adv):
    print(f"  {dev.address}  rssi={adv.rssi}  name={adv.local_name or dev.name!r}")
    for u in adv.service_uuids:
        print(f"      adv service: {u} {label(u)}")
    for cid, data in adv.manufacturer_data.items():
        who = COMPANY_IDS.get(cid, "")
        print(f"      manufacturer 0x{cid:04X} {who}: {fmt_bytes(data)}")
    for u, data in adv.service_data.items():
        print(f"      service data {u}: {fmt_bytes(data)}")


async def enumerate_gatt(device, read_values: bool, read_timeout: float) -> list[str]:
    seen: list[str] = []
    print(f"\n== Connecting to {device.address} over BLE ...")
    async with BleakClient(device, timeout=30) as client:
        print(f"   connected")
        # Pass 1: print the whole tree. Service discovery already happened on
        # connect, so this needs no further radio traffic and can't hang.
        readable = []
        for svc in client.services:
            seen.append(svc.uuid)
            print(f"\n[service] {svc.uuid}  {svc.description}  {label(svc.uuid)}")
            for ch in svc.characteristics:
                seen.append(ch.uuid)
                props = ",".join(ch.properties)
                print(f"   [char] h=0x{ch.handle:04X} {ch.uuid}  {ch.description}  "
                      f"({props})  {label(ch.uuid)}")
                if "read" in ch.properties:
                    readable.append(ch)
                for d in ch.descriptors:
                    print(f"          [desc] h=0x{d.handle:04X} {d.uuid}  {d.description}")

        if not read_values:
            return seen

        # Pass 2: read values, each with a timeout. A read the device never
        # answers stalls the ATT bearer, so stop reading after the first timeout.
        print(f"\n== Reading {len(readable)} readable characteristics "
              f"({read_timeout:.0f}s timeout each)")
        for ch in readable:
            tag = f"h=0x{ch.handle:04X} {ch.uuid}"
            try:
                val = await asyncio.wait_for(client.read_gatt_char(ch), read_timeout)
                print(f"   {tag}: {fmt_bytes(bytes(val))}")
            except asyncio.TimeoutError:
                print(f"   {tag}: TIMEOUT -- device didn't answer (probably needs "
                      f"pairing/encryption). Skipping remaining reads.")
                break
            except Exception as e:  # e.g. insufficient authentication
                print(f"   {tag}: read failed: {type(e).__name__}: {e}")
    return seen


async def bluez_known_uuids(address: str | None, name: str | None) -> list[str]:
    """UUIDs BlueZ has cached for matching devices (includes classic SDP after pairing)."""
    try:
        from dbus_fast import BusType
        from dbus_fast.aio import MessageBus
    except ImportError:
        print("\n(dbus_fast unavailable; skipping BlueZ cache check)")
        return []

    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    try:
        intro = await bus.introspect("org.bluez", "/")
        om = bus.get_proxy_object("org.bluez", "/", intro).get_interface(
            "org.freedesktop.DBus.ObjectManager")
        objs = await om.call_get_managed_objects()
    finally:
        bus.disconnect()

    out: list[str] = []
    print("\n== BlueZ cached device info (classic + LE)")
    hits = 0
    for path, ifaces in objs.items():
        dev = ifaces.get("org.bluez.Device1")
        if not dev:
            continue
        addr = dev["Address"].value
        dname = dev["Name"].value if "Name" in dev else ""
        match_addr = address and addr.upper() == address.upper()
        match_name = name and name.lower() in (dname or "").lower()
        if not (match_addr or match_name):
            continue
        hits += 1
        paired = dev["Paired"].value if "Paired" in dev else False
        uuids = dev["UUIDs"].value if "UUIDs" in dev else []
        print(f"  {addr}  name={dname!r}  paired={paired}  type={dev['AddressType'].value}")
        if not uuids:
            print("      no UUIDs cached (pair it with this PC to get classic SDP records)")
        for u in uuids:
            out.append(u)
            print(f"      {u} {label(u)}")
    if not hits:
        print("  no matching device known to BlueZ")
    return out


async def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--name", default="M4", help="substring of device name to match (default: M4)")
    ap.add_argument("--address", help="connect to this BLE address directly")
    ap.add_argument("--scan-time", type=float, default=10.0)
    ap.add_argument("--list", action="store_true", help="only list nearby BLE devices")
    ap.add_argument("--no-read", action="store_true", help="don't read characteristic values")
    ap.add_argument("--read-timeout", type=float, default=5.0,
                    help="seconds to wait for each characteristic read (default: 5)")
    args = ap.parse_args()

    results = await scan(args.scan_time)

    if args.list:
        print(f"\n{len(results)} BLE devices:")
        for dev, adv in sorted(results, key=lambda r: -r[1].rssi):
            print_adv(dev, adv)
        return

    if args.address:
        matches = [r for r in results if r[0].address.upper() == args.address.upper()]
    else:
        needle = args.name.lower()
        matches = [r for r in results
                   if needle in ((r[1].local_name or r[0].name or "").lower())]

    target = None
    if len(matches) == 1:
        target = matches[0]
    elif len(matches) > 1:
        print("\nSeveral devices match; re-run with --address:")
        for dev, adv in matches:
            print_adv(dev, adv)
    else:
        print("\nNo matching device advertising over BLE. Seen:")
        for dev, adv in sorted(results, key=lambda r: -r[1].rssi):
            print_adv(dev, adv)
        print("\nIs it in pairing mode and disconnected from your phone?")

    seen: list[str] = []
    if target:
        dev, adv = target
        print("\n== Advertisement")
        print_adv(dev, adv)
        seen += adv.service_uuids
        try:
            seen += await enumerate_gatt(dev, read_values=not args.no_read,
                                         read_timeout=args.read_timeout)
        except (BleakError, asyncio.TimeoutError, OSError) as e:
            print(f"\nBLE connect/enumerate failed: {type(e).__name__}: {e}")

    seen += await bluez_known_uuids(
        target[0].address if target else args.address,
        None if args.address else args.name)

    # ---- verdict
    seen_l = {u.lower() for u in seen}
    qcom = sorted(u for u in seen_l if u.endswith(QCOM_BASE))
    vendor = sorted(u for u in seen_l
                    if not u.endswith(SIG_BASE) and not u.endswith(QCOM_BASE))
    print("\n== Summary")
    if qcom:
        print("Qualcomm/CSR UUIDs present -- GAIA control channel likely available:")
        for u in qcom:
            print(f"   {u}  {QCOM_KNOWN.get(u, 'unknown')}")
        print("Next step: query GAIA feature list (still read-only) to see if a "
              "user-EQ feature is implemented.")
    else:
        print("No Qualcomm GAIA UUIDs found.")
    if vendor:
        print("Other vendor-specific UUIDs (worth a closer look):")
        for u in vendor:
            print(f"   {u}")
    if not seen_l:
        print("Nothing enumerated -- device not reachable over BLE and not known to BlueZ.")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
