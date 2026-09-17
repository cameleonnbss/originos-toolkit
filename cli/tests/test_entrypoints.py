"""Tests that the documented ways to *start* the CLI actually start it.

This file exists because of a real bug: `cli.py` had no `if __name__ ==
"__main__"` block, so `python -m originos_toolkit.cli <anything>` exited 0 and
printed nothing. Every wrapper around it — the Makefile targets, the CI smoke
test — looked like it passed while doing nothing. Importing `main()` in a unit
test cannot catch that, only spawning the process can.
"""

from __future__ import annotations

import os
import subprocess
import sys
import unittest
from pathlib import Path

from . import CATALOG_DIR, REPO_ROOT


def run_cli(*argv: str) -> subprocess.CompletedProcess:
    env = dict(os.environ)
    env["PYTHONPATH"] = str(REPO_ROOT / "cli")
    env["PYTHONIOENCODING"] = "utf-8"
    return subprocess.run(
        [sys.executable, *argv],
        capture_output=True,
        text=True,
        cwd=str(REPO_ROOT),
        env=env,
        timeout=60,
    )


class EntryPointTests(unittest.TestCase):
    def test_dash_m_cli_prints_the_version(self) -> None:
        """`python -m originos_toolkit.cli` — the form the docs and CI use."""

        result = run_cli("-m", "originos_toolkit.cli", "--version")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("originos-toolkit", result.stdout)

    def test_dash_m_package_prints_the_version(self) -> None:
        """`python -m originos_toolkit` — the form the CLI README prefers."""

        result = run_cli("-m", "originos_toolkit", "--version")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("originos-toolkit", result.stdout)

    def test_lint_actually_produces_output(self) -> None:
        result = run_cli(
            "-m", "originos_toolkit.cli", "lint", "--catalog", str(CATALOG_DIR)
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("no problems found", result.stdout)

    def test_catalog_lists_tweaks_instead_of_silence(self) -> None:
        result = run_cli(
            "-m", "originos_toolkit.cli", "catalog", "--catalog", str(CATALOG_DIR)
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("force-max-refresh-rate", result.stdout)
        self.assertGreater(len(result.stdout), 300, "the table should not be empty")

    def test_no_shizuku_filter_works_from_the_command_line(self) -> None:
        result = run_cli(
            "-m",
            "originos_toolkit.cli",
            "catalog",
            "--catalog",
            str(CATALOG_DIR),
            "--no-shizuku",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("force-max-refresh-rate", result.stdout)
        # A ui/pm tweak cannot run in-process, so it must not be listed.
        self.assertNotIn("dark-mode-always", result.stdout)
        self.assertIn("need no Shizuku", result.stdout)

    def test_an_unknown_tweak_fails_loudly(self) -> None:
        result = run_cli(
            "-m", "originos_toolkit.cli", "show", "--catalog", str(CATALOG_DIR), "nope"
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unknown tweak", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
