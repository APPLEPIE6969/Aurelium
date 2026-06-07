#!/usr/bin/env python3
"""RCON client for Aurelium CI tests.

This module provides a simple RCON client implementation for testing
Minecraft server plugins via remote console.
"""

import socket
import struct
import sys
from typing import Optional, Tuple


class RconClient:
    """Simple RCON client for Minecraft servers."""

    def __init__(self, host: str = '127.0.0.1', port: int = 25575, password: str = 'test'):
        self.host = host
        self.port = port
        self.password = password
        self.sock: Optional[socket.socket] = None
        self.request_id = 1

    def connect(self) -> bool:
        """Establish connection to RCON server."""
        try:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.sock.settimeout(10)
            self.sock.connect((self.host, self.port))
            return self._login()
        except Exception as e:
            print(f"Failed to connect to RCON: {e}", file=sys.stderr)
            return False

    def _login(self) -> bool:
        """Send login packet and verify authentication."""
        if not self.sock:
            return False
        try:
            self._send_packet(3, self.password)
            response = self._read_packet()
            return response is not None and response[0] == 2 and response[1] == 1
        except Exception as e:
            print(f"RCON login failed: {e}", file=sys.stderr)
            return False

    def _send_packet(self, packet_type: int, payload: str) -> None:
        """Send a packet to the RCON server."""
        if not self.sock:
            return
        payload_bytes = payload.encode('utf-8') + b'\x00'
        data = struct.pack('<ii', self.request_id, packet_type) + payload_bytes
        self.request_id += 1
        packet = struct.pack('<i', len(data)) + data
        self.sock.sendall(packet)

    def _read_packet(self) -> Optional[Tuple[int, int, str]]:
        """Read a packet from the RCON server."""
        if not self.sock:
            return None
        try:
            raw = self._recv_exact(4)
            if not raw:
                return None
            length = struct.unpack('<i', raw[:4])[0]
            if length < 8 or length > 4096:
                return None
            body = self._recv_exact(length)
            if not body or len(body) < 8:
                return None
            req_id = struct.unpack('<i', body[:4])[0]
            pkt_type = struct.unpack('<i', body[4:8])[0]
            payload = body[8:].rstrip(b'\x00').decode('utf-8', errors='replace')
            return (req_id, pkt_type, payload)
        except Exception:
            return None

    def _recv_exact(self, n: int) -> Optional[bytes]:
        """Receive exactly n bytes from socket."""
        if not self.sock:
            return None
        data = b''
        while len(data) < n:
            chunk = self.sock.recv(n - len(data))
            if not chunk:
                return None
            data += chunk
        return data

    def send_command(self, command: str, timeout: float = 10.0) -> Optional[str]:
        """Send a command and return the response."""
        if not self.sock:
            return None
        try:
            self.sock.settimeout(timeout)
            self._send_packet(2, command)
            response = self._read_packet()
            if response:
                return response[2]
            return None
        except Exception as e:
            print(f"RCON command failed: {e}", file=sys.stderr)
            return None

    def close(self) -> None:
        """Close the RCON connection."""
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            finally:
                self.sock = None

    def __enter__(self) -> 'RconClient':
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.close()


def main() -> int:
    """Main entry point for standalone testing."""
    client = RconClient()
    if not client.connect():
        print("Failed to connect to RCON server", file=sys.stderr)
        return 1
    try:
        response = client.send_command('status')
        print(f"Server status: {response}")
        return 0
    finally:
        client.close()


if __name__ == '__main__':
    sys.exit(main())