package dev.cameleonnbss.originostoolkit

import dev.cameleonnbss.originostoolkit.core.JournalEntry
import dev.cameleonnbss.originostoolkit.core.JournalStore
import dev.cameleonnbss.originostoolkit.core.TweakEngine
import dev.cameleonnbss.originostoolkit.core.model.Action
import dev.cameleonnbss.originostoolkit.core.model.Catalog
import dev.cameleonnbss.originostoolkit.core.model.CatalogParser
import dev.cameleonnbss.originostoolkit.core.model.Tweak
import dev.cameleonnbss.originostoolkit.core.ops.Access
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
import java.io.File

/**
 * The promise this project makes everywhere — "these tweaks need no Shizuku" —
 * has to be true in the code that decides whether to run them.
 */
class AccessTest {

    private val catalog: Catalog = run {
        val dir = requireNotNull(System.getProperty("originos.catalog.dir"))
        CatalogParser.parse(
            tweaksDoc = JSONObject(File(dir, "tweaks.json").readText()),
            profilesDoc = JSONObject(File(dir, "profiles.json").readText()),
            awesomeDoc = JSONObject(File(dir, "awesome.json").readText()),
        )
    }

    private fun action(op: String, vararg pairs: Pair<String, Any?>): Action =
        Action(op, pairs.toMap())

    // -- the classifier ----------------------------------------------------

    @Test
    fun `system namespace writes need only the settings grant`() {
        listOf("settings_put", "settings_delete").forEach { op ->
            val need = Access.of(action(op, "ns" to "system", "key" to "peak_refresh_rate", "value" to "144.0"))
            assertEquals("$op should be writable in-process", AccessLevel.SETTINGS, need)
        }
    }

    @Test
    fun `reading a system setting needs no grant at all`() {
        // The app reads the System namespace through its own resolver, which is
        // why the dashboard can show values before anything is granted.
        val need = Access.of(action("settings_get", "ns" to "system", "key" to "peak_refresh_rate"))
        assertEquals(AccessLevel.NONE, need)
    }

    @Test
    fun `secure and global namespaces need the shell user`() {
        listOf("secure", "global").forEach { ns ->
            listOf("settings_put", "settings_get").forEach { op ->
                val need = Access.of(action(op, "ns" to ns, "key" to "k", "value" to "1"))
                assertEquals("$ns is not app-accessible in-process", AccessLevel.SHELL, need)
            }
        }
    }

    @Test
    fun `shell ops need the shell user`() {
        val shellOps = listOf(
            action("pm_disable", "pkg" to "com.example"),
            action("device_config_put", "ns" to "activity_manager", "key" to "k", "value" to "1"),
            action("wm_density_set", "value" to "420"),
            action("svc", "service" to "nfc", "enable" to false),
            action("overlay_enable", "pkg" to "com.example.overlay"),
        )
        shellOps.forEach { assertEquals(it.op, AccessLevel.SHELL, Access.of(it)) }
    }

    @Test
    fun `the revert side counts`() {
        val tweak = fakeTweak(
            actions = listOf(action("settings_put", "ns" to "system", "key" to "k", "value" to "1")),
            revert = listOf(action("settings_put", "ns" to "global", "key" to "k", "value" to "0")),
        )
        assertEquals(AccessLevel.SHELL, Access.of(tweak))
        assertFalse(Access.runsWithoutShizuku(tweak))
    }

    @Test
    fun `level covers is a strength comparison`() {
        assertTrue(AccessLevel.SHELL.covers(AccessLevel.SETTINGS))
        assertTrue(AccessLevel.SETTINGS.covers(AccessLevel.NONE))
        assertFalse(AccessLevel.SETTINGS.covers(AccessLevel.SHELL))
        assertFalse(AccessLevel.NONE.covers(AccessLevel.SETTINGS))
    }

    // -- the real catalog --------------------------------------------------

    @Test
    fun `the catalog declares requirements that match its actions`() {
        val lying = catalog.tweaks.filter { tweak ->
            when (Access.of(tweak)) {
                AccessLevel.SETTINGS -> tweak.requires.id != "settings"
                AccessLevel.SHELL -> tweak.requires.id == "settings"
                AccessLevel.NONE -> false
            }
        }
        assertTrue(
            "these tweaks declare an access level their actions contradict: " +
                lying.joinToString { "${it.id}=${it.requires.id}" },
            lying.isEmpty(),
        )
    }

