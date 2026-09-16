package dev.cameleonnbss.originostoolkit

import dev.cameleonnbss.originostoolkit.core.model.Action
import dev.cameleonnbss.originostoolkit.core.ops.Ops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity tests: the same expectations as `cli/tests/test_ops.py`. If the two
 * implementations ever disagree, one of these two suites goes red.
 */
class OpsTest {

    private fun action(op: String, vararg pairs: Pair<String, Any?>): Action =
        Action(op, pairs.toMap())

    // -- command translation ----------------------------------------------

    @Test
    fun `settings put becomes the documented command`() {
        val calls = Ops.shellCalls(
            action("settings_put", "ns" to "system", "key" to "peak_refresh_rate", "value" to "144.0"),
        )
        assertEquals(listOf("settings put system peak_refresh_rate 144.0"), calls.map { it.render() })
    }

    @Test
    fun `pm disable always targets user zero`() {
        val calls = Ops.shellCalls(action("pm_disable", "pkg" to "com.vivo.browser"))
        assertEquals(listOf("pm disable-user --user 0 com.vivo.browser"), calls.map { it.render() })
    }

    @Test
    fun `overlay commands use cmd overlay`() {
        val calls = Ops.shellCalls(action("overlay_enable", "pkg" to "com.example.nav"))
        assertEquals(listOf("cmd overlay enable --user 0 com.example.nav"), calls.map { it.render() })
    }

    @Test
    fun `cmd op passes its args through`() {
        val calls = Ops.shellCalls(action("cmd", "args" to listOf("uimode", "night", "yes")))
        assertEquals(listOf("cmd uimode night yes"), calls.map { it.render() })
    }

    @Test
    fun `raw shell ops are not re-split`() {
        val calls = Ops.shellCalls(action("shell", "command" to "pm trim-caches 999G"))
        assertEquals(listOf("pm trim-caches 999G"), calls.map { it.render() })
    }

    @Test
    fun `svc enable flag is translated`() {
        assertEquals(
            listOf("svc nfc disable"),
            Ops.shellCalls(action("svc", "service" to "nfc", "enable" to false)).map { it.render() },
        )
        assertEquals(
            listOf("svc nfc enable"),
            Ops.shellCalls(action("svc", "service" to "nfc", "enable" to true)).map { it.render() },
        )
    }

    @Test
    fun `density is computed from the previous value`() {
        val calls = Ops.shellCalls(action("wm_density_delta", "percent" to -10), previous = "450")
        assertEquals(listOf("wm density 405"), calls.map { it.render() })
    }

    @Test
    fun `density never becomes unusable`() {
        val calls = Ops.shellCalls(action("wm_density_delta", "percent" to -95), previous = "440")
        assertEquals(listOf("wm density 72"), calls.map { it.render() })
    }

    @Test
    fun `density without a previous value produces nothing`() {
        assertTrue(Ops.shellCalls(action("wm_density_delta", "percent" to -10)).isEmpty())
    }

