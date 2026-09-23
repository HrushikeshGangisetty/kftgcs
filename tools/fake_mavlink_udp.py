#!/usr/bin/env python3
"""Fake MAVLink vehicle over UDP, for testing the app's UDP link without a drone.

Stdlib only — no pymavlink, no SITL. It streams a valid MAVLink v2 HEARTBEAT at 1 Hz, which is
what the app waits for before it reports "connected" (see TelemetryRepository: `connected` flips on
the FCU heartbeat, not on the socket opening).

Two modes, matching the two kinds of link the app has to cope with:

  push    — sends telemetry unprompted to the app's address, like a Skydroid RC router or
            `sim_vehicle.py --out udp:<phone-ip>:14550`. Tests the app's LISTEN-ONLY mode
            (Remote Host blank).

  listen  — binds a port, stays silent until something sends to it, then streams back to that
            sender. Like a SIYI MK15 datalink. Tests the app's SEEDED mode (Remote Host set) and
            proves the app's outbound registration packet is actually being transmitted.

Usage
-----
    # App in LISTEN-ONLY mode: Local Port 14550, Remote Host blank
    python fake_mavlink_udp.py push --to 192.168.1.50:14550

    # App in SEEDED mode: Remote Host <this-pc-ip>, Remote Port 14550, Local Port 14550
    python fake_mavlink_udp.py listen --port 14550

    # Check the frame builder without any network
    python fake_mavlink_udp.py selftest

The PC and the device must be on the same network. If they are not, either join the PC to the
device's Wi-Fi, or turn on USB tethering on the device and use the address it gets.
"""

import argparse
import socket
import sys
import time

HEARTBEAT_MSGID = 0
HEARTBEAT_CRC_EXTRA = 50

MAV_TYPE_QUADROTOR = 2
MAV_AUTOPILOT_ARDUPILOTMEGA = 3
MAV_MODE_FLAG_SAFETY_ARMED = 128
MAV_STATE_STANDBY = 3

VEHICLE_SYSID = 1
VEHICLE_COMPID = 1  # MAV_COMP_ID_AUTOPILOT1 — the app only treats compid 1 as the flight controller


def crc_accumulate(byte, crc):
    """X25 / CRC-16-MCRF4XX, one byte. Same routine MAVLink uses for its frame checksum."""
    tmp = (byte ^ (crc & 0xFF)) & 0xFF
    tmp = (tmp ^ (tmp << 4)) & 0xFF
    return ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xFFFF


def crc16(data, crc=0xFFFF):
    for b in data:
        crc = crc_accumulate(b, crc)
    return crc


def heartbeat_frame(seq):
    """One MAVLink v2 HEARTBEAT frame, as an autopilot would send it."""
    payload = bytes([
        0, 0, 0, 0,                     # custom_mode (u32, little endian)
        MAV_TYPE_QUADROTOR,             # type
        MAV_AUTOPILOT_ARDUPILOTMEGA,    # autopilot
        MAV_MODE_FLAG_SAFETY_ARMED,     # base_mode
        MAV_STATE_STANDBY,              # system_status
        3,                              # mavlink_version
    ])
    header = bytes([
        0xFD,              # STX (v2)
        len(payload),      # LEN
        0,                 # incompat_flags
        0,                 # compat_flags
        seq & 0xFF,        # seq
        VEHICLE_SYSID,
        VEHICLE_COMPID,
        HEARTBEAT_MSGID & 0xFF,
        (HEARTBEAT_MSGID >> 8) & 0xFF,
        (HEARTBEAT_MSGID >> 16) & 0xFF,
    ])
    crc = crc16(header[1:] + payload)
    crc = crc_accumulate(HEARTBEAT_CRC_EXTRA, crc)
    return header + payload + bytes([crc & 0xFF, (crc >> 8) & 0xFF])


def selftest():
    # Published check vector for CRC-16/MCRF4XX, the variant MAVLink uses: "123456789" -> 0x6F91.
    # (0x906E is CRC-16/X-25, which reflects and final-XORs — not this one.)
    assert crc16(b"123456789") == 0x6F91, hex(crc16(b"123456789"))
    frame = heartbeat_frame(0)
    assert len(frame) == 21, len(frame)
    assert frame[0] == 0xFD and frame[1] == 9
    # A receiver recomputes the checksum the same way and must land on the frame's own bytes.
    crc = crc_accumulate(HEARTBEAT_CRC_EXTRA, crc16(frame[1:-2]))
    assert frame[-2:] == bytes([crc & 0xFF, (crc >> 8) & 0xFF])
    print("selftest OK -", frame.hex(" "))


def push(target, rate_hz):
    host, port = target
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    print("pushing HEARTBEAT to %s:%d at %.1f Hz - set the app to LISTEN-ONLY on port %d "
          "(Remote Host blank)" % (host, port, rate_hz, port))
    seq = 0
    while True:
        sock.sendto(heartbeat_frame(seq), (host, port))
        seq += 1
        if seq % 5 == 0:
            print("  sent %d" % seq)
        time.sleep(1.0 / rate_hz)


def listen(port, rate_hz):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", port))
    sock.settimeout(0.2)
    print("listening on 0.0.0.0:%d, silent until something sends to me "
          "(this is how a SIYI MK15 behaves)" % port)
    peer = None
    seq = 0
    last = 0.0
    received = 0
    while True:
        try:
            data, addr = sock.recvfrom(65535)
            received += 1
            if peer != addr:
                peer = addr
                print("  peer registered: %s:%d - streaming back now" % addr)
            print("  rx %d bytes from %s:%d%s" %
                  (len(data), addr[0], addr[1], " (MAVLink)" if data[:1] in (b"\xfd", b"\xfe") else ""))
        except socket.timeout:
            pass
        now = time.time()
        if peer and now - last >= 1.0 / rate_hz:
            last = now
            sock.sendto(heartbeat_frame(seq), peer)
            seq += 1
            if seq % 5 == 0:
                print("  sent %d to %s:%d (received %d)" % (seq, peer[0], peer[1], received))


def parse_target(value):
    if ":" not in value:
        raise argparse.ArgumentTypeError("expected HOST:PORT, got %r" % value)
    host, port = value.rsplit(":", 1)
    return host, int(port)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="mode", required=True)

    p = sub.add_parser("push", help="send telemetry unprompted (tests listen-only mode)")
    p.add_argument("--to", required=True, type=parse_target, metavar="HOST:PORT")
    p.add_argument("--rate", type=float, default=1.0, help="heartbeats per second (default 1)")

    l = sub.add_parser("listen", help="stay silent until spoken to (tests seeded mode)")
    l.add_argument("--port", type=int, default=14550)
    l.add_argument("--rate", type=float, default=1.0)

    sub.add_parser("selftest", help="verify the frame builder, no network")

    args = ap.parse_args()
    try:
        if args.mode == "selftest":
            selftest()
        elif args.mode == "push":
            push(args.to, args.rate)
        else:
            listen(args.port, args.rate)
    except KeyboardInterrupt:
        print("\nstopped")
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
