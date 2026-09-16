"""Tests for profile (bundle) resolution."""

from __future__ import annotations

import unittest

from . import CATALOG_DIR
from originos_toolkit.catalog import load_catalog
from originos_toolkit.profiles import UnknownReference, is_profile, resolve_ids, summarise


class ProfileTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog = load_catalog(str(CATALOG_DIR))

    def test_is_profile(self) -> None:
        self.assertTrue(is_profile(self.catalog, "gaming-max"))
        self.assertFalse(is_profile(self.catalog, "force-max-refresh-rate"))

    def test_expand_a_profile(self) -> None:
        tweaks = resolve_ids(self.catalog, ["battery-max"])
        ids = [t.id for t in tweaks]
        self.assertEqual(ids, self.catalog.profile("battery-max").tweaks)

    def test_mix_profile_and_tweak_ids(self) -> None:
        tweaks = resolve_ids(self.catalog, ["battery-max", "force-gpu-rendering"])
        self.assertIn("force-gpu-rendering", [t.id for t in tweaks])

    def test_duplicates_are_collapsed(self) -> None:
        tweaks = resolve_ids(self.catalog, ["battery-max", "disable-nfc", "daily-balanced"])
        ids = [t.id for t in tweaks]
        self.assertEqual(len(ids), len(set(ids)))

    def test_order_follows_the_request(self) -> None:
        tweaks = resolve_ids(self.catalog, ["disable-nfc", "force-gpu-rendering"])
        self.assertEqual([t.id for t in tweaks], ["disable-nfc", "force-gpu-rendering"])

    def test_unknown_id_raises_with_the_offender(self) -> None:
        with self.assertRaises(UnknownReference) as ctx:
            resolve_ids(self.catalog, ["gaming-max", "totally-made-up"])
        self.assertEqual(ctx.exception.reference, "totally-made-up")

    def test_profile_inside_a_profile_is_not_supported(self) -> None:
        """Profiles may only reference tweaks; the lint enforces it."""

        known = {t.id for t in self.catalog.tweaks}
        for profile in self.catalog.profiles:
            for reference in profile.tweaks:
                self.assertIn(reference, known)

    def test_summarise_counts_medium_risk(self) -> None:
        text = summarise(self.catalog.profile("gaming-max"), self.catalog)
        self.assertIn("6 tweaks", text)
        self.assertIn("medium-risk", text)

    def test_summarise_omits_risk_noise_for_safe_profiles(self) -> None:
        text = summarise(self.catalog.profile("daily-balanced"), self.catalog)
        self.assertNotIn("high-risk", text)
        self.assertNotIn("medium-risk", text)

    def test_no_profile_contains_a_high_risk_tweak(self) -> None:
        """Profiles are one-click; they must never bundle something dangerous."""

        for profile in self.catalog.profiles:
            for tweak_id in profile.tweaks:
                self.assertNotEqual(
                    self.catalog.tweak(tweak_id).risk,
                    "high",
                    f"{profile.id} bundles the high-risk tweak {tweak_id}",
                )


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
