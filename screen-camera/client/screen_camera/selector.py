"""A small, HiDPI-safe region selector.

Instead of a transparent full-screen overlay (whose behaviour differs between Windows, macOS and
X11/Wayland, and across mixed-DPI monitors), it shows a screenshot of one monitor scaled into a
normal window. The user drags a rectangle on that picture; the rectangle is mapped back to
physical screen pixels using the known scale. Only tkinter (stdlib) and Pillow are needed.

Keys: drag = select, Enter/double-click = confirm, Esc = cancel, R = re-take screenshot.
"""
from __future__ import annotations

from typing import Optional

from .capture import Grabber, Region


def select_region(monitor: int = 1, initial: Optional[Region] = None) -> Optional[Region]:
    import tkinter as tk

    from PIL import ImageTk

    grabber = Grabber()
    try:
        monitors = grabber.monitors
        if monitor < 0 or monitor >= len(monitors):
            raise ValueError(f"monitor {monitor} not found (mss sees {len(monitors) - 1} monitors; 0 = all)")
        shot, mon = grabber.grab_monitor(monitor)
    finally:
        grabber.close()

    root = tk.Tk()
    root.title("screen-camera: drag over the QR code, then press Enter")
    sw, sh = root.winfo_screenwidth(), root.winfo_screenheight()
    max_w, max_h = int(sw * 0.9), int(sh * 0.85)
    scale = min(max_w / shot.width, max_h / shot.height, 1.0)
    disp_w, disp_h = max(1, int(shot.width * scale)), max(1, int(shot.height * scale))
    shown = shot.resize((disp_w, disp_h))

    photo = ImageTk.PhotoImage(shown)
    canvas = tk.Canvas(root, width=disp_w, height=disp_h, highlightthickness=0, cursor="crosshair")
    canvas.pack()
    canvas.create_image(0, 0, anchor="nw", image=photo)
    hint = tk.Label(root, text="Drag to select · Enter = OK · Esc = cancel", anchor="w")
    hint.pack(fill="x")

    state: dict = {"start": None, "rect": None, "result": None}

    def to_physical(x0: float, y0: float, x1: float, y1: float) -> Region:
        l, r = sorted((x0, x1))
        t, b = sorted((y0, y1))
        l, t = max(0, l), max(0, t)
        r, b = min(disp_w, r), min(disp_h, b)
        return Region(
            left=mon["left"] + int(l / scale),
            top=mon["top"] + int(t / scale),
            width=max(1, int((r - l) / scale)),
            height=max(1, int((b - t) / scale)),
        )

    if initial is not None:
        ix0 = (initial.left - mon["left"]) * scale
        iy0 = (initial.top - mon["top"]) * scale
        state["rect"] = canvas.create_rectangle(
            ix0, iy0, ix0 + initial.width * scale, iy0 + initial.height * scale, outline="#00e676", width=2
        )
        state["result"] = initial

    def on_press(e):
        state["start"] = (e.x, e.y)
        if state["rect"] is not None:
            canvas.delete(state["rect"])
        state["rect"] = canvas.create_rectangle(e.x, e.y, e.x, e.y, outline="#00e676", width=2)

    def on_drag(e):
        if state["start"] is None:
            return
        x0, y0 = state["start"]
        canvas.coords(state["rect"], x0, y0, e.x, e.y)
        reg = to_physical(x0, y0, e.x, e.y)
        hint.config(text=f"{reg.width}x{reg.height} at ({reg.left},{reg.top})  ·  Enter = OK · Esc = cancel")

    def on_release(e):
        if state["start"] is None:
            return
        x0, y0 = state["start"]
        if abs(e.x - x0) > 3 and abs(e.y - y0) > 3:
            state["result"] = to_physical(x0, y0, e.x, e.y)

    def confirm(_e=None):
        if state["result"] is not None:
            root.quit()

    def cancel(_e=None):
        state["result"] = None
        root.quit()

    canvas.bind("<ButtonPress-1>", on_press)
    canvas.bind("<B1-Motion>", on_drag)
    canvas.bind("<ButtonRelease-1>", on_release)
    canvas.bind("<Double-Button-1>", confirm)
    root.bind("<Return>", confirm)
    root.bind("<Escape>", cancel)
    root.protocol("WM_DELETE_WINDOW", cancel)
    root.lift()
    root.attributes("-topmost", True)
    root.after(300, lambda: root.attributes("-topmost", False))
    root.focus_force()
    root.mainloop()
    result = state["result"]
    root.destroy()
    return result
