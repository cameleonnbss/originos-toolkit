"""End-to-end tests of the command-line surface (no device required)."""

from __future__ import annotations

import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path

from . import CATALOG_DIR
from originos_toolkit.cli import EXIT_ERROR, EXIT_OK, EXIT_USAGE, main


def run(*argv: str):
    """Run the CLI, returning (exit_code, stdout)."""

    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        code = main(list(argv))
    return code, buffer.getvalue()


class CliTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.journal = Path(self._tmp.name) / "journal.json"
        os.environ["ORIGINOS_TOOLKIT_JOURNAL"] = str(self.journal)

    def tearDown(self) -> None:
        os.environ.pop("ORIGINOS_TOOLKIT_JOURNAL", None)
        self._tmp.cleanup()


class BasicsTests(CliTestCase):
    def test_no_command_prints_help(self) -> None:
        code, out = run()
        self.assertEqual(code, EXIT_USAGE)
        self.assertIn("originos-toolkit", out)

    def test_lint_is_clean(self) -> None:
        code, out = run("lint", "--catalog", str(CATALOG_DIR))
        self.assertEqual(code, EXIT_OK, out)
        self.assertIn("no problems found", out)

    def test_lint_json(self) -> None:
        code, out = run("lint", "--catalog", str(CATALOG_DIR), "--json")
        self.assertEqual(code, EXIT_OK)
        self.assertTrue(json.loads(out)["ok"])

    def test_catalog_json_lists_tweaks(self) -> None:
        code, out = run("catalog", "--catalog", str(CATALOG_DIR), "--json")
        self.assertEqual(code, EXIT_OK)
        payload = json.loads(out)
        self.assertGreaterEqual(len(payload), 25)
        self.assertIn("risk", payload[0])

    def test_catalog_filters_by_category(self) -> None:
        code, out = run("catalog", "--catalog", str(CATALOG_DIR), "--category", "debloat", "--json")
        self.assertEqual(code, EXIT_OK)
        payload = json.loads(out)
        self.assertTrue(payload)
        self.assertTrue(all(item["category"] == "debloat" for item in payload))

    def test_catalog_filters_by_risk(self) -> None:
        code, out = run("catalog", "--catalog", str(CATALOG_DIR), "--risk", "high", "--json")
        self.assertEqual(code, EXIT_OK)
        self.assertTrue(all(item["risk"] == "high" for item in json.loads(out)))

    def test_catalog_after_the_subcommand_also_works(self) -> None:
        """Global options must be accepted on both sides of the command name."""

        first_code, first = run("--catalog", str(CATALOG_DIR), "catalog", "--json")
        second_code, second = run("catalog", "--catalog", str(CATALOG_DIR), "--json")
        self.assertEqual(first_code, EXIT_OK)
        self.assertEqual(json.loads(first), json.loads(second))

    def test_show_prints_the_details(self) -> None:
        code, out = run("show", "--catalog", str(CATALOG_DIR), "force-max-refresh-rate")
        self.assertEqual(code, EXIT_OK)
        self.assertIn("Refresh rate", out)
        self.assertIn("revert   yes", out)

    def test_show_with_commands_prints_adb(self) -> None:
        code, out = run("show", "--catalog", str(CATALOG_DIR), "force-max-refresh-rate", "--commands")
        self.assertEqual(code, EXIT_OK)
        self.assertIn("settings put system peak_refresh_rate 144.0", out)

    def test_show_unknown_tweak(self) -> None:
        code, out = run("show", "--catalog", str(CATALOG_DIR), "nope")
        self.assertEqual(code, EXIT_ERROR)
        self.assertIn("unknown tweak", out)

    def test_profiles_lists_bundles(self) -> None:
        code, out = run("profiles", "--catalog", str(CATALOG_DIR))
        self.assertEqual(code, EXIT_OK)
        self.assertIn("gaming-max", out)
        self.assertIn("battery-max", out)


