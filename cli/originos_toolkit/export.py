"""Export tweaks as a standalone ADB script for people who prefer a PC."""

from __future__ import annotations

import shlex
from typing import List, Sequence

from .catalog import Tweak
from .ops import ShellCall, probe_for, shell_calls

HEADER = """#!/usr/bin/env sh
# ---------------------------------------------------------------------------
# OriginOS Toolkit — generated ADB script
#
# Generated from catalog v{version} on {date}.
# Everything below runs as the `shell` user over ADB. No root, no permanence.
#
# Read the SNAPSHOT section before you run the APPLY section: it prints the
# values that are currently set, which is what you would type back in the
# REVERT notes if you ever want to undo a change by hand.
#
# Usage:  sh {name}
# ---------------------------------------------------------------------------
set -eu
"""


def _adb_prefix(serial: str | None) -> str:
    return f"adb -s {serial}" if serial else "adb"


def _sh_call(call: ShellCall, serial: str | None) -> str:
    prefix = _adb_prefix(serial)
    if call.raw is not None:
        return f"{prefix} shell {shlex.quote(call.raw)}"
    return f"{prefix} shell " + " ".join(shlex.quote(part) for part in call.argv)


def _ps_call(call: ShellCall, serial: str | None) -> str:
    args = f" -s {serial}" if serial else ""
    if call.raw is not None:
        escaped = call.raw.replace('"', '`"')
        return f'& adb{args} shell "{escaped}"'
    joined = " ".join(f'"{part}"' for part in call.argv)
    return f"& adb{args} shell {joined}"


def render_script(
    tweaks: Sequence[Tweak],
    serial: str | None = None,
    fmt: str = "sh",
    version: str = "1",
    date: str = "",
    name: str = "originos-toolkit.sh",
    includes_snapshot: bool = True,
) -> str:
    """Render tweaks as a runnable script in ``sh`` or ``ps1`` format."""

    prefix_fn = _sh_call if fmt == "sh" else _ps_call
    lines: List[str] = []

    if fmt == "sh":
        lines.append(HEADER.format(version=version, date=date, name=name).rstrip())
    else:
        lines.append(
            "# OriginOS Toolkit — generated ADB script (PowerShell)\r\n"
            f"# Generated from catalog v{version} on {date}.\r\n"
            "$ErrorActionPreference = 'Continue'\r\n"
        )

    newline = "\n" if fmt == "sh" else "\r\n"

    if includes_snapshot:
        lines.append(newline + "# --- SNAPSHOT (read-only) ---")
        for tweak in tweaks:
            for action in tweak.actions:
                call = probe_for(action)
                if call is None:
                    continue
                lines.append(f"# {tweak.id}: {action.target}")
                lines.append(prefix_fn(call, serial))

    lines.append(newline + "# --- APPLY ---")
    for tweak in tweaks:
        lines.append(f"# {tweak.id} — {tweak.name}")
        for action in tweak.actions:
            calls = shell_calls(action, previous="460")
            if not calls:
                lines.append(f"#   (skipped {action.target}: needs a value from the device)")
                continue
            for call in calls:
                lines.append(prefix_fn(call, serial))

    lines.append(newline + "# --- REVERT NOTES ---")
    for tweak in tweaks:
        if tweak.revert:
            lines.append(f"# {tweak.id}: explicit revert available")
            for action in tweak.revert:
                for call in shell_calls(action, previous=None):
                    lines.append("#   " + prefix_fn(call, serial))
        elif tweak.is_reversible:
            lines.append(
                f"# {tweak.id}: revert by restoring the SNAPSHOT values printed above,"
                " or run `originos-toolkit revert --all` while the journal is still present."
            )
        else:
            lines.append(f"# {tweak.id}: one-shot action, nothing to revert.")

    body = newline.join(lines)
    return body + newline
