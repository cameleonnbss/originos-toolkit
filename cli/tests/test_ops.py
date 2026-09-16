"""Tests for the op layer: command translation, probes and inverses."""

from __future__ import annotations

import unittest

from originos_toolkit.catalog import Action
from originos_toolkit.ops import (
    interpret_probe,
    inverse_action,
    probe_for,
    shell_calls,
)


class ShellCallTests(unittest.TestCase):
    def test_settings_put(self) -> None:
        calls = shell_calls(Action("settings_put", {"ns": "system", "key": "peak_refresh_rate", "value": "144.0"}))
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0].argv, ("settings", "put", "system", "peak_refresh_rate", "144.0"))

    def test_settings_delete(self) -> None:
        calls = shell_calls(Action("settings_delete", {"ns": "secure", "key": "foo"}))
        self.assertEqual(calls[0].argv, ("settings", "delete", "secure", "foo"))

    def test_device_config_put(self) -> None:
        calls = shell_calls(Action("device_config_put", {"ns": "activity_manager", "key": "k", "value": "64"}))
        self.assertEqual(calls[0].argv, ("device_config", "put", "activity_manager", "k", "64"))

    def test_pm_disable_always_targets_user_zero(self) -> None:
        calls = shell_calls(Action("pm_disable", {"pkg": "com.vivo.browser"}))
        self.assertEqual(calls[0].argv, ("pm", "disable-user", "--user", "0", "com.vivo.browser"))

    def test_overlay_enable(self) -> None:
        calls = shell_calls(Action("overlay_enable", {"pkg": "com.example.overlay"}))
        self.assertEqual(calls[0].argv, ("cmd", "overlay", "enable", "--user", "0", "com.example.overlay"))

    def test_svc_disable_and_enable(self) -> None:
        self.assertEqual(shell_calls(Action("svc", {"service": "nfc", "enable": False}))[0].argv, ("svc", "nfc", "disable"))
        self.assertEqual(shell_calls(Action("svc", {"service": "nfc", "enable": True}))[0].argv, ("svc", "nfc", "enable"))

    def test_cmd_op(self) -> None:
        calls = shell_calls(Action("cmd", {"args": ["uimode", "night", "yes"]}))
        self.assertEqual(calls[0].argv, ("cmd", "uimode", "night", "yes"))

    def test_raw_shell_op_is_not_split(self) -> None:
        calls = shell_calls(Action("shell", {"command": "pm trim-caches 999G"}))
        self.assertEqual(calls[0].raw, "pm trim-caches 999G")
        self.assertEqual(calls[0].render(), "pm trim-caches 999G")

    def test_density_delta_computes_from_previous(self) -> None:
        calls = shell_calls(Action("wm_density_delta", {"percent": -10}), previous="460")
        self.assertEqual(calls[0].argv, ("wm", "density", "414"))

    def test_density_delta_rounds_and_floors(self) -> None:
        self.assertEqual(shell_calls(Action("wm_density_delta", {"percent": -10}), previous="450")[0].argv[2], "405")
        # A hostile percentage must never produce an unusable density.
        self.assertEqual(shell_calls(Action("wm_density_delta", {"percent": -95}), previous="440")[0].argv[2], "72")

    def test_density_delta_without_previous_is_a_no_op(self) -> None:
        self.assertEqual(shell_calls(Action("wm_density_delta", {"percent": -10})), [])

    def test_unknown_op_raises(self) -> None:
        with self.assertRaises(ValueError):
            shell_calls(Action("nuke_everything", {}))


