"""Thin wrapper around the adb command-line tool."""
from __future__ import annotations

import os
import shutil
import subprocess
from typing import Optional, Sequence


class AdbError(RuntimeError):
    pass


class Adb:
    def __init__(self, serial: Optional[str] = None, adb_path: Optional[str] = None):
        self.adb = adb_path or os.environ.get("ADB") or shutil.which("adb")
        if not self.adb:
            raise AdbError("adb not found. Install Android platform-tools or set $ADB.")
        self.serial = serial

    def _base(self) -> list[str]:
        cmd = [self.adb]
        if self.serial:
            cmd += ["-s", self.serial]
        return cmd

    def run(self, *args: str, check: bool = True, timeout: float = 30) -> str:
        proc = subprocess.run(
            self._base() + list(args), capture_output=True, text=True, timeout=timeout
        )
        out = (proc.stdout or "") + (proc.stderr or "")
        if check and proc.returncode != 0:
            raise AdbError(f"adb {' '.join(args)} failed ({proc.returncode}): {out.strip()}")
        return out

    def shell(self, *args: str, check: bool = True, timeout: float = 30) -> str:
        return self.run("shell", *args, check=check, timeout=timeout)

    def popen_shell(self, args: Sequence[str]) -> subprocess.Popen:
        return subprocess.Popen(
            self._base() + ["shell"] + list(args),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL,
            text=True,
            bufsize=1,
        )

    def ensure_device(self) -> str:
        out = self.run("devices").splitlines()[1:]
        devices = [l.split("\t")[0] for l in out if l.strip().endswith("device")]
        if self.serial:
            if self.serial not in devices:
                raise AdbError(f"device {self.serial} not connected/authorized ({devices})")
            return self.serial
        if not devices:
            raise AdbError("no authorized adb device. Enable USB debugging and accept the prompt.")
        if len(devices) > 1:
            raise AdbError(f"several devices connected, pass --serial (one of {devices})")
        self.serial = devices[0]
        return self.serial

    def getprop(self, name: str) -> str:
        return self.shell("getprop", name).strip()

    def sdk_int(self) -> int:
        try:
            return int(self.getprop("ro.build.version.sdk"))
        except ValueError:
            return 0

    def push(self, local: str, remote: str) -> None:
        self.run("push", local, remote, timeout=120)

    def forward(self, local_port: int, abstract_name: str) -> None:
        self.run("forward", f"tcp:{local_port}", f"localabstract:{abstract_name}")

    def forward_remove(self, local_port: int) -> None:
        self.run("forward", "--remove", f"tcp:{local_port}", check=False)
