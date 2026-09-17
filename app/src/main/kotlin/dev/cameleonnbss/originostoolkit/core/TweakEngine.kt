package dev.cameleonnbss.originostoolkit.core

import dev.cameleonnbss.originostoolkit.core.model.Action
import dev.cameleonnbss.originostoolkit.core.model.Tweak
import dev.cameleonnbss.originostoolkit.core.ops.Access
import dev.cameleonnbss.originostoolkit.core.ops.AccessLevel
import dev.cameleonnbss.originostoolkit.core.ops.Ops
import dev.cameleonnbss.originostoolkit.core.ops.ShellCall
import dev.cameleonnbss.originostoolkit.core.shell.RunnerProvider

class EngineException(message: String) : Exception(message)

data class StepOutcome(
    val label: String,
    val command: String,
    val output: String,
    val ok: Boolean,
)

data class TweakResult(
    val tweakId: String,
    val name: String,
    val steps: List<StepOutcome> = emptyList(),
    val skipped: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val reverted: Boolean = false,
    val dryRun: Boolean = false,
) {
    val ok: Boolean get() = errors.isEmpty()

    val appliedCount: Int get() = steps.count { it.ok }
}

/**
 * Applies and reverts tweaks, journalling every change.
 *
 * Behaviour is intentionally identical to the Python engine in
 * `cli/originos_toolkit/engine.py`, and both are covered by tests that assert
 * the same invariants. The order is always:
 *
 *   1. read the previous state of everything we are about to touch;
 *   2. persist the inverse;
 *   3. only then write.
 */
