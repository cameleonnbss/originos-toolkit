"""Tests for the revert journal."""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path

from originos_toolkit.catalog import Action
from originos_toolkit.journal import Journal, default_journal_path


class JournalTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.path = Path(self._tmp.name) / "journal.json"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_missing_file_starts_empty(self) -> None:
        journal = Journal(self.path)
        self.assertEqual(journal.entries, [])

    def test_record_then_reload(self) -> None:
        journal = Journal(self.path)
        journal.record(
            "force-max-refresh-rate",
            "Force maximum refresh rate",
            [Action("settings_put", {"ns": "system", "key": "peak_refresh_rate", "value": "60.0"})],
        )

        reloaded = Journal(self.path)
        self.assertEqual(reloaded.applied_ids, ["force-max-refresh-rate"])
        entry = reloaded.get("force-max-refresh-rate")
        self.assertEqual(entry.inverse[0].get("value"), "60.0")
        self.assertTrue(entry.applied_at)

    def test_recording_twice_replaces_and_moves_to_the_end(self) -> None:
        journal = Journal(self.path)
        journal.record("a", "A", [Action("settings_delete", {"ns": "system", "key": "x"})])
        journal.record("b", "B", [Action("settings_delete", {"ns": "system", "key": "y"})])
        journal.record("a", "A", [Action("settings_delete", {"ns": "system", "key": "z"})])

        self.assertEqual(journal.applied_ids, ["b", "a"])
        self.assertEqual(journal.get("a").inverse[0].get("key"), "z")

    def test_forget_returns_the_removed_entry(self) -> None:
        journal = Journal(self.path)
        journal.record("a", "A", [])
        removed = journal.forget("a")
        self.assertIsNotNone(removed)
        self.assertEqual(journal.entries, [])
        self.assertIsNone(journal.forget("never-existed"))

    def test_clear_persists(self) -> None:
        journal = Journal(self.path)
        journal.record("a", "A", [])
        journal.clear()
        self.assertEqual(Journal(self.path).entries, [])

    def test_corrupt_journal_is_quarantined_not_fatal(self) -> None:
        self.path.write_text("{ this is not json", encoding="utf-8")
        journal = Journal(self.path)
        self.assertEqual(journal.entries, [])
        self.assertTrue(self.path.with_suffix(".corrupt.json").exists())

    def test_written_file_is_valid_json_with_a_version(self) -> None:
        journal = Journal(self.path)
        journal.record("a", "A", [])
        payload = json.loads(self.path.read_text(encoding="utf-8"))
        self.assertEqual(payload["journalVersion"], 1)
        self.assertEqual(len(payload["entries"]), 1)

    def test_no_leftover_temp_file(self) -> None:
        journal = Journal(self.path)
        journal.record("a", "A", [])
        leftovers = [p.name for p in Path(self._tmp.name).iterdir() if p.name.endswith(".tmp")]
        self.assertEqual(leftovers, [])

    def test_default_path_honours_the_environment(self) -> None:
        previous = os.environ.get("ORIGINOS_TOOLKIT_JOURNAL")
        os.environ["ORIGINOS_TOOLKIT_JOURNAL"] = str(self.path)
        try:
            self.assertEqual(default_journal_path(), self.path)
        finally:
            if previous is None:
                os.environ.pop("ORIGINOS_TOOLKIT_JOURNAL", None)
            else:
                os.environ["ORIGINOS_TOOLKIT_JOURNAL"] = previous

    def test_default_path_is_absolute_and_named(self) -> None:
        previous = os.environ.pop("ORIGINOS_TOOLKIT_JOURNAL", None)
        try:
            path = default_journal_path()
            self.assertTrue(path.is_absolute())
            self.assertEqual(path.name, "journal.json")
        finally:
            if previous is not None:
                os.environ["ORIGINOS_TOOLKIT_JOURNAL"] = previous


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
