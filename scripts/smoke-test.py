#!/usr/bin/env python3
"""End-to-end smoke test for MC Chat API's Unix socket protocol.

The test expects a Fabric dev server to be running with RCON enabled. It:
  1. connects to the mod's Unix socket and performs the hello/ack handshake,
  2. sends a Wmsg packet and checks that the server logged the injected text,
  3. sends `say` over RCON and checks that the socket receives the Mmsg broadcast.

It also supports `--stop-only`, used to shut the test server down from a shell trap.
"""

from __future__ import annotations

import argparse
import json
import socket
import struct
import sys
import time
import uuid
from pathlib import Path

TIMEOUT = 15.0
SERVERDATA_EXECCOMMAND = 2
SERVERDATA_AUTH = 3


def fail(message: str) -> None:
    print(f"smoke test FAILED: {message}", file=sys.stderr)
    raise SystemExit(1)


class Rcon:
    """Minimal Minecraft RCON client (no third-party dependencies)."""

    def __init__(self, host: str, port: int, password: str) -> None:
        self.socket = socket.create_connection((host, port), timeout=TIMEOUT)
        self.socket.settimeout(TIMEOUT)
        self.password = password
        self.next_id = 1

    def __enter__(self) -> "Rcon":
        return self

    def __exit__(self, *_: object) -> None:
        self.socket.close()

    def _recv_exact(self, size: int) -> bytes:
        data = bytearray()
        while len(data) < size:
            chunk = self.socket.recv(size - len(data))
            if not chunk:
                raise RuntimeError("RCON connection closed")
            data.extend(chunk)
        return bytes(data)

    def _receive(self) -> tuple[int, int, str]:
        size = struct.unpack("<i", self._recv_exact(4))[0]
        data = self._recv_exact(size)
        request_id, packet_type = struct.unpack("<ii", data[:8])
        body = data[8:-2].decode("utf-8", errors="replace")
        return request_id, packet_type, body

    def _send(self, packet_type: int, body: str) -> int:
        request_id = self.next_id
        self.next_id += 1
        payload = (
            struct.pack("<ii", request_id, packet_type)
            + body.encode("utf-8")
            + b"\x00\x00"
        )
        self.socket.sendall(struct.pack("<i", len(payload)) + payload)
        return request_id

    def login(self) -> None:
        request_id = self._send(SERVERDATA_AUTH, self.password)
        response_id, _, body = self._receive()
        if response_id == -1:
            raise RuntimeError(f"RCON authentication failed: {body}")

    def command(self, command: str) -> str:
        request_id = self._send(SERVERDATA_EXECCOMMAND, command)
        while True:
            response_id, _, body = self._receive()
            if response_id == request_id:
                return body

    def send_only(self, command: str) -> None:
        """Send a command without waiting for a reply (used for `stop`)."""
        self._send(SERVERDATA_EXECCOMMAND, command)


def read_packet(reader) -> dict:
    line = reader.readline()
    if not line:
        raise RuntimeError("socket closed before the next packet arrived")
    packet = json.loads(line)
    if not isinstance(packet, dict):
        raise RuntimeError(f"packet is not a JSON object: {line.strip()}")
    return packet


def wait_for_log(log_path: Path, token: str, timeout: float = TIMEOUT) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if token in log_path.read_text(encoding="utf-8", errors="replace"):
                return True
        except FileNotFoundError:
            pass
        time.sleep(0.25)
    return False


def stop_server(args: argparse.Namespace) -> None:
    """Best-effort shutdown used by the runner's EXIT trap."""
    try:
        with Rcon(args.rcon_host, args.rcon_port, args.rcon_password) as rcon:
            rcon.login()
            rcon.send_only("stop")
    except (OSError, RuntimeError):
        # Already stopped, or never started; the trap also kills the process.
        pass


def run_test(args: argparse.Namespace) -> None:
    inbound_token = f"smoke-in-{uuid.uuid4().hex[:8]}"
    outbound_token = f"smoke-out-{uuid.uuid4().hex[:8]}"
    log_path = Path(args.log)

    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(TIMEOUT)
        client.connect(args.socket)
        reader = client.makefile("r", encoding="utf-8")
        print(f"connected to {args.socket}")

        client.sendall(b'{"type":"hello"}\n')
        ack = read_packet(reader)
        if ack != {"type": "ack"}:
            fail(f"expected ack, got {ack}")
        print("handshake ok")

        client.sendall(
            (json.dumps({"type": "Wmsg", "msg": inbound_token}) + "\n").encode("utf-8")
        )
        if not wait_for_log(log_path, inbound_token):
            fail(f"Wmsg text {inbound_token!r} never appeared in the server log")
        print("Wmsg injected into Minecraft")

        with Rcon(args.rcon_host, args.rcon_port, args.rcon_password) as rcon:
            rcon.login()
            rcon.command(f"say {outbound_token}")

            deadline = time.monotonic() + TIMEOUT
            while time.monotonic() < deadline:
                packet = read_packet(reader)
                if packet.get("type") == "Mmsg" and outbound_token in str(
                    packet.get("msg", "")
                ):
                    print("Mmsg broadcast received")
                    break
            else:
                fail(f"no Mmsg containing {outbound_token!r} was broadcast")

            rcon.send_only("stop")

    print("smoke test PASSED")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--socket", help="path to the mod's Unix socket")
    parser.add_argument("--log", help="server log file to inspect")
    parser.add_argument("--rcon-host", default="127.0.0.1")
    parser.add_argument("--rcon-port", type=int, default=25575)
    parser.add_argument("--rcon-password", default="smoke")
    parser.add_argument("--stop-only", action="store_true", help="stop the test server")
    args = parser.parse_args()

    if args.stop_only:
        stop_server(args)
        return
    if not args.socket or not args.log:
        parser.error("--socket and --log are required unless --stop-only is used")

    run_test(args)


if __name__ == "__main__":
    main()
