"""Thin, injectable wrapper around the ``adb`` executable.

Everything the toolkit does to a device funnels through :class:`AdbRunner`, which
makes the whole engine testable without a phone attached: tests substitute a
``Runner`` that records commands and returns canned output.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Sequence

DEFAULT_TIMEOUT = 30


@dataclass(frozen=True)
class CommandResult:
    """Outcome of one shell invocation."""

    argv: Sequence[str]
    stdout: str = ""
    stderr: str = ""
    code: int = 0

    @property
    def ok(self) -> bool:
        return self.code == 0

    @property
    def output(self) -> str:
        return (self.stdout or self.stderr).strip()

    def __str__(self) -> str:  # pragma: no cover - debugging aid
        return " ".join(self.argv)


class AdbError(RuntimeError):
    """Raised when adb itself cannot be found or returns a hard failure."""


#: Places ``adb`` likes to live, beyond ``PATH``.
_COMMON_ADB_PATHS = (
    "~/Android/Sdk/platform-tools/adb",
    "~/Library/Android/sdk/platform-tools/adb",
    "~/AppData/Local/Android/Sdk/platform-tools/adb.exe",
    "/usr/lib/android-sdk/platform-tools/adb",
    "/usr/local/share/android-sdk/platform-tools/adb",
    "/opt/homebrew/bin/adb",
)


def find_adb(explicit: Optional[str] = None) -> Optional[str]:
    """Locate ``adb``.

    Order: explicit argument, ``ORIGINOS_TOOLKIT_ADB``, ``ADB``, ``PATH``,
    ``ANDROID_HOME``/``ANDROID_SDK_ROOT``, then a list of well-known install
    locations. Returns ``None`` when nothing is found.
    """

    if explicit:
        return explicit

    for env in ("ORIGINOS_TOOLKIT_ADB", "ADB"):
        candidate = os.environ.get(env)
        if candidate:
            return candidate

    on_path = shutil.which("adb")
    if on_path:
        return on_path

    for root_env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(root_env)
        if not root:
            continue
        for name in ("adb", "adb.exe"):
            candidate = Path(root) / "platform-tools" / name
            if candidate.exists():
                return str(candidate)

    for raw in _COMMON_ADB_PATHS:
        candidate = Path(os.path.expanduser(raw))
        if candidate.exists():
            return str(candidate)

    return None


class AdbRunner:
    """Runs ``adb shell`` commands against one device."""

    def __init__(
        self,
        adb_path: Optional[str] = None,
        serial: Optional[str] = None,
        timeout: int = DEFAULT_TIMEOUT,
    ) -> None:
        resolved = find_adb(adb_path)
        if not resolved:
            raise AdbError(
                "adb was not found. Install Android Platform Tools and make sure "
                "`adb` is on your PATH, or set ORIGINOS_TOOLKIT_ADB to its full path."
            )
        self.adb = resolved
        self.serial = serial
        self.timeout = timeout

    # -- plumbing ---------------------------------------------------------

    def base(self) -> List[str]:
        cmd = [self.adb]
        if self.serial:
            cmd += ["-s", self.serial]
        return cmd

    def _run(self, args: Sequence[str]) -> CommandResult:
        try:
            completed = subprocess.run(
                list(args),
                capture_output=True,
                text=True,
                timeout=self.timeout,
                check=False,
            )
        except FileNotFoundError as exc:  # pragma: no cover - environment specific
            raise AdbError(f"failed to execute {args[0]!r}: {exc}") from exc
        except subprocess.TimeoutExpired as exc:
            return CommandResult(argv=args, stderr=f"timed out after {self.timeout}s", code=124)

        return CommandResult(
            argv=args,
            stdout=completed.stdout or "",
            stderr=completed.stderr or "",
            code=completed.returncode,
        )

    # -- public API -------------------------------------------------------

    def shell(self, argv: Sequence[str]) -> CommandResult:
        """Run one shell command passed as an argv vector (preferred)."""

        return self._run([*self.base(), "shell", *argv])

    def raw_shell(self, command: str) -> CommandResult:
        """Run a raw shell string. Used by the ``shell`` op."""

        return self._run([*self.base(), "shell", command])

    def devices(self) -> List[str]:
        """Return the serials of devices currently in ``device`` state."""

        result = self._run([self.adb, "devices"])
        serials: List[str] = []
        for line in result.stdout.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                serials.append(parts[0])
        return serials


class RecordingRunner:
    """A :class:`AdbRunner` look-alike that records instead of executing.

    Used by ``--dry-run`` and by the unit tests. ``answers`` maps a command
    prefix to the output it should pretend to return.
    """

    def __init__(self, answers: Optional[dict] = None, fail_on: Optional[set] = None) -> None:
        self.calls: List[str] = []
        self.answers = answers or {}
        self.fail_on = fail_on or set()

    def _record(self, argv: Sequence[str]) -> CommandResult:
        rendered = " ".join(argv)
        self.calls.append(rendered)
        for prefix, output in self.answers.items():
            if rendered.startswith(prefix):
                return CommandResult(argv=argv, stdout=output, code=0)
        if any(token in rendered for token in self.fail_on):
            return CommandResult(argv=argv, stderr="dry-run failure", code=1)
        return CommandResult(argv=argv, stdout="", code=0)

    def shell(self, argv: Sequence[str]) -> CommandResult:
        return self._record([*argv])

    def raw_shell(self, command: str) -> CommandResult:
        return self._record([command])

    def devices(self) -> List[str]:
        self.calls.append("devices")
        return ["emulator-originos"]
