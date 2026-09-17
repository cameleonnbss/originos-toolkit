package dev.cameleonnbss.originostoolkit

import dev.cameleonnbss.originostoolkit.core.EngineException
import dev.cameleonnbss.originostoolkit.core.JournalEntry
import dev.cameleonnbss.originostoolkit.core.JournalStore
import dev.cameleonnbss.originostoolkit.core.TweakEngine
import dev.cameleonnbss.originostoolkit.core.model.Tweak
import dev.cameleonnbss.originostoolkit.core.ops.AccessLevel
import dev.cameleonnbss.originostoolkit.core.ops.ShellCall
import dev.cameleonnbss.originostoolkit.core.shell.RunnerProvider
import dev.cameleonnbss.originostoolkit.core.shell.ShizukuState
import dev.cameleonnbss.originostoolkit.core.shell.ShellResult
import dev.cameleonnbss.originostoolkit.core.shell.ShellRunner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records commands and answers from a map of prefixes. */
private class FakeShell(
    private val answers: Map<String, String> = emptyMap(),
    val failures: MutableSet<String> = mutableSetOf(),
    private val onWrite: (String) -> Unit = {},
    override val level: AccessLevel = AccessLevel.SHELL,
    override val canWrite: Boolean = true,
) : ShellRunner {
    val calls = mutableListOf<String>()

    override val label = "fake"
    override fun isAvailable() = true

    override fun exec(call: ShellCall): ShellResult {
        val command = call.render()
        calls += command
        if (command.startsWith("settings put") || command.startsWith("pm disable")) {
            onWrite(command)
        }
        answers.forEach { (prefix, output) ->
            if (command.startsWith(prefix)) return ShellResult(command, stdout = output)
        }
        failures.firstOrNull { command.contains(it) }?.let {
            return ShellResult(command, stderr = "simulated failure", code = 1)
        }
        return ShellResult(command)
    }
}

private class FakeProvider(private val runner: ShellRunner) : RunnerProvider {
    override val active: ShellRunner get() = runner
    override val state: ShizukuState = ShizukuState(binderAlive = true, permissionGranted = true)
}

private class InMemoryJournalStore : JournalStore {
    var entries: List<JournalEntry> = emptyList()
        private set

    override fun load(): List<JournalEntry> = entries
    override fun save(entries: List<JournalEntry>) {
        this.entries = entries
    }
}

class TweakEngineTest {

    private lateinit var store: InMemoryJournalStore

    private fun setup(answers: Map<String, String> = emptyMap(), onWrite: (String) -> Unit = {}) =
        FakeShell(answers, onWrite = onWrite).let { shell ->
            store = InMemoryJournalStore()
            Triple(shell, TweakEngine(FakeProvider(shell), store), store)
        }

    private fun parseTweak(body: String): Tweak = Tweak.fromJson(JSONObject(body))

    private val refreshTweak = parseTweak(
        """
        {
          "id": "force-max-refresh-rate",
          "name": "Force maximum refresh rate",
          "category": "display",
          "summary": "Pins the refresh rate.",
          "details": "Writes both settings.",
          "risk": "low",
          "requires": "shizuku",
          "originOs": ["originos4"],
          "actions": [
            {"op": "settings_put", "ns": "system", "key": "peak_refresh_rate", "value": "144.0"},
            {"op": "settings_put", "ns": "system", "key": "min_refresh_rate", "value": "144.0"}
          ]
        }
        """,
    )

    private val overlayTweak = parseTweak(
        """
        {
          "id": "gesture-navigation",
          "name": "Gesture navigation",
          "category": "input",
          "summary": "Switches the navbar overlay.",
          "details": "Uses RRO overlays.",
          "risk": "medium",
          "requires": "shizuku",
          "originOs": ["originos4"],
          "actions": [
            {"op": "overlay_enable", "pkg": "nav.gestural"},
            {"op": "overlay_disable", "pkg": "nav.threebutton"}
          ],
          "revert": [
            {"op": "overlay_enable", "pkg": "nav.threebutton"},
            {"op": "overlay_disable", "pkg": "nav.gestural"}
          ]
        }
        """,
    )

    @Test
    fun `apply runs the expected commands`() {
        val (shell, engine, _) = setup(
            mapOf(
                "settings get system peak_refresh_rate" to "60.0\n",
                "settings get system min_refresh_rate" to "null\n",
            ),
        )
        val result = engine.apply(refreshTweak)

        assertTrue(result.errors.toString(), result.ok)
        assertTrue(shell.calls.contains("settings put system peak_refresh_rate 144.0"))
        assertTrue(shell.calls.contains("settings put system min_refresh_rate 144.0"))
    }

