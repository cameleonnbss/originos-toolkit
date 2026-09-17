package dev.cameleonnbss.originostoolkit.core.ops

import dev.cameleonnbss.originostoolkit.core.model.Action
import dev.cameleonnbss.originostoolkit.core.model.Tweak

/**
 * What one command needs from the device, weakest first.
 *
 * The distinction matters because it is the difference between an app that
 * refuses to work without Shizuku and one that can actually do things on a
 * stock phone:
 *
 *  * [NONE] — a read this app can do itself, or that needs no privilege.
 *  * [SETTINGS] — writable in-process once the user grants *modify system
 *    settings* (`Settings.ACTION_MANAGE_WRITE_SETTINGS`). Only the `system`
 *    namespace is reachable this way; it happens to contain `peak_refresh_rate`
 *    and `min_refresh_rate`, which is why the refresh-rate tweaks need nothing
 *    else.
 *  * [SHELL] — needs the `shell` user (uid 2000), which Shizuku or a USB cable
 *    provides.
 */
enum class AccessLevel {
    NONE,
    SETTINGS,
    SHELL;

    /** True when this level is enough to run something that needs [required]. */
    fun covers(required: AccessLevel): Boolean = ordinal >= required.ordinal
}

/**
 * Classifies actions and tweaks by the access they really need.
 *
 * This must stay in lockstep with `cli/originos_toolkit/ops.py`, which computes
 * the same thing for the CLI and for the catalog's lint. The catalog declares a
 * requirement per tweak and lint rejects a declaration that does not match the
 * actions, so the two implementations are checked against one another.
 */
object Access {

    /** Ops a plain app can perform against the `system` namespace. */
    private val SETTINGS_OPS = setOf("settings_get", "settings_put", "settings_delete")

    /** The `settings` verbs that change something. */
    private val SETTINGS_WRITE_OPS = setOf("settings_put", "settings_delete")

    /** Ops that never mutate anything. */
    private val READ_ONLY_OPS = setOf("device_config_get", "pm_list", "overlay_list")

    /** The only namespace a non-privileged app may write with WRITE_SETTINGS. */
    const val APP_WRITABLE_NAMESPACE = "system"

    /**
     * The rule, in one place:
     *
     *  * a write to the `system` namespace needs the settings grant ([SETTINGS]);
     *    everywhere else it needs the shell user ([SHELL]);
     *  * a read never needs a grant of its own — the app reads `system` through
     *    its own resolver, and `secure`/`global` are routed through the shell
     *    because this app deliberately implements no in-process access to them.
     */
    fun of(action: Action): AccessLevel {
        val systemNamespace = action.namespace == APP_WRITABLE_NAMESPACE

        if (action.op in SETTINGS_WRITE_OPS) {
            return if (systemNamespace) AccessLevel.SETTINGS else AccessLevel.SHELL
        }
        if (action.op == "settings_get") {
            return if (systemNamespace) AccessLevel.NONE else AccessLevel.SHELL
        }
        return if (action.op in READ_ONLY_OPS) AccessLevel.NONE else AccessLevel.SHELL
    }

    fun of(actions: List<Action>): AccessLevel =
        actions.fold(AccessLevel.NONE) { strongest, action ->
            val need = of(action)
            if (need.ordinal > strongest.ordinal) need else strongest
        }

    /**
     * What applying *and* undoing [tweak] needs.
     *
     * The revert side counts: a tweak whose undo needs the shell user is not a
     * no-Shizuku tweak, even when its apply side would be.
     */
    fun of(tweak: Tweak): AccessLevel = of(tweak.actions + tweak.revert)

    /** True when the whole apply/revert cycle fits in the `system` namespace. */
    fun runsWithoutShizuku(tweak: Tweak): Boolean = of(tweak) != AccessLevel.SHELL

    /** The targets a shell-free run would touch, for the UI to show. */
    fun settingsTargets(tweak: Tweak): List<String> =
        (tweak.actions + tweak.revert).filter { of(it) == AccessLevel.SETTINGS }.map { it.target }
}
