"""OriginOS Toolkit — a no-root tweak engine for Vivo/iQOO OriginOS devices.

The Python package is the reference implementation of the tweak engine that the
Android app also ships. It talks to a device through ``adb`` (USB or wireless),
or simply prints the commands it *would* run so you can paste them yourself.
"""

from __future__ import annotations

__all__ = ["__version__", "CATALOG_VERSION"]

__version__ = "1.0.0"

#: Highest ``catalogVersion`` this build understands.
CATALOG_VERSION = 1