    @Test
    fun `the journal holds the exact inverse`() {
        val (_, engine, _) = setup(
            mapOf(
                "settings get system peak_refresh_rate" to "60.0\n",
                "settings get system min_refresh_rate" to "null\n",
            ),
        )
        engine.apply(refreshTweak)

        val entry = engine.journal.first { it.tweakId == refreshTweak.id }
        val restored = entry.inverse.map { Triple(it.op, it.settingKey, it.settingValue) }
        assertTrue(restored.contains(Triple("settings_put", "peak_refresh_rate", "60.0")))
        assertTrue(restored.contains(Triple("settings_delete", "min_refresh_rate", null)))
    }

    @Test
    fun `the journal is written before any write reaches the device`() {
        var journalWasReady = true
        val (_, engine, _) = setup(
            mapOf(
                "settings get system peak_refresh_rate" to "60.0\n",
                "settings get system min_refresh_rate" to "null\n",
            ),
            onWrite = { journalWasReady = store.entries.isNotEmpty() },
        )
        engine.apply(refreshTweak)
        assertTrue("the inverse must be on disk before the first write", journalWasReady)
    }

    @Test
    fun `re-applying is refused so the revert stays correct`() {
        val (shell, engine, _) = setup(
            mapOf(
                "settings get system peak_refresh_rate" to "60.0\n",
                "settings get system min_refresh_rate" to "null\n",
            ),
        )
        engine.apply(refreshTweak)
        val before = shell.calls.size

        val again = engine.apply(refreshTweak)
        assertEquals(before, shell.calls.size)
        assertTrue(again.skipped.any { it.contains("already applied") })
        assertEquals(
            "60.0",
            engine.journal.first { it.tweakId == refreshTweak.id }
                .inverse.first { it.settingKey == "peak_refresh_rate" }.settingValue,
        )
    }

    @Test
    fun `force re-applies`() {
        val (shell, engine, _) = setup(
            mapOf(
                "settings get system peak_refresh_rate" to "60.0\n",
                "settings get system min_refresh_rate" to "null\n",
            ),
        )
        engine.apply(refreshTweak)
        engine.apply(refreshTweak, force = true)
        assertEquals(2, shell.calls.count { it == "settings put system peak_refresh_rate 144.0" })
    }

    @Test
    fun `a failed probe aborts before writing`() {
        val (shell, engine, _) = setup()
        shell.failures += "settings get"
        var threw = false
        try {
            engine.apply(refreshTweak)
        } catch (_: EngineException) {
            threw = true
        }
        assertTrue(threw)
        assertFalse(shell.calls.any { it.startsWith("settings put") })
    }

    @Test
    fun `dry run touches nothing and writes no journal`() {
        val (shell, engine, _) = setup()
        engine.dryRun = true
        val result = engine.apply(refreshTweak)

        assertTrue(result.dryRun)
        assertTrue(shell.calls.isEmpty())
        assertTrue(store.entries.isEmpty())
        assertEquals(2, result.steps.size)
    }

    @Test
    fun `revert restores the previous values`() {
        val (shell, engine, _) = setup(
            mapOf(
                "settings get system peak_refresh_rate" to "60.0\n",
                "settings get system min_refresh_rate" to "null\n",
            ),
        )
        engine.apply(refreshTweak)
        shell.calls.clear()

        val result = engine.revert(refreshTweak, refreshTweak.id)
        assertTrue(result.errors.toString(), result.ok)
        assertTrue(shell.calls.contains("settings put system peak_refresh_rate 60.0"))
        assertTrue(shell.calls.contains("settings delete system min_refresh_rate"))
        assertTrue(engine.journal.isEmpty())
    }

    @Test
    fun `revert falls back to an explicit revert list`() {
        val (shell, engine, _) = setup()
        val result = engine.revert(overlayTweak, overlayTweak.id)

        assertTrue(result.ok)
        assertTrue(shell.calls.contains("cmd overlay enable --user 0 nav.threebutton"))
        assertTrue(shell.calls.contains("cmd overlay disable --user 0 nav.gestural"))
    }

