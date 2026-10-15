"""screen-camera desktop client.

    python -m screen_camera run --app com.tencent.mm      # push server, open WeChat on a virtual display
    python -m screen_camera select                        # just pick a region and print it
    python -m screen_camera doctor                        # check whether this phone can work
    python -m screen_camera cleanup                       # remove the CDM association + pushed jar
"""
from __future__ import annotations

import argparse
import queue
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Optional

from . import __version__
from .adb import Adb, AdbError
from .capture import Region, encode_image, fit_size
from .protocol import Connection, StreamState, encode_clear, encode_jpeg, encode_rgba

REMOTE_JAR = "/data/local/tmp/screen-camera-server.jar"
SERVER_CLASS = "io.github.screencamera.Server"
DEFAULT_MAC = "02:53:43:41:4d:01"
DEFAULT_SOCKET = "screen-camera"


def default_jar() -> Path:
    return Path(__file__).resolve().parents[2] / "server" / "build" / "screen-camera-server.jar"


def log(msg: str) -> None:
    print(f"[client] {msg}", flush=True)


# --------------------------------------------------------------------------- device-side server


class ServerProcess:
    """Runs the dex jar with app_process over `adb shell` and parses its @key=value events."""

    def __init__(self, adb: Adb, server_args: list[str]):
        self.adb = adb
        self.events: dict[str, str] = {}
        self._ready = threading.Event()
        cmd = [f"CLASSPATH={REMOTE_JAR}", "app_process", "/", SERVER_CLASS] + server_args
        self.proc = adb.popen_shell(cmd)
        self._thread = threading.Thread(target=self._pump, name="server-out", daemon=True)
        self._thread.start()

    def _pump(self) -> None:
        assert self.proc.stdout is not None
        for line in self.proc.stdout:
            line = line.rstrip("\n")
            if line.startswith("@") and "=" in line:
                k, _, v = line[1:].partition("=")
                self.events[k] = v
                if k in ("ready", "fatal"):
                    self._ready.set()
            else:
                print(line, flush=True)
        self._ready.set()

    def wait_ready(self, timeout: float = 20) -> None:
        if not self._ready.wait(timeout):
            raise RuntimeError("server did not become ready in time (see [server] lines above)")
        if "fatal" in self.events or "ready" not in self.events:
            raise RuntimeError(f"server failed: {self.events.get('fatal', 'exited early')}")

    def alive(self) -> bool:
        return self.proc.poll() is None

    def stop(self) -> None:
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(3)
            except subprocess.TimeoutExpired:
                self.proc.kill()


# --------------------------------------------------------------------------- frame pump


class FramePump(threading.Thread):
    def __init__(self, conn: Connection, region: Region, fps: float, fmt: str, quality: int):
        super().__init__(name="frame-pump", daemon=True)
        self.conn = conn
        self.region = region
        self.fps = fps
        self.fmt = fmt
        self.quality = quality
        self.paused = False
        self.stop_event = threading.Event()
        self.frames_sent = 0

    def run(self) -> None:
        from .capture import Grabber  # needs a display; create in this thread

        grabber = Grabber()
        period = 1.0 / self.fps
        try:
            while not self.stop_event.is_set() and not self.conn.closed.is_set():
                state: StreamState = self.conn.state
                if not state.active or self.paused:
                    # No app is consuming the camera: don't burn CPU. Wake on state change.
                    self.conn.state_changed.wait(0.5)
                    self.conn.state_changed.clear()
                    continue
                t0 = time.monotonic()
                img = grabber.grab(self.region)
                w, h = fit_size(img.width, img.height, state.width, state.height)
                if (w, h) != img.size:
                    img = img.resize((w, h))
                data = encode_image(img, self.fmt, self.quality)
                try:
                    if self.fmt == "jpeg":
                        self.conn.send(encode_jpeg(data))  # type: ignore[arg-type]
                    else:
                        rw, rh, raw = data  # type: ignore[misc]
                        self.conn.send(encode_rgba(rw, rh, raw))
                except OSError:
                    break
                self.frames_sent += 1
                dt = time.monotonic() - t0
                if dt < period:
                    self.stop_event.wait(period - dt)
        finally:
            grabber.close()


# --------------------------------------------------------------------------- helpers


def resolve_launcher(adb: Adb, package: str) -> Optional[str]:
    out = adb.shell(
        "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER", package, check=False,
    )
    lines = [l.strip() for l in out.splitlines() if "/" in l]
    return lines[-1] if lines else None


def launch_on_display(adb: Adb, package: str, display_id: int, force_stop: bool) -> None:
    if force_stop:
        adb.shell("am", "force-stop", package, check=False)
    component = resolve_launcher(adb, package)
    if not component:
        log(f"could not resolve a launcher activity for {package}; start it yourself on display {display_id}")
        return
    log(f"starting {component} on display {display_id}")
    out = adb.shell("am", "start", "--display", str(display_id), "-n", component, "-f", "0x10000000", check=False)
    if "Error" in out or "Exception" in out:
        log(f"am start said: {out.strip()}")


