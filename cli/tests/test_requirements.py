"""Tests for the access-requirement classifier.

These lock the claim the project makes everywhere else: a tweak that only
touches the `system` namespace runs without Shizuku, and the catalog is not
allowed to claim otherwise.
"""

from __future__ import annotations

import copy
import json
import unittest

from . import CATALOG_DIR
from originos_toolkit.catalog import Action, Catalog, Tweak, lint, load_catalog
from originos_toolkit.ops import (
    action_requirement,
    requirement_of,
    runs_without_shizuku,
    settings_targets,
    tweak_requirement,
)


def tweak(**overrides) -> Tweak:
    base = {
        "id": "sample",
        "name": "Sample",
        "category": "display",
        "summary": "for tests",
        "requires": "shizuku",
        "originOs": ["originos5"],
        "actions": [{"op": "settings_put", "ns": "system", "key": "k", "value": "1"}],
    }
    base.update(overrides)
    return Tweak.from_json(base)


class ActionRequirementTests(unittest.TestCase):
    def test_system_namespace_writes_need_only_the_settings_grant(self) -> None:
        for op in ("settings_put", "settings_delete"):
            with self.subTest(op=op):
                action = Action(op, {"ns": "system", "key": "peak_refresh_rate", "value": "144.0"})
                self.assertEqual(action_requirement(action), "settings")

    def test_reading_a_system_setting_needs_no_grant_at_all(self) -> None:
        """The app reads the System namespace through its own resolver."""

        action = Action("settings_get", {"ns": "system", "key": "peak_refresh_rate"})
        self.assertEqual(action_requirement(action), "none")

    def test_secure_and_global_need_the_shell_user(self) -> None:
        for namespace in ("secure", "global"):
            for op in ("settings_put", "settings_get"):
                with self.subTest(ns=namespace, op=op):
                    action = Action(op, {"ns": namespace, "key": "k", "value": "1"})
                    self.assertEqual(action_requirement(action), "shell")

    def test_shell_ops_need_the_shell_user(self) -> None:
        for action in (
            Action("pm_disable", {"pkg": "com.example"}),
            Action("device_config_put", {"ns": "activity_manager", "key": "k", "value": "1"}),
            Action("wm_density_set", {"value": "420"}),
            Action("svc", {"service": "nfc", "enable": False}),
            Action("shell", {"command": "cmd overlay list"}),
        ):
            with self.subTest(op=action.op):
                self.assertEqual(action_requirement(action), "shell")

    def test_read_only_ops_are_free(self) -> None:
        for op in ("device_config_get", "pm_list", "overlay_list"):
            with self.subTest(op=op):
                self.assertEqual(action_requirement(Action(op, {})), "none")

    def test_read_only_shell_ops_still_need_the_shell_user(self) -> None:
        """`wm density` reads a display property, not a setting we can resolve."""

        self.assertEqual(action_requirement(Action("wm_density_set", {"value": "420"})), "shell")


class RequirementAggregationTests(unittest.TestCase):
    def test_strongest_requirement_wins(self) -> None:
        actions = [
            Action("settings_put", {"ns": "system", "key": "a", "value": "1"}),
            Action("settings_put", {"ns": "global", "key": "b", "value": "1"}),
        ]
        self.assertEqual(requirement_of(actions), "shell")

    def test_revert_side_counts(self) -> None:
        """A tweak whose undo needs the shell is not a no-Shizuku tweak."""

        subject = tweak(
            actions=[{"op": "settings_put", "ns": "system", "key": "k", "value": "1"}],
            revert=[{"op": "settings_put", "ns": "global", "key": "k", "value": "0"}],
        )
        self.assertEqual(tweak_requirement(subject), "shell")
        self.assertFalse(runs_without_shizuku(subject))

    def test_system_only_tweak_runs_without_shizuku(self) -> None:
        subject = tweak()
        self.assertEqual(tweak_requirement(subject), "settings")
        self.assertTrue(runs_without_shizuku(subject))

    def test_settings_targets_are_reported(self) -> None:
        targets = settings_targets(
            [
                Action("settings_put", {"ns": "system", "key": "peak_refresh_rate", "value": "1"}),
                Action("pm_disable", {"pkg": "com.example"}),
            ]
        )
        self.assertEqual(targets, ["settings/system/peak_refresh_rate"])


class CatalogConsistencyTests(unittest.TestCase):
    """The catalog's `requires` field must match what the actions can do."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog = load_catalog(str(CATALOG_DIR))

    def test_real_catalog_is_consistent(self) -> None:
        problems = lint(self.catalog)
        self.assertEqual(problems, [], "\n".join(problems))

    def test_the_flagship_tweak_needs_no_shizuku(self) -> None:
        """Forcing the refresh rate is the reason this project exists."""

        subject = self.catalog.tweak("force-max-refresh-rate")
        self.assertEqual(subject.requires, "settings")
        self.assertTrue(runs_without_shizuku(subject))

    def test_some_tweaks_are_usable_without_shizuku(self) -> None:
        usable = [t for t in self.catalog.tweaks if runs_without_shizuku(t)]
        self.assertGreaterEqual(len(usable), 8)

    def test_lint_rejects_claiming_shizuku_for_a_system_only_tweak(self) -> None:
        lying = tweak(requires="shizuku")
        catalog = Catalog(1, [], [lying], [], [])
        problems = lint(catalog)
        self.assertTrue(
            any("works without Shizuku" in problem for problem in problems),
            problems,
        )

    def test_lint_rejects_claiming_settings_for_a_shell_tweak(self) -> None:
        lying = tweak(
            requires="settings",
            actions=[{"op": "settings_put", "ns": "global", "key": "k", "value": "1"}],
        )
        catalog = Catalog(1, [], [lying], [], [])
        problems = lint(catalog)
        self.assertTrue(any("need the shell user" in problem for problem in problems), problems)

    def test_requires_vocabulary_is_enforced(self) -> None:
        catalog = Catalog(1, [], [tweak(requires="magic")], [], [])
        self.assertTrue(any("requires must be one of" in p for p in lint(catalog)))


if __name__ == "__main__":
    unittest.main()
