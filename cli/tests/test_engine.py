"""Tests for the apply/revert engine, including its safety guarantees."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from . import CATALOG_DIR
from originos_toolkit.adb import CommandResult, RecordingRunner
from originos_toolkit.catalog import load_catalog
from originos_toolkit.engine import Engine, EngineError
from originos_toolkit.journal import Journal


class EngineTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.catalog = load_catalog(str(CATALOG_DIR))
        self._tmp = tempfile.TemporaryDirectory()
        self.journal_path = Path(self._tmp.name) / "journal.json"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def engine(self, answers=None, dry_run=False, allow_experimental=False):
        runner = RecordingRunner(answers=answers or {})
        journal = Journal(self.journal_path)
        engine = Engine(
            catalog=self.catalog,
            runner=runner,
            journal=journal,
            dry_run=dry_run,
            allow_experimental=allow_experimental,
        )
        return engine, runner


class ApplyTests(EngineTestCase):
    def test_apply_runs_the_expected_commands(self) -> None:
        engine, runner = self.engine(
            {
                "settings get system peak_refresh_rate": "60.0\n",
                "settings get system min_refresh_rate": "null\n",
            }
        )
        result = engine.apply(self.catalog.tweak("force-max-refresh-rate"))

        self.assertTrue(result.ok, result.errors)
        self.assertIn("settings put system peak_refresh_rate 144.0", runner.calls)
        self.assertIn("settings put system min_refresh_rate 144.0", runner.calls)

    def test_journal_holds_the_exact_inverse(self) -> None:
        engine, _ = self.engine(
            {
                "settings get system peak_refresh_rate": "60.0\n",
                "settings get system min_refresh_rate": "null\n",
            }
        )
        engine.apply(self.catalog.tweak("force-max-refresh-rate"))

        entry = engine.journal.get("force-max-refresh-rate")
        self.assertIsNotNone(entry)
        restored = {(a.op, a.get("key"), a.get("value")) for a in entry.inverse}
        self.assertIn(("settings_put", "peak_refresh_rate", "60.0"), restored)
        self.assertIn(("settings_delete", "min_refresh_rate", None), restored)

    def test_journal_is_written_before_any_write_reaches_the_device(self) -> None:
        """If the device dies mid-apply, the inverse must already be on disk."""

        seen: list = []
        journal_file = self.journal_path

        class SpyRunner(RecordingRunner):
            def shell(self, argv):  # type: ignore[override]
                rendered = " ".join(argv)
                if rendered.startswith(("settings put", "settings delete")):
                    on_disk = journal_file.read_text(encoding="utf-8") if journal_file.exists() else ""
                    seen.append("peak_refresh_rate" in on_disk)
                return super().shell(argv)

        runner = SpyRunner(
            answers={
                "settings get system peak_refresh_rate": "60.0\n",
                "settings get system min_refresh_rate": "null\n",
            }
        )
        engine = Engine(self.catalog, runner, Journal(self.journal_path))
        engine.apply(self.catalog.tweak("force-max-refresh-rate"))

        self.assertTrue(seen, "the apply should have performed writes")
        self.assertEqual(seen, [True] * len(seen), "every write must be preceded by the journal")

    def test_already_applied_tweaks_are_skipped_not_restacked(self) -> None:
        answers = {
            "settings get system peak_refresh_rate": "60.0\n",
            "settings get system min_refresh_rate": "null\n",
        }
        engine, runner = self.engine(answers)
        engine.apply(self.catalog.tweak("force-max-refresh-rate"))
        before = len(runner.calls)

        again = engine.apply(self.catalog.tweak("force-max-refresh-rate"))
        self.assertEqual(len(runner.calls), before, "second apply must not touch the device")
        self.assertTrue(any("already applied" in note for note in again.skipped))
        # The original restore values must survive the refusal.
        entry = engine.journal.get("force-max-refresh-rate")
        self.assertIn(("settings_put", "peak_refresh_rate", "60.0"), {(a.op, a.get("key"), a.get("value")) for a in entry.inverse})

    def test_force_reapplies(self) -> None:
        answers = {
            "settings get system peak_refresh_rate": "60.0\n",
            "settings get system min_refresh_rate": "null\n",
        }
        engine, runner = self.engine(answers)
        tweak = self.catalog.tweak("force-max-refresh-rate")
        engine.apply(tweak)
        engine.apply(tweak, force=True)
        self.assertEqual(runner.calls.count("settings put system peak_refresh_rate 144.0"), 2)

    def test_experimental_tweaks_are_blocked_by_default(self) -> None:
        engine, _ = self.engine()
        with self.assertRaises(EngineError):
            engine.apply(self.catalog.tweak("doze-force-idle"))

    def test_experimental_tweaks_run_when_allowed(self) -> None:
        engine, runner = self.engine(allow_experimental=True)
        result = engine.apply(self.catalog.tweak("doze-force-idle"))
        self.assertTrue(result.ok)
        self.assertIn("dumpsys deviceidle force-idle", runner.calls)

    def test_dry_run_touches_nothing(self) -> None:
        engine, runner = self.engine(dry_run=True)
        result = engine.apply(self.catalog.tweak("force-max-refresh-rate"))
        self.assertTrue(result.dry_run)
        self.assertEqual(runner.calls, [], "a dry run must not reach the device")
        self.assertFalse(self.journal_path.exists(), "a dry run must not write a journal")
        self.assertTrue(result.steps, "a dry run must still show the commands")

    def test_one_shot_tweak_applies_without_journaling(self) -> None:
        engine, runner = self.engine()
        result = engine.apply(self.catalog.tweak("trim-caches"))
        self.assertTrue(result.ok, result.errors)
        self.assertEqual(runner.calls, ["pm trim-caches 999G"])
        self.assertEqual(engine.journal.entries, [])

    def test_debloat_records_one_inverse_per_package(self) -> None:
        engine, _ = self.engine(
            {
                "pm list packages -d com.vivo.appstore": "",
                "pm list packages -d com.vivo.browser": "package:com.vivo.browser\n",
            }
        )
        engine.apply(self.catalog.tweak("debloat-vivo-store-browser"))
        entry = engine.journal.get("debloat-vivo-store-browser")
        inverses = {(a.op, a.get("pkg")) for a in entry.inverse}
        self.assertIn(("pm_enable", "com.vivo.appstore"), inverses)
        # com.vivo.browser was already disabled, so reverting must leave it alone.
        self.assertNotIn(("pm_enable", "com.vivo.browser"), inverses)

    def test_failed_probe_aborts_before_writing(self) -> None:
        engine, runner = self.engine()
        runner.fail_on = {"settings get"}
        with self.assertRaises(EngineError):
            engine.apply(self.catalog.tweak("force-max-refresh-rate"))
        self.assertNotIn("settings put system peak_refresh_rate 144.0", runner.calls)

    def test_write_failure_is_reported_but_not_rolled_back_silently(self) -> None:
        engine, runner = self.engine(
            {
                "settings get system peak_refresh_rate": "60.0\n",
                "settings get system min_refresh_rate": "null\n",
            }
        )
        runner.fail_on = {"settings put"}
        result = engine.apply(self.catalog.tweak("force-max-refresh-rate"))
        self.assertFalse(result.ok)
        self.assertTrue(result.errors)
        # The journal must still exist so the user can retry the revert.
        self.assertIsNotNone(engine.journal.get("force-max-refresh-rate"))


class RevertTests(EngineTestCase):
    def test_revert_restores_the_previous_values(self) -> None:
        engine, runner = self.engine(
            {
                "settings get system peak_refresh_rate": "60.0\n",
                "settings get system min_refresh_rate": "null\n",
            }
        )
        tweak = self.catalog.tweak("force-max-refresh-rate")
        engine.apply(tweak)
        runner.calls.clear()

        result = engine.revert(tweak, tweak.id)
        self.assertTrue(result.ok, result.errors)
        self.assertIn("settings put system peak_refresh_rate 60.0", runner.calls)
        self.assertIn("settings delete system min_refresh_rate", runner.calls)
        self.assertIsNone(engine.journal.get(tweak.id))

    def test_revert_survives_a_new_process(self) -> None:
        answers = {
            "settings get system peak_refresh_rate": "60.0\n",
            "settings get system min_refresh_rate": "null\n",
        }
        engine, _ = self.engine(answers)
        engine.apply(self.catalog.tweak("force-max-refresh-rate"))

        fresh_engine, runner = self.engine(answers)
        result = fresh_engine.revert(self.catalog.tweak("force-max-refresh-rate"), "force-max-refresh-rate")
        self.assertTrue(result.ok, result.errors)
        self.assertIn("settings put system peak_refresh_rate 60.0", runner.calls)

    def test_revert_uses_explicit_revert_list_when_no_journal_exists(self) -> None:
        engine, runner = self.engine()
        tweak = self.catalog.tweak("gesture-navigation")
        result = engine.revert(tweak, tweak.id)
        self.assertTrue(result.ok, result.errors)
        self.assertIn(
            "cmd overlay enable --user 0 com.android.internal.systemui.navbar.threebutton",
            runner.calls,
        )

    def test_revert_without_journal_or_explicit_list_errors(self) -> None:
        engine, _ = self.engine()
        with self.assertRaises(EngineError):
            engine.revert(self.catalog.tweak("force-gpu-rendering"), "force-gpu-rendering")

    def test_revert_all_empties_the_journal(self) -> None:
        answers = {
            "settings get system peak_refresh_rate": "60.0\n",
            "settings get system min_refresh_rate": "null\n",
            "settings get global force_gpu_rendering": "null\n",
        }
        engine, _ = self.engine(answers)
        engine.apply(self.catalog.tweak("force-max-refresh-rate"))
        engine.apply(self.catalog.tweak("force-gpu-rendering"))
        self.assertEqual(len(engine.journal.entries), 2)

        engine.revert_all()
        self.assertEqual(engine.journal.entries, [])

    def test_revert_dry_run_keeps_the_journal(self) -> None:
        answers = {
            "settings get system peak_refresh_rate": "60.0\n",
            "settings get system min_refresh_rate": "null\n",
        }
        engine, _ = self.engine(answers)
        engine.apply(self.catalog.tweak("force-max-refresh-rate"))

        dry, runner = self.engine(answers, dry_run=True)
        dry.journal = engine.journal
        result = dry.revert(self.catalog.tweak("force-max-refresh-rate"), "force-max-refresh-rate")
        self.assertTrue(result.dry_run)
        self.assertEqual(runner.calls, [])
        self.assertIsNotNone(dry.journal.get("force-max-refresh-rate"))


class StatusTests(EngineTestCase):
    def test_status_detects_an_applied_tweak(self) -> None:
        engine, _ = self.engine(
            {
                "settings get system peak_refresh_rate": "144.0\n",
                "settings get system min_refresh_rate": "144.0\n",
            }
        )
        self.assertTrue(engine.is_applied(self.catalog.tweak("force-max-refresh-rate")))

    def test_status_detects_a_reverted_tweak(self) -> None:
        engine, _ = self.engine(
            {
                "settings get system peak_refresh_rate": "60.0\n",
                "settings get system min_refresh_rate": "60.0\n",
            }
        )
        self.assertFalse(engine.is_applied(self.catalog.tweak("force-max-refresh-rate")))

    def test_status_is_none_for_one_shot_actions(self) -> None:
        engine, _ = self.engine()
        self.assertIsNone(engine.is_applied(self.catalog.tweak("trim-caches")))

    def test_status_covers_the_whole_catalog(self) -> None:
        engine, _ = self.engine()
        rows = engine.status()
        self.assertEqual(len(rows), len(self.catalog.tweaks))

    def test_status_for_debloat(self) -> None:
        engine, _ = self.engine(
            {
                "pm list packages -d com.vivo.appstore": "package:com.vivo.appstore\n",
                "pm list packages -d com.vivo.browser": "package:com.vivo.browser\n",
            }
        )
        self.assertTrue(engine.is_applied(self.catalog.tweak("debloat-vivo-store-browser")))


class ResultShapeTests(EngineTestCase):
    def test_results_expose_the_commands(self) -> None:
        engine, _ = self.engine(
            {
                "settings get system peak_refresh_rate": "60.0\n",
                "settings get system min_refresh_rate": "null\n",
            }
        )
        result = engine.apply(self.catalog.tweak("force-max-refresh-rate"))
        rendered = [step.call.render() for step in result.steps]
        self.assertEqual(
            rendered,
            ["settings put system peak_refresh_rate 144.0", "settings put system min_refresh_rate 144.0"],
        )
        self.assertEqual(result.carried_out, 2)

    def test_plan_only_reads(self) -> None:
        engine, runner = self.engine(
            {"settings get system peak_refresh_rate": "60.0\n", "settings get system min_refresh_rate": "null\n"}
        )
        _, steps = engine.plan(self.catalog.tweak("force-max-refresh-rate"))
        self.assertEqual(len(steps), 2)
        self.assertTrue(all(call.startswith("settings get") for call in runner.calls), runner.calls)
        self.assertFalse(any("put" in call for call in runner.calls))

    def test_command_result_helpers(self) -> None:
        ok = CommandResult(argv=("a",), stdout="hello\n")
        bad = CommandResult(argv=("a",), stderr="boom", code=1)
        self.assertTrue(ok.ok)
        self.assertEqual(ok.output, "hello")
        self.assertFalse(bad.ok)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