class ApplyTests(CliTestCase):
    def test_apply_dry_run_prints_commands_and_writes_nothing(self) -> None:
        code, out = run(
            "apply", "--catalog", str(CATALOG_DIR), "force-max-refresh-rate", "--dry-run", "--yes"
        )
        self.assertEqual(code, EXIT_OK, out)
        self.assertIn("DRY RUN", out)
        self.assertIn("settings put system peak_refresh_rate 144.0", out)
        self.assertFalse(self.journal.exists())

    def test_apply_a_whole_profile_dry_run(self) -> None:
        code, out = run("apply", "--catalog", str(CATALOG_DIR), "gaming-max", "--dry-run", "--yes")
        self.assertEqual(code, EXIT_OK, out)
        self.assertIn("refresh-rate-overlay", out)
        self.assertIn("6 tweak(s)", out)

    def test_apply_blocks_experimental_without_the_flag(self) -> None:
        code, out = run("apply", "--catalog", str(CATALOG_DIR), "doze-force-idle", "--yes")
        self.assertEqual(code, EXIT_ERROR)
        self.assertIn("--allow-experimental", out)

    def test_apply_unknown_id(self) -> None:
        code, out = run("apply", "--catalog", str(CATALOG_DIR), "nope", "--dry-run", "--yes")
        self.assertEqual(code, EXIT_ERROR)
        self.assertIn("unknown tweak id", out)

    def test_apply_without_a_tty_and_without_yes_refuses(self) -> None:
        code, out = run("apply", "--catalog", str(CATALOG_DIR), "force-max-refresh-rate")
        self.assertIn(code, (EXIT_ERROR,))
        self.assertIn("refusing to continue", out)

    def test_revert_requires_an_id_or_all(self) -> None:
        code, out = run("revert", "--catalog", str(CATALOG_DIR))
        self.assertEqual(code, EXIT_USAGE)
        self.assertIn("nothing to revert", out)

    def test_revert_all_with_an_empty_journal_is_a_no_op(self) -> None:
        code, out = run("revert", "--catalog", str(CATALOG_DIR), "--all", "--dry-run")
        self.assertEqual(code, EXIT_OK)
        self.assertIn("journal is empty", out)

    def test_revert_unknown_id_reports_and_fails(self) -> None:
        code, out = run("revert", "--catalog", str(CATALOG_DIR), "force-gpu-rendering", "--dry-run")
        self.assertEqual(code, EXIT_ERROR)
        self.assertIn("nothing to revert", out)


class ExportTests(CliTestCase):
    def test_export_to_stdout(self) -> None:
        code, out = run("export", "--catalog", str(CATALOG_DIR), "force-max-refresh-rate")
        self.assertEqual(code, EXIT_OK)
        self.assertIn("#!/usr/bin/env sh", out)
        self.assertIn("--- SNAPSHOT", out)
        self.assertIn("--- APPLY", out)
        self.assertIn("--- REVERT NOTES", out)
        self.assertIn("adb shell settings put system peak_refresh_rate 144.0", out)

    def test_export_to_a_file_with_a_serial(self) -> None:
        target = Path(self._tmp.name) / "apply.sh"
        code, out = run(
            "export",
            "--catalog",
            str(CATALOG_DIR),
            "--serial",
            "ABC123",
            "gaming-max",
            "-o",
            str(target),
        )
        self.assertEqual(code, EXIT_OK, out)
        self.assertTrue(target.exists())
        content = target.read_text(encoding="utf-8")
        self.assertIn("adb -s ABC123 shell", content)
        self.assertIn("device_config put activity_manager max_cached_processes 64", content)

    def test_export_resolves_relative_density_against_a_default(self) -> None:
        """Without a device to read, a delta op still exports a usable command."""

        code, out = run("export", "--catalog", str(CATALOG_DIR), "density-compact")
        self.assertEqual(code, EXIT_OK)
        self.assertIn("wm density 414", out)

    def test_export_powershell(self) -> None:
        code, out = run("export", "--catalog", str(CATALOG_DIR), "trim-caches", "--format", "ps1")
        self.assertEqual(code, EXIT_OK)
        self.assertIn("& adb shell", out)
        self.assertIn("pm trim-caches 999G", out)
        self.assertIn("one-shot action", out)

    def test_export_unknown_id(self) -> None:
        code, out = run("export", "--catalog", str(CATALOG_DIR), "nope")
        self.assertEqual(code, EXIT_ERROR)
        self.assertIn("unknown tweak id", out)