    @Test
    fun `values with spaces are quoted`() {
        val calls = Ops.shellCalls(
            action("settings_put", "ns" to "system", "key" to "k", "value" to "two words"),
        )
        assertEquals(listOf("settings put system k 'two words'"), calls.map { it.render() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unknown op is rejected`() {
        Ops.shellCalls(action("nuke_everything"))
    }

    // -- probes ------------------------------------------------------------

    @Test
    fun `a write is preceded by a read of the same target`() {
        val probe = Ops.probeFor(action("settings_put", "ns" to "system", "key" to "k", "value" to "1"))
        assertEquals("settings get system k", probe?.render())
    }

    @Test
    fun `fire and forget ops need no probe`() {
        assertNull(Ops.probeFor(action("cmd", "args" to listOf("uimode", "night", "yes"))))
        assertNull(Ops.probeFor(action("shell", "command" to "am kill-all")))
        assertNull(Ops.probeFor(action("svc", "service" to "nfc", "enable" to false)))
    }

    @Test
    fun `settings probes normalise null to empty`() {
        val put = action("settings_put", "ns" to "system", "key" to "k", "value" to "1")
        assertEquals("60.0", Ops.interpretProbe(put, "60.0\n"))
        assertEquals("", Ops.interpretProbe(put, "null\n"))
        assertEquals("", Ops.interpretProbe(put, ""))
    }

    @Test
    fun `density probes prefer the override`() {
        val delta = action("wm_density_delta", "percent" to -10)
        assertEquals("460", Ops.interpretProbe(delta, "Physical density: 460"))
        assertEquals("440", Ops.interpretProbe(delta, "Physical density: 460\nOverride density: 440"))
        assertNull(Ops.interpretProbe(delta, "garbage"))
    }

    @Test
    fun `package probes report the disabled state`() {
        val disable = action("pm_disable", "pkg" to "com.vivo.browser")
        assertEquals("enabled", Ops.interpretProbe(disable, ""))
        assertEquals("disabled", Ops.interpretProbe(disable, "package:com.vivo.browser"))
        assertEquals("enabled", Ops.interpretProbe(disable, "package:com.other"))
    }

    @Test
    fun `overlay probes only look at bracketed state lines`() {
        val enable = action("overlay_enable", "pkg" to "com.x.navbar")
        assertEquals("enabled", Ops.interpretProbe(enable, "[ ] com.x.other\n[x] com.x.navbar"))
        assertEquals("disabled", Ops.interpretProbe(enable, "[x] com.x.other\n[ ] com.x.navbar"))
        assertEquals("absent", Ops.interpretProbe(enable, "[x] com.x.other"))
        // A bare package listing line must not be mistaken for the state line.
        assertEquals(
            "enabled",
            Ops.interpretProbe(enable, "com.x.navbar\n[x] com.x.navbar"),
        )
    }

    // -- inverses ----------------------------------------------------------

    @Test
    fun `a setting is restored to its previous value`() {
        val inverse = Ops.inverseAction(
            action("settings_put", "ns" to "system", "key" to "peak_refresh_rate", "value" to "144.0"),
            "60.0",
        )
        assertEquals("settings_put", inverse?.op)
        assertEquals("60.0", inverse?.settingValue)
        assertEquals("system", inverse?.namespace)
    }

    @Test
    fun `a key that did not exist is deleted again`() {
        val inverse = Ops.inverseAction(
            action("settings_put", "ns" to "system", "key" to "min_refresh_rate", "value" to "144.0"),
            "",
        )
        assertEquals("settings_delete", inverse?.op)
        assertEquals("min_refresh_rate", inverse?.settingKey)
    }

    @Test
    fun `without a probe there is no inverse`() {
        assertNull(
            Ops.inverseAction(action("settings_put", "ns" to "system", "key" to "k", "value" to "1"), null),
        )
    }

    @Test
    fun `an already disabled package is left alone on revert`() {
        assertNull(Ops.inverseAction(action("pm_disable", "pkg" to "com.x"), "disabled"))
        assertNotNull(Ops.inverseAction(action("pm_disable", "pkg" to "com.x"), "enabled"))
    }

    @Test
    fun `an overlay that was already on is left alone`() {
        assertNull(Ops.inverseAction(action("overlay_enable", "pkg" to "com.x"), "enabled"))
        assertEquals(
            "overlay_disable",
            Ops.inverseAction(action("overlay_enable", "pkg" to "com.x"), "absent")?.op,
        )
    }

    @Test
    fun `svc inverse flips the flag`() {
        val inverse = Ops.inverseAction(action("svc", "service" to "nfc", "enable" to false), null)
        assertEquals(true, inverse?.bool("enable"))
    }

    @Test
    fun `density inverse restores the exact value`() {
        val inverse = Ops.inverseAction(action("wm_density_delta", "percent" to -10), "460")
        assertEquals("wm_density_set", inverse?.op)
        assertEquals("460", inverse?.string("value"))
    }

    @Test
    fun `one shot ops have no inverse`() {
        assertNull(Ops.inverseAction(action("shell", "command" to "am kill-all"), null))
        assertNull(Ops.inverseAction(action("cmd", "args" to listOf("uimode", "night", "yes")), null))
    }

    @Test
    fun `actions survive a journal round trip`() {
        val original = action(
            "settings_put",
            "ns" to "system",
            "key" to "peak_refresh_rate",
            "value" to "144.0",
        )
        val restored = Action.fromJson(original.toJson())
        assertEquals(original.op, restored.op)
        assertEquals(original.target, restored.target)
        assertEquals(original.settingValue, restored.settingValue)
    }
}
