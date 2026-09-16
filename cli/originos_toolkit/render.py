"""Tiny dependency-free terminal rendering helpers."""

from __future__ import annotations

import os
import sys
from typing import Iterable, List, Optional, Sequence

_RISK_LABELS = {"low": "low", "medium": "med", "high": "HIGH"}


def use_colour() -> bool:
    if os.environ.get("NO_COLOR"):
        return False
    if os.environ.get("FORCE_COLOR"):
        return True
    return sys.stdout.isatty()


def paint(text: str, colour: str) -> str:
    if not use_colour():
        return text
    codes = {
        "red": "31",
        "green": "32",
        "yellow": "33",
        "blue": "34",
        "magenta": "35",
        "cyan": "36",
        "dim": "2",
        "bold": "1",
    }
    code = codes.get(colour)
    return f"\033[{code}m{text}\033[0m" if code else text


def risk_badge(risk: str) -> str:
    label = _RISK_LABELS.get(risk, risk)
    colour = {"low": "green", "medium": "yellow", "high": "red"}.get(risk, "dim")
    return paint(f"[{label}]", colour)


def marker(flag: Optional[bool]) -> str:
    if flag is True:
        return paint("on ", "green")
    if flag is False:
        return paint("off", "dim")
    return paint("?  ", "dim")


def table(headers: Sequence[str], rows: Iterable[Sequence[str]], indent: str = "  ") -> str:
    """Render a left-aligned, space-padded table."""

    plain_rows: List[List[str]] = [[str(cell) for cell in row] for row in rows]
    widths = [len(h) for h in headers]
    for row in plain_rows:
        for index, cell in enumerate(row):
            if index < len(widths):
                widths[index] = max(widths[index], len(_strip_ansi(cell)))

    def fmt(row: Sequence[str]) -> str:
        parts = []
        for index, cell in enumerate(row):
            width = widths[index] if index < len(widths) else 0
            pad = max(0, width - len(_strip_ansi(cell)))
            parts.append(cell + " " * pad)
        return indent + "  ".join(parts).rstrip()

    lines = [fmt(list(headers))]
    lines.append(indent + "  ".join("-" * w for w in widths).rstrip())
    lines.extend(fmt(row) for row in plain_rows)
    return "\n".join(lines)


def _strip_ansi(text: str) -> str:
    out = []
    inside = False
    for char in text:
        if char == "\033":
            inside = True
            continue
        if inside:
            if char.isalpha():
                inside = False
            continue
        out.append(char)
    return "".join(out)


def heading(text: str) -> str:
    return "\n" + paint(text, "bold") + "\n" + "=" * len(text)


def bullet(text: str, indent: int = 2) -> str:
    return " " * indent + "• " + text


def wrap(text: str, width: int = 88, indent: str = "  ") -> str:
    """Naive greedy word wrap that also keeps explicit newlines."""

    if not text:
        return ""
    lines: List[str] = []
    for paragraph in text.split("\n"):
        if not paragraph.strip():
            lines.append("")
            continue
        current = indent
        for word in paragraph.split():
            if len(current) + len(word) + 1 > width and current.strip():
                lines.append(current.rstrip())
                current = indent + word + " "
            else:
                current += word + " "
        lines.append(current.rstrip())
    return "\n".join(lines)