def start_scrcpy(args, serial: str, display_id: int) -> Optional[subprocess.Popen]:
    exe = args.scrcpy or shutil.which("scrcpy")
    if not exe:
        log("scrcpy not found: install it, or view/control the virtual display another way "
            f"(e.g. `scrcpy --display-id={display_id}`)")
        return None
    cmd = [exe, "-s", serial, f"--display-id={display_id}", "--no-audio",
           f"--window-title=screen-camera (display {display_id})"]
    if args.scrcpy_args:
        cmd += args.scrcpy_args.split()
    log("launching " + " ".join(cmd))
    return subprocess.Popen(cmd)


def stdin_commands(q: "queue.Queue[str]") -> None:
    for line in sys.stdin:
        q.put(line.strip().lower())
    q.put("q")


# --------------------------------------------------------------------------- commands


def cmd_run(args) -> int:
    adb = Adb(args.serial)
    serial = adb.ensure_device()
    sdk = adb.sdk_int()
    log(f"device {serial}: Android {adb.getprop('ro.build.version.release')} (API {sdk})")
    if sdk < 35 and not args.force:
        log("Android 15 (API 35)+ is required for VirtualDeviceManager virtual cameras. (--force to try anyway)")
        return 2
    svc = adb.shell("service", "check", "virtual_camera", check=False)
    if "not found" in svc and not args.force:
        log("this ROM does not run the native 'virtual_camera' service, so virtual cameras cannot be created.")
        log("  (`adb shell service check virtual_camera` -> " + svc.strip() + ")")
        return 2

    jar = Path(args.server_jar or default_jar())
    if not jar.is_file():
        log(f"server jar not found at {jar}; build it with ./build_server.sh (or pass --server-jar)")
        return 2

    region = Region.parse(args.region) if args.region else None
    if region is None:
        from .selector import select_region

        log("select the screen region to expose as the camera…")
        region = select_region(args.monitor)
        if region is None:
            log("cancelled")
            return 1
    log(f"region: {region}")

    adb.push(str(jar), REMOTE_JAR)
    adb.forward(args.port, DEFAULT_SOCKET)

    cw, ch = (int(v) for v in args.camera_size.lower().split("x"))
    dw, dh = (int(v) for v in args.display_size.lower().split("x"))
    server_args = [
        f"width={cw}", f"height={ch}", f"fps={args.camera_fps}", f"facing={args.facing}",
        f"display_width={dw}", f"display_height={dh}", f"display_dpi={args.display_dpi}",
        f"always_unlocked={'true' if args.always_unlocked else 'false'}",
        f"socket={DEFAULT_SOCKET}", f"association_mac={DEFAULT_MAC}",
        f"verbose={'true' if args.verbose else 'false'}",
    ]
    if args.app and not args.no_grant:
        server_args.append(f"grant={args.app}")

    server = ServerProcess(adb, server_args)
    scrcpy_proc = None
    conn = None
    pump = None
    try:
        server.wait_ready()
        display_id = int(server.events["display_id"])
        log(f"virtual display {display_id} is ready (virtual device {server.events.get('device_id')})")

        if not args.no_scrcpy:
            scrcpy_proc = start_scrcpy(args, serial, display_id)
        if args.app:
            time.sleep(0.5)
            launch_on_display(adb, args.app, display_id, args.force_stop)
        else:
            log(f"start the app on the virtual display, e.g.: adb shell am start --display {display_id} -n <pkg>/<activity>")

        for attempt in range(20):
            try:
                conn = Connection("127.0.0.1", args.port)
                break
            except (OSError, EOFError):
                time.sleep(0.25)
        if conn is None:
            raise RuntimeError("could not connect to the server socket through adb forward")
        conn.on_info = lambda s: log(f"server: {s}")
        conn.on_state = lambda st: log(
            f"camera {'OPEN' if st.active else 'idle'}" + (f" (consumer wants ~{st.width}x{st.height})" if st.active else "")
        )

        pump = FramePump(conn, region, args.fps, args.format, args.quality)
        pump.start()
        log("running. Open the app's QR scanner on the virtual display.")
        log("commands: s = re-select region, p = pause/resume, q = quit")

        q: "queue.Queue[str]" = queue.Queue()
        threading.Thread(target=stdin_commands, args=(q,), daemon=True).start()
        while server.alive() and not conn.closed.is_set():
            try:
                c = q.get(timeout=0.5)
            except queue.Empty:
                continue
            if c == "q":
                break
            if c == "p":
                pump.paused = not pump.paused
                if pump.paused:
                    conn.send(encode_clear())
                log("paused" if pump.paused else "resumed")
            elif c == "s":
                from .selector import select_region

                new = select_region(args.monitor, pump.region)  # Tk on the main thread
                if new:
                    pump.region = new
                    log(f"region: {new}")
        return 0
    finally:
        if pump:
            pump.stop_event.set()
        if conn:
            conn.close()
        if scrcpy_proc and scrcpy_proc.poll() is None:
            scrcpy_proc.terminate()
        server.stop()
        adb.forward_remove(args.port)
        log("stopped (the virtual device/display/cameras are torn down with the server process)")


