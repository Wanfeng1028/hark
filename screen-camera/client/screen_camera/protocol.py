"""Wire protocol shared with the Android server (see server/.../Protocol.java).

All integers are big-endian.

client -> server
    handshake: b"SCAM" + u8 version
    message:   u8 type | u32 length | payload
        1 FRAME_JPEG   payload = JPEG bytes
        2 FRAME_RGBA   payload = u16 width | u16 height | width*height*4 RGBA bytes
        3 CLEAR        payload = b""

server -> client
    handshake: b"SCAM" + u8 version
    message:   u8 type | u32 length | payload
        1 STREAM_STATE payload = u8 active | u16 width | u16 height
        2 INFO         payload = UTF-8 "key=value"
"""
from __future__ import annotations

import socket
import struct
import threading
from dataclasses import dataclass
from typing import Callable, Optional

MAGIC = b"SCAM"
VERSION = 1

C_FRAME_JPEG = 1
C_FRAME_RGBA = 2
C_CLEAR = 3

S_STREAM_STATE = 1
S_INFO = 2

MAX_PAYLOAD = 64 * 1024 * 1024
_HDR = struct.Struct(">BI")


@dataclass(frozen=True)
class StreamState:
    active: bool
    width: int
    height: int


def encode_message(msg_type: int, payload: bytes) -> bytes:
    if len(payload) > MAX_PAYLOAD:
        raise ValueError("payload too large")
    return _HDR.pack(msg_type, len(payload)) + payload


def encode_jpeg(jpeg: bytes) -> bytes:
    return encode_message(C_FRAME_JPEG, jpeg)


def encode_rgba(width: int, height: int, rgba: bytes) -> bytes:
    if not (0 < width < 65536 and 0 < height < 65536):
        raise ValueError("dimensions out of range")
    if len(rgba) != width * height * 4:
        raise ValueError("RGBA buffer size mismatch")
    return encode_message(C_FRAME_RGBA, struct.pack(">HH", width, height) + rgba)


def encode_clear() -> bytes:
    return encode_message(C_CLEAR, b"")


def recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("socket closed")
        buf.extend(chunk)
    return bytes(buf)


def read_message(sock: socket.socket) -> tuple[int, bytes]:
    msg_type, length = _HDR.unpack(recv_exact(sock, _HDR.size))
    if length > MAX_PAYLOAD:
        raise IOError(f"bad payload length {length}")
    return msg_type, recv_exact(sock, length) if length else b""


def decode_state(payload: bytes) -> StreamState:
    active, w, h = struct.unpack(">BHH", payload[:5])
    return StreamState(bool(active), w, h)


class Connection:
    """Client side of the frame socket. Thread-safe send; a reader thread tracks stream state."""

    def __init__(self, host: str, port: int, timeout: float = 5.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self._send_lock = threading.Lock()
        self.state = StreamState(False, 0, 0)
        self.state_changed = threading.Event()
        self.closed = threading.Event()
        self.on_info: Optional[Callable[[str], None]] = None
        self.on_state: Optional[Callable[[StreamState], None]] = None

        self.sock.sendall(MAGIC + bytes([VERSION]))
        magic = recv_exact(self.sock, 5)
        if magic[:4] != MAGIC or magic[4] != VERSION:
            # With `adb forward`, a connect succeeds even when nothing listens on the device
            # side; the forward then closes immediately (EOFError above) or returns junk.
            raise IOError("server handshake failed")
        self.sock.settimeout(None)
        self._reader = threading.Thread(target=self._read_loop, name="sc-reader", daemon=True)
        self._reader.start()

    def _read_loop(self) -> None:
        try:
            while True:
                msg_type, payload = read_message(self.sock)
                if msg_type == S_STREAM_STATE:
                    self.state = decode_state(payload)
                    self.state_changed.set()
                    if self.on_state:
                        self.on_state(self.state)
                elif msg_type == S_INFO and self.on_info:
                    self.on_info(payload.decode("utf-8", "replace"))
        except (EOFError, OSError):
            pass
        finally:
            self.closed.set()
            self.state_changed.set()

    def send(self, data: bytes) -> None:
        with self._send_lock:
            self.sock.sendall(data)

    def close(self) -> None:
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()
        self.closed.set()
