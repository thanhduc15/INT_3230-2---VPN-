#!/usr/bin/env python3
"""Attach to a lab TUN. Stdout is binary: a ready byte followed by packet frames."""
import fcntl
import os
import re
import struct
import sys
import threading

TUNSETIFF = 0x400454CA  # Linux x86_64 / arm64
IFF_TUN = 0x0001
IFF_NO_PI = 0x1000
MTU = 1400


def to_java(fd):
    try:
        while True:
            packet = os.read(fd, 65535)
            if not packet:
                raise EOFError("TUN closed")
            if packet[0] >> 4 != 4:
                continue  # This tunnel carries IPv4 only.
            if len(packet) > MTU:
                raise ValueError("Set TUN MTU to 1400")
            sys.stdout.buffer.write(struct.pack("!H", len(packet)) + packet)
            sys.stdout.buffer.flush()
    except Exception as exc:
        print(f"TUN read failed: {exc}", file=sys.stderr, flush=True)
        os._exit(1)  # Unblock the peer even if the main thread is waiting on stdin.


def main(name):
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,15}", name):
        raise ValueError("Invalid TUN name")
    with open("/dev/net/tun", "r+b", buffering=0) as tun:
        request = struct.pack("16sH", name.encode("ascii"), IFF_TUN | IFF_NO_PI)
        fcntl.ioctl(tun.fileno(), TUNSETIFF, request)
        sys.stdout.buffer.write(b"R")
        sys.stdout.buffer.flush()
        threading.Thread(target=to_java, args=(tun.fileno(),), daemon=True).start()
        while True:
            header = sys.stdin.buffer.read(2)
            if not header:
                return
            length, = struct.unpack("!H", header)
            if not 20 <= length <= MTU:
                raise ValueError("Invalid packet length from Java")
            packet = sys.stdin.buffer.read(length)
            if len(packet) != length:
                raise EOFError("Incomplete packet from Java")
            if os.write(tun.fileno(), packet) != length:
                raise OSError("Short TUN write")


if __name__ == "__main__":
    main(sys.argv[1])
