"""Desktop screen-region capture (mss) and frame encoding (Pillow)."""
from __future__ import annotations

import io
from dataclasses import dataclass

from PIL import Image


@dataclass(frozen=True)
class Region:
    """A rectangle in mss's global (physical-pixel) coordinate space."""

    left: int
    top: int
    width: int
    height: int

    @classmethod
    def parse(cls, text: str) -> "Region":
        """Parse "x,y,w,h" (or "WxH+X+Y" geometry)."""
        text = text.strip()
        if "x" in text and "+" in text:
            size, _, rest = text.partition("+")
            w, h = size.split("x")
            x, y = rest.split("+")
            vals = [int(x), int(y), int(w), int(h)]
        else:
            vals = [int(v) for v in text.replace(" ", "").split(",")]
        if len(vals) != 4 or vals[2] <= 0 or vals[3] <= 0:
            raise ValueError("region must be x,y,w,h with positive w,h")
        return cls(*vals)

    def as_mss(self) -> dict:
        return {"left": self.left, "top": self.top, "width": self.width, "height": self.height}

    def __str__(self) -> str:
        return f"{self.left},{self.top},{self.width},{self.height}"


def fit_size(src_w: int, src_h: int, max_w: int, max_h: int) -> tuple[int, int]:
    """Largest size <= (max_w, max_h) with the source aspect ratio; never upscales."""
    if max_w <= 0 or max_h <= 0:
        return src_w, src_h
    scale = min(max_w / src_w, max_h / src_h, 1.0)
    return max(1, round(src_w * scale)), max(1, round(src_h * scale))


def encode_image(img: Image.Image, fmt: str, quality: int = 85) -> bytes | tuple[int, int, bytes]:
    if fmt == "jpeg":
        buf = io.BytesIO()
        img.convert("RGB").save(buf, format="JPEG", quality=quality, optimize=False)
        return buf.getvalue()
    if fmt == "raw":
        rgba = img.convert("RGBA")
        return rgba.width, rgba.height, rgba.tobytes("raw", "RGBA")
    raise ValueError(f"unknown format {fmt}")


class Grabber:
    """Grabs a region with mss. Create it in the thread that uses it (mss handles are per-thread)."""

    def __init__(self) -> None:
        import mss  # imported lazily so tests/--help work without a display

        self._sct = mss.mss()

    @property
    def monitors(self) -> list[dict]:
        return self._sct.monitors

    def grab(self, region: Region) -> Image.Image:
        shot = self._sct.grab(region.as_mss())
        return Image.frombytes("RGB", shot.size, shot.rgb)

    def grab_monitor(self, index: int) -> tuple[Image.Image, dict]:
        mon = self._sct.monitors[index]
        shot = self._sct.grab(mon)
        return Image.frombytes("RGB", shot.size, shot.rgb), mon

    def close(self) -> None:
        self._sct.close()
