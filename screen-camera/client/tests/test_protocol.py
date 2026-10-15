"""Protocol tests against an in-process fake of the Android FrameServer (no device needed)."""
import io
import socket
import struct
import threading
import time

from PIL import Image

from screen_camera import protocol as P
from screen_camera.capture import Region, encode_image, fit_size


class FakeServer(threading.Thread):
    """Mirrors FrameServer.java's parsing and replies."""

    def __init__(self):
        super().__init__(daemon=True)
        self.lsock = socket.socket()
        self.lsock.bind(("127.0.0.1", 0))
        self.lsock.listen(1)
        self.port = self.lsock.getsockname()[1]
        self.frames = []
        self.done = threading.Event()

    def run(self):
        s, _ = self.lsock.accept()
        hs = P.recv_exact(s, 5)
        assert hs == P.MAGIC + bytes([P.VERSION])
        s.sendall(P.MAGIC + bytes([P.VERSION]))
        s.sendall(P.encode_message(P.S_INFO, b"camera=1280x720@30"))
        s.sendall(P.encode_message(P.S_STREAM_STATE, struct.pack(">BHH", 1, 720, 1280)))
        try:
            while True:
                t, payload = P.read_message(s)
                if t == P.C_FRAME_JPEG:
                    self.frames.append(("jpeg", Image.open(io.BytesIO(payload)).size))
                elif t == P.C_FRAME_RGBA:
                    w, h = struct.unpack(">HH", payload[:4])
                    assert len(payload) == 4 + w * h * 4
                    self.frames.append(("rgba", (w, h)))
                elif t == P.C_CLEAR:
                    self.frames.append(("clear", None))
        except EOFError:
            pass
        finally:
            self.done.set()


def test_roundtrip():
    srv = FakeServer()
    srv.start()
    conn = P.Connection("127.0.0.1", srv.port)
    infos = []
    conn.on_info = infos.append
    deadline = time.time() + 2
    while not conn.state.active and time.time() < deadline:
        time.sleep(0.01)
    assert conn.state == P.StreamState(True, 720, 1280)

    img = Image.new("RGB", (400, 300), (255, 0, 0))
    w, h = fit_size(img.width, img.height, conn.state.width, conn.state.height)
    assert (w, h) == (400, 300)  # never upscales
    conn.send(P.encode_jpeg(encode_image(img, "jpeg")))
    rw, rh, raw = encode_image(img.resize((40, 30)), "raw")
    conn.send(P.encode_rgba(rw, rh, raw))
    conn.send(P.encode_clear())
    time.sleep(0.2)
    conn.close()
    srv.done.wait(2)
    assert srv.frames == [("jpeg", (400, 300)), ("rgba", (40, 30)), ("clear", None)]


def test_fit_and_region():
    assert fit_size(2000, 1000, 720, 1280) == (720, 360)
    assert fit_size(100, 100, 720, 1280) == (100, 100)
    assert str(Region.parse("10,20,300,400")) == "10,20,300,400"
    assert Region.parse("300x400+10+20") == Region(10, 20, 300, 400)
