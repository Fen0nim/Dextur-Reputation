#!/usr/bin/env python3
"""Minimal offline-mode protocol 776 client, only for the isolated Paper QA server.

No external dependencies. Packet IDs are from Paper 26.2 build 134's protocol
registration. It accepts configuration, acknowledges teleports and keep-alives,
then stays connected while the QA plugin exercises real Player commands/events.
"""
import argparse
import hashlib
import socket
import struct
import time
import uuid
import zlib
import threading
import math


def varint(number):
    result = bytearray()
    while True:
        byte = number & 127
        number >>= 7
        result.append(byte | (128 if number else 0))
        if not number:
            return bytes(result)


def read_varint(read):
    value = 0
    for index in range(5):
        byte = read(1)[0]
        value |= (byte & 127) << (index * 7)
        if not byte & 128:
            return value
    raise ValueError("Oversized VarInt")


def string(value):
    data = value.encode()
    return varint(len(data)) + data


def run(port, username, walk=False, capture_messages=False):
    with socket.create_connection(("127.0.0.1", port), timeout=5) as connection:
        connection.settimeout(60)
        threshold = None
        send_lock=threading.Lock()
        position=[None]
        stopped=threading.Event()

        def read(size):
            result = bytearray()
            while len(result) < size:
                part = connection.recv(size - len(result))
                if not part:
                    raise EOFError()
                result.extend(part)
            return bytes(result)

        def send(packet_id, body=b""):
            packet = varint(packet_id) + body
            if threshold is not None:
                packet = varint(len(packet)) + zlib.compress(packet) if len(packet) >= threshold else b"\0" + packet
            with send_lock:connection.sendall(varint(len(packet)) + packet)

        def movement():
            angle=0.0
            while not stopped.wait(0.1):
                base=position[0]
                if base is not None and base[1]>=199:
                    angle+=0.12
                    try:send(0x1E,struct.pack(">dddB",base[0]+3*math.sin(angle),base[1],base[2]+3*(1-math.cos(angle)),0))
                    except OSError:return
        if walk:threading.Thread(target=movement,daemon=True).start()
        identity = bytearray(hashlib.md5(("OfflinePlayer:" + username).encode()).digest())
        identity[6] = (identity[6] & 15) | 48
        identity[8] = (identity[8] & 63) | 128
        send(0, varint(776) + string("127.0.0.1") + struct.pack(">H", port) + varint(2))
        send(0, string(username) + uuid.UUID(bytes=bytes(identity)).bytes)
        state = "login"
        message_packet = None
        while True:
            length = read_varint(read)
            if length > 8 * 1024 * 1024:
                raise ValueError("Oversized server frame")
            packet = read(length)
            if threshold is not None:
                from io import BytesIO
                stream = BytesIO(packet)
                uncompressed = read_varint(stream.read)
                packet = zlib.decompress(stream.read()) if uncompressed else stream.read()
            from io import BytesIO
            stream = BytesIO(packet)
            packet_id = read_varint(stream.read)
            body = stream.read()
            if state == "login":
                if packet_id == 3:
                    threshold = read_varint(BytesIO(body).read)
                elif packet_id == 2:
                    send(3)
                    state = "configuration"
                    # locale, view distance, chat mode/colors, skin, main hand, filtering/listing, particles
                    send(0, string("en_us") + b"\x02" + varint(0) + b"\x01\x7f" + varint(1) + b"\x00\x01" + varint(0))
                elif packet_id == 0:
                    raise RuntimeError("Login disconnected: " + body.decode(errors="replace"))
            elif state == "configuration":
                if packet_id == 14:
                    send(7, varint(0))
                elif packet_id == 19:
                    send(9)
                elif packet_id == 3:
                    send(3)
                    state = "play"
                elif packet_id == 4:
                    send(4, body)
                elif packet_id == 5:
                    send(5, body)
                elif packet_id == 2:
                    raise RuntimeError("Configuration disconnected")
            else:
                if capture_messages:
                    if b"UX_WIRE_MARKER" in body or b"POLISH_WIRE_MARKER" in body:
                        message_packet = packet_id
                    if packet_id == message_packet:
                        import json
                        print(json.dumps({"player": username, "packet": packet_id, "text": body.decode("utf-8", errors="replace")}, ensure_ascii=False), flush=True)
                if packet_id == 0x2C:
                    send(0x1C, body)
                elif packet_id == 0x48:
                    payload=BytesIO(body)
                    teleport_id = read_varint(payload.read)
                    coordinates=payload.read(24)
                    if len(coordinates)==24:position[0]=struct.unpack(">ddd",coordinates)
                    send(0, varint(teleport_id))
                    send(0x2C)
                elif packet_id == 0x20:
                    return


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=25566)
    parser.add_argument("--username", default="Phase2Voter")
    parser.add_argument("--walk",action="store_true",help="Send native movement for isolated Phase 5 activity tests")
    parser.add_argument("--capture-messages", action="store_true", help="Capture QA-marked system-chat packets for notification verification")
    args = parser.parse_args()
    deadline = time.monotonic() + 160
    while time.monotonic() < deadline:
        try:
            run(args.port, args.username,args.walk,args.capture_messages)
            return
        except (ConnectionRefusedError, ConnectionResetError, EOFError, socket.timeout):
            time.sleep(0.25)
    raise RuntimeError("QA player could not complete the server session")


if __name__ == "__main__":
    main()