class ProbeTests(unittest.TestCase):
    def test_settings_put_probes_current_value(self) -> None:
        probe = probe_for(Action("settings_put", {"ns": "system", "key": "k", "value": "1"}))
        self.assertEqual(probe.argv, ("settings", "get", "system", "k"))

    def test_read_only_ops_need_no_probe(self) -> None:
        self.assertIsNone(probe_for(Action("cmd", {"args": ["uimode", "night", "yes"]})))
        self.assertIsNone(probe_for(Action("shell", {"command": "am kill-all"})))
        self.assertIsNone(probe_for(Action("svc", {"service": "nfc", "enable": False})))

    def test_probe_parses_settings_values(self) -> None:
        action = Action("settings_put", {"ns": "system", "key": "k", "value": "1"})
        self.assertEqual(interpret_probe(action, "60.0\n"), "60.0")
        self.assertEqual(interpret_probe(action, "null\n"), "")
        self.assertEqual(interpret_probe(action, ""), "")

    def test_probe_parses_density(self) -> None:
        action = Action("wm_density_delta", {"percent": -10})
        self.assertEqual(interpret_probe(action, "Physical density: 460\n"), "460")
        self.assertEqual(interpret_probe(action, "Physical density: 460\nOverride density: 440\n"), "440")
        self.assertIsNone(interpret_probe(action, "garbage"))

    def test_probe_parses_package_state(self) -> None:
        action = Action("pm_disable", {"pkg": "com.vivo.browser"})
        self.assertEqual(interpret_probe(action, ""), "enabled")
        self.assertEqual(interpret_probe(action, "package:com.vivo.browser\n"), "disabled")
        self.assertEqual(interpret_probe(action, "package:com.other\n"), "enabled")

    def test_probe_parses_overlay_state(self) -> None:
        action = Action("overlay_enable", {"pkg": "com.x.navbar"})
        listing = "[ ] com.x.other\n[x] com.x.navbar\n"
        self.assertEqual(interpret_probe(action, listing), "enabled")
        listing = "[x] com.x.other\n[ ] com.x.navbar\n"
        self.assertEqual(interpret_probe(action, listing), "disabled")
        self.assertEqual(interpret_probe(action, "[x] com.x.other\n"), "absent")


class InverseTests(unittest.TestCase):
    def test_settings_put_restores_the_previous_value(self) -> None:
        action = Action("settings_put", {"ns": "system", "key": "peak_refresh_rate", "value": "144.0"})
        inverse = inverse_action(action, "60.0")
        self.assertEqual(inverse.op, "settings_put")
        self.assertEqual(inverse.get("value"), "60.0")
        self.assertEqual(inverse.get("ns"), "system")

    def test_settings_put_deletes_a_key_that_did_not_exist(self) -> None:
        action = Action("settings_put", {"ns": "system", "key": "min_refresh_rate", "value": "144.0"})
        inverse = inverse_action(action, "")
        self.assertEqual(inverse.op, "settings_delete")
        self.assertEqual(inverse.get("key"), "min_refresh_rate")

    def test_settings_put_without_a_probe_is_not_invertible(self) -> None:
        action = Action("settings_put", {"ns": "system", "key": "k", "value": "1"})
        self.assertIsNone(inverse_action(action, None))

    def test_pm_disable_inverse_is_pm_enable(self) -> None:
        inverse = inverse_action(Action("pm_disable", {"pkg": "com.vivo.browser"}), "enabled")
        self.assertEqual(inverse.op, "pm_enable")
        self.assertEqual(inverse.get("pkg"), "com.vivo.browser")

    def test_already_disabled_package_is_left_alone(self) -> None:
        """Reverting must not enable something the user had already disabled."""

        self.assertIsNone(inverse_action(Action("pm_disable", {"pkg": "com.x"}), "disabled"))

    def test_already_enabled_overlay_is_left_alone(self) -> None:
        self.assertIsNone(inverse_action(Action("overlay_enable", {"pkg": "com.x"}), "enabled"))
        self.assertEqual(inverse_action(Action("overlay_enable", {"pkg": "com.x"}), "absent").op, "overlay_disable")

    def test_svc_inverse_flips_the_flag(self) -> None:
        inverse = inverse_action(Action("svc", {"service": "nfc", "enable": False}), None)
        self.assertTrue(inverse.get("enable"))

    def test_density_inverse_restores_the_exact_value(self) -> None:
        inverse = inverse_action(Action("wm_density_delta", {"percent": -10}), "460")
        self.assertEqual(inverse.op, "wm_density_set")
        self.assertEqual(inverse.get("value"), "460")

    def test_one_shot_ops_have_no_inverse(self) -> None:
        self.assertIsNone(inverse_action(Action("shell", {"command": "am kill-all"}), None))
        self.assertIsNone(inverse_action(Action("cmd", {"args": ["uimode", "night", "yes"]}), None))

    def test_device_config_round_trip(self) -> None:
        action = Action("device_config_put", {"ns": "activity_manager", "key": "max_cached_processes", "value": "64"})
        self.assertEqual(inverse_action(action, "32").get("value"), "32")
        self.assertEqual(inverse_action(action, "").op, "device_config_delete")


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