def cmd_select(args) -> int:
    from .selector import select_region

    r = select_region(args.monitor)
    if r is None:
        return 1
    print(r)
    return 0


def cmd_doctor(args) -> int:
    adb = Adb(args.serial)
    serial = adb.ensure_device()
    sdk = adb.sdk_int()
    ok = True

    def item(name: str, good: bool, detail: str) -> None:
        nonlocal ok
        ok &= good
        print(f"  [{'OK' if good else '!!'}] {name}: {detail}")

    print(f"device {serial}: {adb.getprop('ro.product.manufacturer')} {adb.getprop('ro.product.model')}")
    item("Android version", sdk >= 35, f"{adb.getprop('ro.build.version.release')} (API {sdk}); need API 35+, 36+ recommended")
    svc = adb.shell("service", "check", "virtual_camera", check=False).strip()
    item("native virtual_camera service", "not found" not in svc and "found" in svc, svc)
    vdm = adb.shell("service", "check", "virtualdevice", check=False).strip()
    item("virtualdevice system service", "not found" not in vdm and "found" in vdm, vdm)
    perms = adb.shell("dumpsys", "package", "com.android.shell", check=False)
    granted = any("CREATE_VIRTUAL_DEVICE: granted=true" in l for l in perms.splitlines())
    item("shell holds CREATE_VIRTUAL_DEVICE", granted,
         "granted" if granted else "not listed as granted (expected via the SYSTEM_SHELL role)")
    assoc = adb.shell("cmd", "companiondevice", "list", "0", check=False).strip()
    has = DEFAULT_MAC in assoc.lower()
    print(f"  [..] CDM association for com.android.shell: {'present' if has else 'will be created on first run'}")
    if args.verbose:
        print(assoc)
    print("result:", "looks usable" if ok else "probably NOT usable (see !! items)")
    return 0 if ok else 3


def cmd_cleanup(args) -> int:
    adb = Adb(args.serial)
    adb.ensure_device()
    out = adb.shell("cmd", "companiondevice", "disassociate", "0", "com.android.shell", DEFAULT_MAC, check=False)
    print(out.strip() or "association removed (if it existed)")
    adb.shell("rm", "-f", REMOTE_JAR, check=False)
    print(f"removed {REMOTE_JAR}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="screen-camera", description="Feed a desktop screen region to an Android app's camera.")
    p.add_argument("--version", action="version", version=__version__)
    p.add_argument("-s", "--serial", help="adb device serial")
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="cmd", required=True)

    r = sub.add_parser("run", help="start the virtual camera and stream a screen region")
    r.add_argument("--app", help="package to launch on the virtual display (e.g. com.tencent.mm, com.eg.android.AlipayGphone)")
    r.add_argument("--force-stop", action="store_true", help="force-stop --app first so it opens on the virtual display")
    r.add_argument("--no-grant", action="store_true", help="don't pre-grant CAMERA to --app on the virtual device")
    r.add_argument("--region", help="x,y,w,h in screen pixels (skip the selector)")
    r.add_argument("--monitor", type=int, default=1, help="mss monitor index for the selector (1 = primary, 0 = all)")
    r.add_argument("--fps", type=float, default=15, help="desktop capture rate")
    r.add_argument("--format", choices=["jpeg", "raw"], default="jpeg")
    r.add_argument("--quality", type=int, default=85, help="JPEG quality")
    r.add_argument("--camera-size", default="1280x720", help="camera stream size advertised to apps (landscape)")
    r.add_argument("--camera-fps", type=int, default=30)
    r.add_argument("--facing", default="back,front", help="back, front or back,front")
    r.add_argument("--display-size", default="1080x1920", help="virtual display size for the app")
    r.add_argument("--display-dpi", type=int, default=420)
    r.add_argument("--always-unlocked", action="store_true", help="keep the virtual display usable while the phone is locked")
    r.add_argument("--port", type=int, default=27183, help="local TCP port for adb forward")
    r.add_argument("--server-jar", help="path to screen-camera-server.jar")
    r.add_argument("--no-scrcpy", action="store_true", help="don't launch scrcpy for the virtual display")
    r.add_argument("--scrcpy", help="path to scrcpy")
    r.add_argument("--scrcpy-args", help="extra scrcpy arguments")
    r.add_argument("--force", action="store_true", help="skip Android version / service checks")
    r.set_defaults(func=cmd_run)

    s = sub.add_parser("select", help="pick a region and print it as x,y,w,h")
    s.add_argument("--monitor", type=int, default=1)
    s.set_defaults(func=cmd_select)

    d = sub.add_parser("doctor", help="check device compatibility")
    d.set_defaults(func=cmd_doctor)

    c = sub.add_parser("cleanup", help="remove the CDM association and the pushed jar")
    c.set_defaults(func=cmd_cleanup)
    return p


def main(argv: Optional[list[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except AdbError as e:
        log(str(e))
        return 2
    except KeyboardInterrupt:
        return 130
