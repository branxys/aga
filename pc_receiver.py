"""
PadRelay PC receiver
--------------------
Listens for UDP packets from the PadRelay Android app and feeds them into virtual
Xbox 360 controllers (via ViGEmBus). Supports up to 4 controllers at once: each player
slot sent by the phone gets its own virtual controller. Also answers the app's discovery
broadcast so the phone can find this PC automatically.

Setup (Windows):
    pip install vgamepad
    (vgamepad installs the ViGEmBus driver; accept the prompt)

Run:
    python pc_receiver.py [port]
"""
import socket
import struct
import sys
import time

import vgamepad as vg

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9876
MAX_PADS = 4
SLOT_TIMEOUT = 5.0  # seconds without packets before a virtual controller is unplugged

# v2 packet: magic 0xC8, slot, buttons, LT, RT, LX, LY, RX, RY
FMT_V2 = "<BBHBBhhhh"
SIZE_V2 = struct.calcsize(FMT_V2)
# v1 packet (older app versions, single controller): magic 0xC7, buttons, LT, RT, LX, LY, RX, RY
FMT_V1 = "<BHBBhhhh"
SIZE_V1 = struct.calcsize(FMT_V1)

DISCOVERY_REQUEST = b"PADRELAY?"
DISCOVERY_REPLY = b"PADRELAY!"


def local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))  # no packet is actually sent
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return "unknown"


def neutral(pad):
    r = pad.report
    r.wButtons = 0
    r.bLeftTrigger = 0
    r.bRightTrigger = 0
    r.sThumbLX = r.sThumbLY = r.sThumbRX = r.sThumbRY = 0
    pad.update()


def apply(pad, buttons, lt, rt, lx, ly, rx, ry):
    r = pad.report
    r.wButtons = buttons
    r.bLeftTrigger = lt
    r.bRightTrigger = rt
    r.sThumbLX = lx
    r.sThumbLY = ly
    r.sThumbRX = rx
    r.sThumbRY = ry
    pad.update()


def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", PORT))
    sock.settimeout(0.5)

    pads = {}      # slot -> virtual gamepad
    last_rx = {}   # slot -> time of last packet

    print(f"Listening on UDP {local_ip()}:{PORT}")
    print("The phone app can find this PC automatically (or enter the IP manually).")
    print(f"Up to {MAX_PADS} controllers are supported. Press Ctrl+C to quit.\n")

    try:
        while True:
            now = time.time()

            # Unplug virtual controllers whose phone-side controller went away
            for slot in list(pads):
                if now - last_rx[slot] > SLOT_TIMEOUT:
                    neutral(pads[slot])
                    del pads[slot]       # destroying the object unplugs the virtual controller
                    del last_rx[slot]
                    print(f"Player {slot + 1} disconnected.")

            try:
                data, addr = sock.recvfrom(64)
            except socket.timeout:
                continue
            except ConnectionResetError:
                # Windows raises this when a previous reply hit a closed port; ignore
                continue

            # Discovery request from the phone
            if data.startswith(DISCOVERY_REQUEST):
                try:
                    sock.sendto(DISCOVERY_REPLY + socket.gethostname().encode(), addr)
                    print(f"Discovery request from {addr[0]} - replied.")
                except OSError:
                    pass
                continue

            if len(data) == SIZE_V2 and data[0] == 0xC8:
                _, slot, buttons, lt, rt, lx, ly, rx, ry = struct.unpack(FMT_V2, data)
            elif len(data) == SIZE_V1 and data[0] == 0xC7:
                _, buttons, lt, rt, lx, ly, rx, ry = struct.unpack(FMT_V1, data)
                slot = 0
            else:
                continue

            if slot >= MAX_PADS:
                continue

            if slot not in pads:
                pads[slot] = vg.VX360Gamepad()
                print(f"Player {slot + 1} connected (from {addr[0]}).")

            apply(pads[slot], buttons, lt, rt, lx, ly, rx, ry)
            last_rx[slot] = time.time()
    except KeyboardInterrupt:
        pass
    finally:
        for pad in pads.values():
            neutral(pad)
        pads.clear()
        sock.close()


if __name__ == "__main__":
    main()