    @Test
    fun `forcing the refresh rate works without Shizuku`() {
        val tweak = catalog.tweak("force-max-refresh-rate")
        assertNotNull(tweak)
        assertTrue(Access.runsWithoutShizuku(tweak!!))
        assertEquals("settings", tweak.requires.id)
    }

    @Test
    fun `several tweaks work without Shizuku`() {
        val usable = catalog.tweaks.filter { Access.runsWithoutShizuku(it) }
        assertTrue("expected at least 8, got ${usable.size}", usable.size >= 8)
    }

    @Test
    fun `no tweak claims settings access while needing the shell`() {
        catalog.tweaks.filter { it.requires.id == "settings" }.forEach { tweak ->
            assertEquals(
                "${tweak.id} claims settings-only but needs more",
                AccessLevel.SETTINGS,
                Access.of(tweak),
            )
        }
    }

    // -- the engine refuses honestly ---------------------------------------

    private class StaticShell(
        override val level: AccessLevel,
        override val canWrite: Boolean,
    ) : ShellRunner {
        override val label = "test"
        override fun isAvailable() = true
        override fun exec(call: ShellCall): ShellResult = ShellResult(call.render())
    }

    private class Provider(private val runner: ShellRunner) : RunnerProvider {
        override val active: ShellRunner get() = runner
        override val state = ShizukuState()
    }

    private object EmptyJournal : JournalStore {
        override fun load(): List<JournalEntry> = emptyList()
        override fun save(entries: List<JournalEntry>) = Unit
    }

    private fun engineWith(runner: ShellRunner) = TweakEngine(Provider(runner), EmptyJournal)

    private fun fakeTweak(
        actions: List<Action>,
        revert: List<Action> = emptyList(),
    ): Tweak = Tweak(
        id = "sample",
        name = "Sample",
        category = "display",
        summary = "for tests",
        details = "",
        risk = dev.cameleonnbss.originostoolkit.core.model.Risk.LOW,
        requires = dev.cameleonnbss.originostoolkit.core.model.Requirement.SETTINGS,
        verified = true,
        declaredOneShot = false,
        originOs = listOf("originos5"),
        actions = actions,
        revert = revert,
        verify = emptyList(),
    )

    @Test
    fun `a settings-only runner can run a system-namespace tweak`() {
        val engine = engineWith(StaticShell(AccessLevel.SETTINGS, canWrite = true))
        val tweak = fakeTweak(
            listOf(action("settings_put", "ns" to "system", "key" to "k", "value" to "1")),
        )
        assertNull(engine.refusalFor(tweak))
        assertTrue(engine.canRun(tweak))
    }

    @Test
    fun `a settings-only runner refuses a shell tweak instead of half applying it`() {
        val engine = engineWith(StaticShell(AccessLevel.SETTINGS, canWrite = true))
        // One system write plus one global write: the interesting case, because
        // running it partially would leave the journal holding an inverse that
        // can never be executed.
        val tweak = fakeTweak(
            listOf(
                action("settings_put", "ns" to "system", "key" to "a", "value" to "1"),
                action("settings_put", "ns" to "global", "key" to "b", "value" to "1"),
            ),
        )
        val refusal = engine.refusalFor(tweak)
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("shell user"))
        assertFalse(engine.canRun(tweak))

        val result = engine.apply(tweak)
        assertFalse(result.ok)
        assertEquals("nothing should have been executed", 0, result.steps.size)
        assertTrue(result.errors.first().contains("shell user"))
    }

    @Test
    fun `a write attempt without the grant is refused with the exact next step`() {
        val engine = engineWith(StaticShell(AccessLevel.SETTINGS, canWrite = false))
        val tweak = fakeTweak(
            listOf(action("settings_put", "ns" to "system", "key" to "k", "value" to "1")),
        )
        val refusal = engine.refusalFor(tweak)
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("modify system settings"))
    }

    @Test
    fun `a read-only tweak needs nothing granted`() {
        val engine = engineWith(StaticShell(AccessLevel.NONE, canWrite = false))
        val tweak = fakeTweak(listOf(action("settings_get", "ns" to "system", "key" to "k")))
        assertNull(engine.refusalFor(tweak))
    }

    @Test
    fun `dry runs preview even when the runner cannot execute`() {
        val engine = engineWith(StaticShell(AccessLevel.SETTINGS, canWrite = true))
        val tweak = fakeTweak(listOf(action("pm_disable", "pkg" to "com.example")))
        engine.dryRun = true
        val plan = engine.plan(tweak)
        assertTrue("a preview should still show the commands", plan.isNotEmpty())
    }
}
