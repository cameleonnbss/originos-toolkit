"""The revert journal.

Every apply writes the *inverse* of what it did into a small JSON file before
the first command reaches the device. Reverting then replays that journal, so
restores are exact even across reboots, adb disconnects or app restarts, and
even if the catalog later changes its mind about what a tweak should do.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

from .catalog import Action

JOURNAL_VERSION = 1


def default_journal_path() -> Path:
    """Where the journal lives unless overridden."""

    override = os.environ.get("ORIGINOS_TOOLKIT_JOURNAL")
    if override:
        return Path(override).expanduser()

    if os.name == "nt":  # pragma: no cover - platform specific
        base = Path(os.environ.get("APPDATA", Path.home()))
        return base / "originos-toolkit" / "journal.json"

    base = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state"))
    return base / "originos-toolkit" / "journal.json"


@dataclass
class JournalEntry:
    tweak_id: str
    name: str
    applied_at: str
    inverse: List[Action] = field(default_factory=list)
    note: str = ""

    def to_json(self) -> Dict[str, Any]:
        return {
            "tweakId": self.tweak_id,
            "name": self.name,
            "appliedAt": self.applied_at,
            "inverse": [a.to_json() for a in self.inverse],
            "note": self.note,
        }

    @classmethod
    def from_json(cls, data: Dict[str, Any]) -> "JournalEntry":
        return cls(
            tweak_id=data["tweakId"],
            name=data.get("name", data["tweakId"]),
            applied_at=data.get("appliedAt", ""),
            inverse=[Action.from_json(a) for a in data.get("inverse", [])],
            note=data.get("note", ""),
        )


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


class Journal:
    """Ordered, append-only-ish record of applied tweaks."""

    def __init__(self, path: Optional[Path] = None) -> None:
        self.path = Path(path) if path else default_journal_path()
        self.entries: List[JournalEntry] = []
        self.load()

    # -- io ---------------------------------------------------------------

    def load(self) -> None:
        if not self.path.is_file():
            self.entries = []
            return
        try:
            with self.path.open("r", encoding="utf-8") as handle:
                data = json.load(handle)
        except (json.JSONDecodeError, OSError):
            # A corrupt journal must never block an apply; we start clean but
            # keep the damaged file around for forensics.
            try:
                self.path.rename(self.path.with_suffix(".corrupt.json"))
            except OSError:
                pass
            self.entries = []
            return
        self.entries = [JournalEntry.from_json(e) for e in data.get("entries", [])]

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "journalVersion": JOURNAL_VERSION,
            "entries": [e.to_json() for e in self.entries],
        }
        tmp = self.path.with_suffix(".tmp")
        with tmp.open("w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
        tmp.replace(self.path)

    # -- mutations --------------------------------------------------------

    def record(self, tweak_id: str, name: str, inverse: Iterable[Action], note: str = "") -> JournalEntry:
        """Replace any previous entry for the same tweak and persist."""

        self.forget(tweak_id)
        entry = JournalEntry(
            tweak_id=tweak_id,
            name=name,
            applied_at=now_iso(),
            inverse=list(inverse),
            note=note,
        )
        self.entries.append(entry)
        self.save()
        return entry

    def forget(self, tweak_id: str) -> Optional[JournalEntry]:
        """Drop the entry for a tweak and persist. Returns the removed entry."""

        removed = None
        kept: List[JournalEntry] = []
        for entry in self.entries:
            if entry.tweak_id == tweak_id and removed is None:
                removed = entry
            else:
                kept.append(entry)
        if removed is not None:
            self.entries = kept
            self.save()
        return removed

    def get(self, tweak_id: str) -> Optional[JournalEntry]:
        for entry in self.entries:
            if entry.tweak_id == tweak_id:
                return entry
        return None

    def clear(self) -> None:
        self.entries = []
        self.save()

    @property
    def applied_ids(self) -> List[str]:
        return [e.tweak_id for e in self.entries]