    @Test
    fun `revert without a journal or explicit list fails loudly`() {
        val (_, engine, _) = setup()
        var threw = false
        try {
            engine.revert(refreshTweak, refreshTweak.id)
        } catch (_: EngineException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `revert all empties the journal`() {
        val (_, engine, _) = setup(
            mapOf(
                "settings get system peak_refresh_rate" to "60.0\n",
                "settings get system min_refresh_rate" to "null\n",
            ),
        )
        engine.apply(refreshTweak)
        assertEquals(1, engine.journal.size)

        engine.revertAll()
        assertTrue(engine.journal.isEmpty())
    }

    @Test
    fun `one shot actions apply without journaling`() {
        val oneShot = parseTweak(
            """
            {
              "id": "trim-caches",
              "name": "Trim caches",
              "category": "system",
              "summary": "Frees storage.",
              "details": "One shot.",
              "risk": "low",
              "requires": "shizuku",
              "reversible": false,
              "originOs": ["originos4"],
              "actions": [{"op": "shell", "command": "pm trim-caches 999G"}]
            }
            """,
        )
        val (shell, engine, _) = setup()
        val result = engine.apply(oneShot)

        assertTrue(result.ok)
        assertEquals(listOf("pm trim-caches 999G"), shell.calls)
        assertTrue(engine.journal.isEmpty())
    }

    @Test
    fun `status detects an applied and a reverted tweak`() {
        val (_, engine, _) = setup(
            mapOf(
                "settings get system peak_refresh_rate" to "144.0\n",
                "settings get system min_refresh_rate" to "144.0\n",
            ),
        )
        assertEquals(true, engine.status(refreshTweak))

        val (_, offEngine, _) = setup(
            mapOf(
                "settings get system peak_refresh_rate" to "60.0\n",
                "settings get system min_refresh_rate" to "60.0\n",
            ),
        )
        assertEquals(false, offEngine.status(refreshTweak))
    }

    @Test
    fun `status is unknown for one shot actions`() {
        val oneShot = parseTweak(
            """
            {
              "id": "kill-background-apps",
              "name": "Kill background apps",
              "category": "experimental",
              "summary": "Frees RAM.",
              "details": "One shot.",
              "risk": "medium",
              "requires": "shizuku",
              "reversible": false,
              "originOs": ["originos4"],
              "actions": [{"op": "shell", "command": "am kill-all"}]
            }
            """,
        )
        val (_, engine, _) = setup()
        assertNull(engine.status(oneShot))
    }

    @Test
    fun `an already disabled package is not re-enabled on revert`() {
        val debloat = parseTweak(
            """
            {
              "id": "debloat-vivo",
              "name": "Debloat Vivo",
              "category": "debloat",
              "summary": "Disables two packages.",
              "details": "Reversible.",
              "risk": "medium",
              "requires": "shizuku",
              "originOs": ["originos4"],
              "actions": [
                {"op": "pm_disable", "pkg": "com.vivo.appstore"},
                {"op": "pm_disable", "pkg": "com.vivo.browser"}
              ]
            }
            """,
        )
        val (_, engine, _) = setup(
            mapOf(
                "pm list packages -d com.vivo.appstore" to "",
                "pm list packages -d com.vivo.browser" to "package:com.vivo.browser\n",
            ),
        )
        engine.apply(debloat)

        val inverses = engine.journal.first().inverse.mapNotNull { it.packageName }
        assertTrue(inverses.contains("com.vivo.appstore"))
        assertFalse("a pre-disabled package must be left alone", inverses.contains("com.vivo.browser"))
    }

    @Test
    fun `panic reset restores display metrics then reverts`() {
        val (shell, engine, _) = setup(
            mapOf(
                "settings get system peak_refresh_rate" to "60.0\n",
                "settings get system min_refresh_rate" to "null\n",
            ),
        )
        engine.apply(refreshTweak)
        val results = engine.panicReset()

        assertTrue(shell.calls.contains("wm density reset"))
        assertTrue(shell.calls.contains("wm size reset"))
        assertEquals(2, results.size)
        assertTrue(engine.journal.isEmpty())
    }

    @Test
    fun `plan previews without executing`() {
        val (shell, engine, _) = setup(
            mapOf(
                "settings get system peak_refresh_rate" to "60.0\n",
                "settings get system min_refresh_rate" to "null\n",
            ),
        )
        val plan = engine.plan(refreshTweak)
        assertEquals(
            listOf(
                "settings put system peak_refresh_rate 144.0",
                "settings put system min_refresh_rate 144.0",
            ),
            plan.map { it.render() },
        )
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun `a failed write is reported and the journal is kept`() {
        val (shell, engine, _) = setup(
            mapOf(
                "settings get system peak_refresh_rate" to "60.0\n",
                "settings get system min_refresh_rate" to "null\n",
            ),
        )
        shell.failures += "settings put"
        val result = engine.apply(refreshTweak)

        assertFalse(result.ok)
        assertTrue(result.errors.isNotEmpty())
        assertNotNull(engine.journal.firstOrNull { it.tweakId == refreshTweak.id })
    }
}