class TweakEngine(
    private val providers: RunnerProvider,
    private val journalStore: JournalStore,
) {

    /** When true, nothing is executed and no journal is written. */
    var dryRun: Boolean = false

    private val entries: MutableList<JournalEntry> = journalStore.load().toMutableList()

    val journal: List<JournalEntry> get() = entries.toList()

    fun isApplied(tweakId: String): Boolean = entries.any { it.tweakId == tweakId }

    // -- access ------------------------------------------------------------

    /**
     * Why the runner we have cannot safely run [actions], or `null` when it can.
     *
     * Checked *before* anything is executed and for the whole tweak at once: a
     * tweak that writes to both `system` and `global` would otherwise half
     * apply, with the journal holding an inverse it can never run.
     */
    fun refusalFor(actions: List<Action>): String? {
        val required = Access.of(actions)
        val runner = providers.active

        if (!runner.level.covers(required)) {
            return when (required) {
                AccessLevel.SHELL ->
                    "needs the shell user: start Shizuku (or run the generated script over adb)."
                AccessLevel.SETTINGS ->
                    "needs \"modify system settings\" on this app: Settings → Apps → " +
                        "OriginOS Toolkit → Special access."
                AccessLevel.NONE -> null
            }
        }

        if (required != AccessLevel.NONE && !runner.canWrite) {
            return "needs write access: grant \"modify system settings\" to this app " +
                "(Settings → Apps → Special access), or start Shizuku."
        }

        return null
    }

    fun refusalFor(tweak: Tweak): String? = refusalFor(tweak.actions + tweak.revert)

    /** True when the active runner can run the whole tweak. */
    fun canRun(tweak: Tweak): Boolean = refusalFor(tweak) == null

    // -- probes ------------------------------------------------------------

    fun probe(tweak: Tweak): Map<String, String?> {
        val states = linkedMapOf<String, String?>()
        tweak.actions.forEach { action ->
            val call = Ops.probeFor(action) ?: return@forEach
            if (dryRun) {
                // A preview must not touch the device; delta ops are resolved
                // against a sane default so the preview stays readable.
                states[action.target] = if (action.op == "wm_density_delta") DEFAULT_DENSITY else null
                return@forEach
            }
            val result = providers.active.exec(call)
            if (!result.ok && result.stdout.isBlank()) {
                throw EngineException("could not read ${action.target}: ${result.output}")
            }
            states[action.target] = Ops.interpretProbe(action, result.stdout)
        }
        return states
    }

    fun inverseFor(tweak: Tweak, states: Map<String, String?>): List<Action> =
        if (tweak.revert.isNotEmpty()) {
            tweak.revert
        } else {
            tweak.actions.mapNotNull { Ops.inverseAction(it, states[it.target]) }
        }

    /**
     * The commands that would run, without running them.
     *
     * `probe` is switched to dry-run internally so a preview never reads from
     * the device either, while relative ops still resolve to something usable.
     */
    fun plan(tweak: Tweak): List<ShellCall> {
        val wasDryRun = dryRun
        dryRun = true
        val states = try {
            probe(tweak)
        } finally {
            dryRun = wasDryRun
        }
        return tweak.actions.flatMap { Ops.shellCalls(it, states[it.target]) }
    }

    // -- apply / revert ----------------------------------------------------

    fun apply(tweak: Tweak, force: Boolean = false): TweakResult {
        val steps = mutableListOf<StepOutcome>()
        val skipped = mutableListOf<String>()
        val errors = mutableListOf<String>()

        if (isApplied(tweak.id) && !force && !dryRun) {
            // Re-applying would snapshot the already-modified state and quietly
            // break the revert. Refuse, and keep the original inverse.
            skipped += "${tweak.id} is already applied"
            return TweakResult(tweak.id, tweak.name, skipped = skipped, dryRun = dryRun)
        }

        // A dry run always previews, so a user without Shizuku can still see
        // exactly what the commands would be, and what they would need.
        if (!dryRun) {
            refusalFor(tweak)?.let { refusal ->
                return TweakResult(
                    tweakId = tweak.id,
                    name = tweak.name,
                    errors = listOf("${tweak.id} $refusal"),
                    dryRun = false,
                )
            }
        }

        val states = probe(tweak)
        val inverses = inverseFor(tweak, states)

        if (!dryRun) {
            if (inverses.isNotEmpty() || tweak.revert.isNotEmpty()) {
                record(tweak.id, tweak.name, inverses)
            } else if (tweak.reversible) {
                errors += "refusing to apply ${tweak.id}: no inverse could be derived"
                return TweakResult(tweak.id, tweak.name, errors = errors, dryRun = dryRun)
            }
        }

        tweak.actions.forEach { action ->
            val calls = Ops.shellCalls(action, states[action.target])
            if (calls.isEmpty()) {
                skipped += "${action.op} (${action.target}) could not be resolved"
                return@forEach
            }
            calls.forEach { call ->
                val command = call.render()
                if (dryRun) {
                    steps += StepOutcome(action.target, command, "(dry run)", ok = true)
                    return@forEach
                }
                val result = providers.active.exec(call)
                steps += StepOutcome(action.target, command, result.output, result.ok)
                if (!result.ok) errors += "$command -> ${result.output}"
            }
        }

        return TweakResult(tweak.id, tweak.name, steps, skipped, errors, dryRun = dryRun)
    }

    fun revert(tweak: Tweak?, tweakId: String): TweakResult {
        val entry = entries.firstOrNull { it.tweakId == tweakId }
        val inverses = entry?.inverse ?: tweak?.revert?.takeIf { it.isNotEmpty() }
        ?: throw EngineException(
            "nothing to revert for $tweakId: it is not in the journal and the catalog " +
                "defines no explicit revert"
        )

        val steps = mutableListOf<StepOutcome>()
        val errors = mutableListOf<String>()

        if (!dryRun) {
            refusalFor(inverses)?.let { refusal ->
                return TweakResult(
                    tweakId = tweakId,
                    name = entry?.name ?: tweak?.name ?: tweakId,
                    errors = listOf("cannot revert $tweakId: it $refusal"),
                    reverted = true,
                    dryRun = false,
                )
            }
        }

        inverses.forEach { action ->
            Ops.shellCalls(action).forEach { call ->
                val command = call.render()
                if (dryRun) {
                    steps += StepOutcome(action.target, command, "(dry run)", ok = true)
                    return@forEach
                }
                val result = providers.active.exec(call)
                steps += StepOutcome(action.target, command, result.output, result.ok)
                if (!result.ok) errors += "$command -> ${result.output}"
            }
        }

        val outcome = TweakResult(
            tweakId = tweakId,
            name = entry?.name ?: tweak?.name ?: tweakId,
            steps = steps,
            errors = errors,
            reverted = true,
            dryRun = dryRun,
        )
        if (!dryRun && entry != null && outcome.ok) forget(tweakId)
        return outcome
    }

    fun revertAll(): List<TweakResult> =
        entries.asReversed().toList().mapNotNull { entry ->
            runCatching { revert(null, entry.tweakId) }.getOrNull()
        }

    /**
     * Last-resort reset: restore factory display metrics, then revert the
     * journal. Reachable from Settings even when the UI looks wrong, because a
     * bad density is the one way a no-root tweak can make a phone awkward.
     */
    fun panicReset(): List<TweakResult> {
        val steps = mutableListOf<StepOutcome>()
        val errors = mutableListOf<String>()
        listOf(
            ShellCall.Argv(listOf("wm", "density", "reset")),
            ShellCall.Argv(listOf("wm", "size", "reset")),
        ).forEach { call ->
            if (dryRun) {
                steps += StepOutcome("display metrics", call.render(), "(dry run)", ok = true)
                return@forEach
            }
            val result = providers.active.exec(call)
            steps += StepOutcome("display metrics", call.render(), result.output, result.ok)
            if (!result.ok) errors += "${call.render()} -> ${result.output}"
        }
        val reset = TweakResult("panic-reset", "Panic reset", steps, errors = errors, dryRun = dryRun)
        return listOf(reset) + revertAll()
    }

    // -- status ------------------------------------------------------------

    /** True/false when the state is readable, `null` when it is not. */
    fun status(tweak: Tweak): Boolean? {
        var verdict: Boolean? = null
        tweak.actions.forEach { action ->
            val call = Ops.probeFor(action) ?: return@forEach
            val expected = Ops.expectedState(action) ?: return@forEach
            val result = providers.active.exec(call)
            val state = Ops.interpretProbe(action, result.stdout)
            verdict = (verdict ?: true) && state == expected
        }
        return verdict
    }

    // -- journal mutations -------------------------------------------------

    private fun record(tweakId: String, name: String, inverse: List<Action>) {
        entries.removeAll { it.tweakId == tweakId }
        entries += JournalEntry(tweakId, name, journalStore.now(), inverse)
        journalStore.save(entries)
    }

    private fun forget(tweakId: String) {
        entries.removeAll { it.tweakId == tweakId }
        journalStore.save(entries)
    }

    companion object {
        /** Used only to make previews of relative ops readable. */
        const val DEFAULT_DENSITY = "460"
    }
}
