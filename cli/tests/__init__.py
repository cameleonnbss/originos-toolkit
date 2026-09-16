"""Test suite for the OriginOS Toolkit CLI.

The path fix-up below keeps ``python -m unittest``, ``pytest`` and IDE runners
all working from a fresh checkout without installing the package.
"""

from __future__ import annotations

import sys
from pathlib import Path

_CLI_ROOT = Path(__file__).resolve().parents[1]
if str(_CLI_ROOT) not in sys.path:
    sys.path.insert(0, str(_CLI_ROOT))

#: Repository root, used by tests that read the shared catalog.
REPO_ROOT = _CLI_ROOT.parent
CATALOG_DIR = REPO_ROOT / "catalog"
