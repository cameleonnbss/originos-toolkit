"""Translation between catalog ops, shell commands and their inverses.

This module is the safety-critical heart of the toolkit. Two invariants are
enforced here and covered by tests:

1. **Every mutating op has a documented inverse.** If we cannot derive an
   inverse, the catalog entry must say so and is refused by :func:`lint`.
2. **Nothing is applied before its previous state is read.** The engine calls
   :func:`probe_for` first and journals the result, so reverting restores the
   exact value that was there before — including "this key did not exist".
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Sequence, Tuple

from .catalog import Action

#: The AOSP user id we operate on. `--user 0` is the owner, i.e. you.
DEFAULT_USER = "0"


@dataclass(frozen=True)
class ShellCall:
    """One command to run on the device."""

    argv: Tuple[str, ...] = ()
    raw: Optional[str] = None

    def render(self) -> str:
        if self.raw is not None:
            return self.raw
        return " ".join(self.argv)

    def is_empty(self) -> bool:
        return not self.argv and not self.raw


def _call(argv: Sequence[str]) -> ShellCall:
    return ShellCall(argv=tuple(str(a) for a in argv))


# ---------------------------------------------------------------------------
# apply side
# ---------------------------------------------------------------------------


def shell_calls(action: Action, previous: Optional[str] = None) -> List[ShellCall]:
    """Translate one action into the shell commands that apply it.

    ``previous`` is required for ops that compute a new value from the old one
    (currently only ``wm_density_delta``); when it is missing the op yields no
    command and the engine reports it as skipped.
    """

    op = action.op

    if op == "settings_get":
        return [_call(["settings", "get", action.get("ns"), action.get("key")])]

    if op == "settings_put":
        return [
            _call(
                [
                    "settings",
                    "put",
                    action.get("ns"),
                    action.get("key"),
                    action.get("value"),
                ]
            )
        ]

    if op == "settings_delete":
        return [_call(["settings", "delete", action.get("ns"), action.get("key")])]

    if op == "device_config_get":
        return [_call(["device_config", "get", action.get("ns"), action.get("key")])]

    if op == "device_config_put":
        return [
            _call(
                [
                    "device_config",
                    "put",
                    action.get("ns"),
                    action.get("key"),
                    action.get("value"),
                ]
            )
        ]

    if op == "device_config_delete":
        return [_call(["device_config", "delete", action.get("ns"), action.get("key")])]

    if op == "pm_disable":
        return [_call(["pm", "disable-user", "--user", DEFAULT_USER, action.get("pkg")])]

    if op == "pm_enable":
        return [_call(["pm", "enable", "--user", DEFAULT_USER, action.get("pkg")])]

    if op == "pm_list":
        return [_call(["pm", "list", "packages", *action.get("args", [])])]

    if op == "overlay_enable":
        return [_call(["cmd", "overlay", "enable", "--user", DEFAULT_USER, action.get("pkg")])]

    if op == "overlay_disable":
        return [_call(["cmd", "overlay", "disable", "--user", DEFAULT_USER, action.get("pkg")])]

    if op == "overlay_list":
        return [_call(["cmd", "overlay", "list", "--user", DEFAULT_USER])]

    if op == "cmd":
        return [_call(["cmd", *action.get("args", [])])]

    if op == "svc":
        verb = "enable" if action.get("enable") else "disable"
        return [_call(["svc", action.get("service"), verb])]

    if op == "shell":
        return [ShellCall(raw=str(action.get("command", "")))]

    if op == "wm_density_delta":
        percent = action.get("percent")
        if previous is None or percent is None:
            return []
        current = int(previous)
        new_value = max(72, round(current * (100 + int(percent)) / 100))
        return [_call(["wm", "density", str(new_value)])]

    if op == "wm_density_set":
        return [_call(["wm", "density", str(action.get("value"))])]

    if op == "wm_density_reset":
        return [_call(["wm", "density", "reset"])]

    raise ValueError(f"no translator for op {op!r}")


# ---------------------------------------------------------------------------
# snapshot side
# ---------------------------------------------------------------------------


def probe_for(action: Action) -> Optional[ShellCall]:
    """The read that must happen *before* applying ``action``, if any."""

    op = action.op

    if op in ("settings_put", "settings_delete"):
        return _call(["settings", "get", action.get("ns"), action.get("key")])

    if op in ("device_config_put", "device_config_delete"):
        return _call(["device_config", "get", action.get("ns"), action.get("key")])

    if op == "wm_density_delta":
        return _call(["wm", "density"])

    if op == "pm_disable":
        return _call(["pm", "list", "packages", "-d", action.get("pkg")])

    if op in ("overlay_enable", "overlay_disable"):
        return _call(["cmd", "overlay", "list", "--user", DEFAULT_USER])

    return None


def interpret_probe(action: Action, output: str) -> Optional[str]:
    """Normalise raw probe output into a state string the inverse can use."""

    op = action.op
    text = (output or "").strip()

    if op in ("settings_put", "settings_delete", "device_config_put", "device_config_delete"):
        if not text or text.lower() == "null":
            return ""
        return text

    if op == "wm_density_delta":
        # "Physical density: 460" and/or "Override density: 440"
        density = _parse_density(text)
        return str(density) if density is not None else None

    if op == "pm_disable":
        package = action.get("pkg", "")
        for line in text.splitlines():
            if line.strip().startswith("package:") and package in line:
                return "disabled"
        return "enabled"

    if op in ("overlay_enable", "overlay_disable"):
        package = action.get("pkg", "")
        for line in text.splitlines():
            stripped = line.strip()
            if package and package in stripped:
                if stripped.startswith("[x]"):
                    return "enabled"
                if stripped.startswith("[ ]"):
                    return "disabled"
        return "absent"

    return None


def _parse_density(text: str) -> Optional[int]:
    """Pull the effective density out of ``wm density`` output."""

    override = None
    physical = None
    for line in text.splitlines():
        lowered = line.lower()
        digits = "".join(ch for ch in line if ch.isdigit())
        if not digits:
            continue
        if "override" in lowered:
            override = int(digits)
        elif "physical" in lowered:
            physical = int(digits)
    return override if override is not None else physical


# ---------------------------------------------------------------------------
# inverse side
# ---------------------------------------------------------------------------


def inverse_action(action: Action, previous: Optional[str]) -> Optional[Action]:
    """Build the action that undoes ``action``.

    Returns ``None`` when undoing would be wrong or impossible: an already
    disabled package, an overlay that was already on, a fire-and-forget command
    with no explicit revert.
    """

    op = action.op

    if op in ("settings_put", "settings_delete"):
        ns, key = action.get("ns"), action.get("key")
        if previous is None:
            return None
        if previous == "":
            return Action("settings_delete", {"ns": ns, "key": key})
        return Action("settings_put", {"ns": ns, "key": key, "value": previous})

    if op in ("device_config_put", "device_config_delete"):
        ns, key = action.get("ns"), action.get("key")
        if previous is None:
            return None
        if previous == "":
            return Action("device_config_delete", {"ns": ns, "key": key})
        return Action("device_config_put", {"ns": ns, "key": key, "value": previous})

    if op == "pm_disable":
        if previous == "disabled":
            return None
        return Action("pm_enable", {"pkg": action.get("pkg")})

    if op == "pm_enable":
        return Action("pm_disable", {"pkg": action.get("pkg")})

    if op == "overlay_enable":
        if previous == "enabled":
            return None
        return Action("overlay_disable", {"pkg": action.get("pkg")})

    if op == "overlay_disable":
        if previous == "disabled":
            return None
        return Action("overlay_enable", {"pkg": action.get("pkg")})

    if op == "svc":
        return Action(
            "svc",
            {"service": action.get("service"), "enable": not bool(action.get("enable"))},
        )

    if op == "wm_density_delta":
        if previous is None:
            return None
        return Action("wm_density_set", {"value": previous})

    return None


def is_read_only(op: str) -> bool:
    """True for ops that never mutate device state."""

    return op in ("settings_get", "device_config_get", "pm_list", "overlay_list")
