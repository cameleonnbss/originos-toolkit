"""Tests that guard the shared catalog itself.

These run against the real ``catalog/`` directory, so a mistake in a JSON file
fails CI instead of waiting for a user to hit it on their phone.
"""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from . import CATALOG_DIR
from originos_toolkit.catalog import (
    AUTO_INVERTIBLE,
    KNOWN_OPS,
    CatalogError,
    discover_catalog,
    lint,
    load_catalog,
)


class CatalogTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog = load_catalog(str(CATALOG_DIR))

    def test_catalog_directory_is_discoverable(self) -> None:
        self.assertEqual(Path(discover_catalog(str(CATALOG_DIR))).resolve(), CATALOG_DIR.resolve())

    def test_catalog_version_matches_supported(self) -> None:
        self.assertEqual(self.catalog.catalog_version, 1)

    def test_lint_is_clean(self) -> None:
        problems = lint(self.catalog)
        self.assertEqual(problems, [], "\n".join(problems))

    def test_has_a_useful_number_of_tweaks(self) -> None:
        self.assertGreaterEqual(len(self.catalog.tweaks), 25)

    def test_tweak_ids_are_unique(self) -> None:
        ids = [t.id for t in self.catalog.tweaks]
        self.assertEqual(len(ids), len(set(ids)))

    def test_ids_are_kebab_case(self) -> None:
        for tweak in self.catalog.tweaks:
            self.assertRegex(tweak.id, r"^[a-z0-9]+(-[a-z0-9]+)*$", tweak.id)

    def test_every_category_has_at_least_one_tweak(self) -> None:
        used = {t.category for t in self.catalog.tweaks}
        for category in self.catalog.categories:
            self.assertIn(category.id, used, f"category {category.id} is empty")

    def test_ops_are_all_known(self) -> None:
        for tweak in self.catalog.tweaks:
            for action in tweak.actions:
                self.assertIn(action.op, KNOWN_OPS, f"{tweak.id} → {action.op}")

    def test_normalizing_tweaks_are_reversible(self) -> None:
        """Anything that changes state must be undoable, or say it is not."""

        for tweak in self.catalog.tweaks:
            if tweak.reversible is False:
                continue
            self.assertTrue(tweak.is_reversible, f"{tweak.id} cannot be reverted")

    def test_one_shot_tweaks_are_marked(self) -> None:
        for tweak in self.catalog.tweaks:
            if tweak.revert is None and not all(a.op in AUTO_INVERTIBLE for a in tweak.actions):
                self.assertIs(
                    tweak.reversible,
                    False,
                    f"{tweak.id} is a one-shot action but does not set reversible: false",
                )

    def test_every_tweak_has_prose(self) -> None:
        for tweak in self.catalog.tweaks:
            self.assertGreater(len(tweak.summary), 30, f"{tweak.id}: summary too short")
            self.assertGreater(len(tweak.details), 40, f"{tweak.id}: details too short")

    def test_verified_flag_is_boolean(self) -> None:
        unverified = [t.id for t in self.catalog.tweaks if not t.verified]
        self.assertIn("fixed-performance-mode", unverified)

    def test_density_tweak_uses_a_relative_delta(self) -> None:
        tweak = self.catalog.tweak("density-compact")
        self.assertEqual([a.op for a in tweak.actions], ["wm_density_delta"])

    def test_no_debloat_tweak_uses_uninstall(self) -> None:
        """`pm uninstall --user 0` loses data; we only ever disable."""

        raw = (CATALOG_DIR / "tweaks.json").read_text(encoding="utf-8")
        self.assertNotIn("pm uninstall", raw)

    def test_profiles_reference_real_tweaks(self) -> None:
        known = {t.id for t in self.catalog.tweaks}
        for profile in self.catalog.profiles:
            for tweak_id in profile.tweaks:
                self.assertIn(tweak_id, known, f"profile {profile.id}")

    def test_profiles_are_not_empty(self) -> None:
        self.assertGreaterEqual(len(self.catalog.profiles), 5)

    def test_awesome_entries_use_https(self) -> None:
        for section in self.catalog.awesome:
            for entry in section.entries:
                self.assertTrue(entry.url.startswith("https://"), entry.name)

    def test_awesome_covers_the_requested_repos(self) -> None:
        repos = {e.repo for section in self.catalog.awesome for e in section.entries}
        for expected in (
            "ewfawfasdf/VivoIQOO144FPSUnlocker",
            "Astreas-Core/otweak",
            "timschneeb/awesome-shizuku",
            "0x192/universal-android-debloater",
            "RikkaApps/Shizuku",
        ):
            self.assertIn(expected, repos)

    def test_missing_catalog_raises(self) -> None:
        with self.assertRaises(CatalogError):
            load_catalog(str(CATALOG_DIR / "does-not-exist"))

    def test_bad_json_raises(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "tweaks.json").write_text("{not json", encoding="utf-8")
            with self.assertRaises(CatalogError):
                load_catalog(tmp)

    def test_json_files_are_valid_json(self) -> None:
        for path in sorted(CATALOG_DIR.glob("*.json")):
            with self.subTest(path=path.name):
                json.loads(path.read_text(encoding="utf-8"))


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
