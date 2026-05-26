"""Shared RCON client for Aurelium CI tests."""
import socket
import struct
import re


class RCONClient:
 """Minimal RCON protocol client."""

 def __init__(self, host: str = '127.0.0.1', port: int = 25575, password: str = 'testpass'):
 self.host = host
 self.port = port
 self.password = password
 self.sock = None
 self._req_id = 0

 def connect(self):
 """Connect and authenticate to the RCON server."""
 self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
 self.sock.settimeout(10)
 self.sock.connect((self.host, self.port))
 self._send(3, self.password)
 self._recv()

 def send(self, command: str) -> str:
 """Send a command and return the response."""
 self._req_id += 1
 self._send(2, command)
 _, resp = self._recv()
 return resp

 def close(self):
 if self.sock:
 self.sock.close()
 self.sock = None

 def _send(self, pkt_type: int, data: str):
 packet = struct.pack('<ii', pkt_type, pkt_type) + data.encode('utf-8') + b'\x00\x00'
 self.sock.sendall(struct.pack('<i', len(packet)) + packet)

 def _recv(self):
 length = struct.unpack('<i', self._recv_all(4))[0]
 data = self._recv_all(length)
 req_id = struct.unpack('<i', data[:4])[0]
 resp = data[8:-2].decode('utf-8', errors='replace')
 return req_id, resp

 def _recv_all(self, n: int) -> bytes:
 data = b''
 while len(data) < n:
 chunk = self.sock.recv(n - len(data))
 if not chunk:
 raise ConnectionError(f"Connection closed: got {len(data)}/{n} bytes")
 data += chunk
 return data


def strip_color(text: str) -> str:
 """Remove Minecraft color codes from text."""
 return re.sub(r'\u00a7[0-9a-fk-or]', '', text)


def rcon(host: str, port: int, password: str, command: str) -> str:
 """One-shot RCON command helper."""
 client = RCONClient(host, port, password)
 try:
 client.connect()
 return client.send(command)
 finally:
 client.close()