class AwesomeTests(CliTestCase):
    def test_awesome_json(self) -> None:
        code, out = run("awesome", "--catalog", str(CATALOG_DIR), "--json")
        self.assertEqual(code, EXIT_OK)
        payload = json.loads(out)
        self.assertTrue(payload)
        repos = [e["repo"] for section in payload for e in section["entries"]]
        self.assertIn("ewfawfasdf/VivoIQOO144FPSUnlocker", repos)

    def test_awesome_section_filter(self) -> None:
        code, out = run("awesome", "--catalog", str(CATALOG_DIR), "--section", "debloat", "--json")
        self.assertEqual(code, EXIT_OK)
        payload = json.loads(out)
        self.assertEqual([s["section"] for s in payload], ["debloat"])

    def test_awesome_unknown_section(self) -> None:
        code, out = run("awesome", "--catalog", str(CATALOG_DIR), "--section", "nope")
        self.assertEqual(code, EXIT_ERROR)

    def test_awesome_renders_the_list(self) -> None:
        code, out = run("awesome", "--catalog", str(CATALOG_DIR), "--section", "fps-gaming")
        self.assertEqual(code, EXIT_OK)
        self.assertIn("VivoIQOO144FPSUnlocker", out)
        self.assertIn("https://github.com/", out)


class DebloatTests(CliTestCase):
    def test_debloat_dry_run_lists_packages_without_a_device(self) -> None:
        code, out = run("debloat", "--catalog", str(CATALOG_DIR), "--dry-run")
        self.assertEqual(code, EXIT_OK, out)
        self.assertIn("com.vivo.appstore", out)
        self.assertIn("pm disable-user --user 0 com.vivo.browser", out)

    def test_debloat_enable_dry_run(self) -> None:
        code, out = run("debloat", "--catalog", str(CATALOG_DIR), "--dry-run", "--enable")
        self.assertEqual(code, EXIT_OK, out)
        self.assertIn("pm enable", out)
        self.assertNotIn("disable-user --user 0", out)

    def test_debloat_explicit_targets(self) -> None:
        code, out = run("debloat", "--catalog", str(CATALOG_DIR), "--dry-run", "--targets", "com.example.bloat")
        self.assertEqual(code, EXIT_OK, out)
        self.assertIn("com.example.bloat", out)
        self.assertNotIn("com.vivo.appstore", out)


class DoctorTests(CliTestCase):
    def test_doctor_without_adb_explains_what_to_do(self) -> None:
        """Only meaningful when adb is absent; otherwise it is a smoke test."""

        from originos_toolkit.adb import find_adb

        if find_adb(None):
            code, out = run("doctor", "--catalog", str(CATALOG_DIR))
            self.assertIn(code, (EXIT_OK, EXIT_ERROR))
            self.assertIn("adb executable", out)
        else:
            code, out = run("doctor", "--catalog", str(CATALOG_DIR))
            self.assertEqual(code, EXIT_ERROR)
            self.assertIn("Install Android Platform Tools", out)

    def test_missing_catalog_directory(self) -> None:
        code, out = run("catalog", "--catalog", str(Path(self._tmp.name) / "nope"))
        self.assertEqual(code, EXIT_ERROR)
        self.assertIn("catalog", out.lower())


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
