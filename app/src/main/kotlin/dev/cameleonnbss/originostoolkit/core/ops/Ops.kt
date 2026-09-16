package dev.cameleonnbss.originostoolkit.core.ops

import dev.cameleonnbss.originostoolkit.core.model.Action

/** One command to run on the device. */
sealed interface ShellCall {
    fun render(): String

    data class Argv(val argv: List<String>) : ShellCall {
        override fun render(): String = argv.joinToString(" ") { quote(it) }
    }

    data class Raw(val command: String) : ShellCall {
        override fun render(): String = command
    }

    companion object {
        /** Shell-quotes only when needed, so previews stay readable. */
        fun quote(part: String): String {
            val safe = part.isNotEmpty() && part.all { it.isLetterOrDigit() || it in "._-:@/=" }
            return if (safe) part else "'" + part.replace("'", "'\\''") + "'"
        }
    }
}

/**
 * The safety-critical half of the engine, byte-for-byte equivalent in behaviour
 * to `cli/originos_toolkit/ops.py`. Two invariants:
 *
 * 1. every mutating op has an inverse, or the catalog declares it cannot be undone;
 * 2. nothing is written before the previous value has been read and recorded.
 *
 * A Kotlin unit test (`OpsParityTest`) locks this behaviour against the same
 * expectations as the Python suite.
 */
object Ops {

    const val USER = "0"

    // -- apply -------------------------------------------------------------

    fun shellCalls(action: Action, previous: String? = null): List<ShellCall> = when (action.op) {
        "settings_get" -> listOf(argv("settings", "get", action.namespace, action.settingKey))
        "settings_put" -> listOf(
            argv("settings", "put", action.namespace, action.settingKey, action.settingValue)
        )
        "settings_delete" -> listOf(argv("settings", "delete", action.namespace, action.settingKey))

        "device_config_get" -> listOf(argv("device_config", "get", action.namespace, action.settingKey))
        "device_config_put" -> listOf(
            argv("device_config", "put", action.namespace, action.settingKey, action.settingValue)
        )
        "device_config_delete" -> listOf(
            argv("device_config", "delete", action.namespace, action.settingKey)
        )

        "pm_disable" -> listOf(argv("pm", "disable-user", "--user", USER, action.packageName))
        "pm_enable" -> listOf(argv("pm", "enable", "--user", USER, action.packageName))
        "pm_list" -> listOf(argv("pm", "list", "packages", *action.stringList("args").toTypedArray()))

        "overlay_enable" -> listOf(argv("cmd", "overlay", "enable", "--user", USER, action.packageName))
        "overlay_disable" -> listOf(argv("cmd", "overlay", "disable", "--user", USER, action.packageName))
        "overlay_list" -> listOf(argv("cmd", "overlay", "list", "--user", USER))

        "cmd" -> listOf(argv("cmd", *action.stringList("args").toTypedArray()))
        "svc" -> listOf(argv("svc", action.string("service"), if (action.bool("enable") == true) "enable" else "disable"))
        "shell" -> listOf(ShellCall.Raw(action.string("command").orEmpty()))

        "wm_density_delta" -> {
            val percent = action.int("percent")
            val base = previous?.toIntOrNull()
            if (percent == null || base == null) {
                emptyList()
            } else {
                val next = maxOf(72, Math.round(base * (100 + percent) / 100.0).toInt())
                listOf(argv("wm", "density", next.toString()))
            }
        }

        "wm_density_set" -> listOf(argv("wm", "density", action.string("value")))
        "wm_density_reset" -> listOf(argv("wm", "density", "reset"))

        else -> throw IllegalArgumentException("no translator for op '${action.op}'")
    }

    // -- probes ------------------------------------------------------------

    /** The read that must happen *before* [action] is applied, if any. */
    fun probeFor(action: Action): ShellCall? = when (action.op) {
        "settings_put", "settings_delete" -> argv("settings", "get", action.namespace, action.settingKey)
        "device_config_put", "device_config_delete" ->
            argv("device_config", "get", action.namespace, action.settingKey)
        "wm_density_delta" -> argv("wm", "density")
        "pm_disable" -> argv("pm", "list", "packages", "-d", action.packageName)
        "overlay_enable", "overlay_disable" -> argv("cmd", "overlay", "list", "--user", USER)
        else -> null
    }

    /** Normalises raw probe output into the state string an inverse can use. */
    fun interpretProbe(action: Action, output: String): String? {
        val text = output.trim()
        return when (action.op) {
            "settings_put", "settings_delete", "device_config_put", "device_config_delete" ->
                if (text.isEmpty() || text.equals("null", ignoreCase = true)) "" else text

            "wm_density_delta" -> parseDensity(text)?.toString()

            "pm_disable" -> {
                val pkg = action.packageName.orEmpty()
                if (text.lineSequence().any { it.trim().startsWith("package:") && it.contains(pkg) }) "disabled" else "enabled"
            }

            // `cmd overlay list` prints a bare package line followed by the
            // state line "[x] pkg" / "[ ] pkg", so only bracketed lines count.
            "overlay_enable", "overlay_disable" -> {
                val pkg = action.packageName.orEmpty()
                text.lineSequence()
                    .map { it.trim() }
                    .firstOrNull {
                        pkg.isNotEmpty() && it.contains(pkg) && (it.startsWith("[x]") || it.startsWith("[ ]"))
                    }
                    ?.let { if (it.startsWith("[x]")) "enabled" else "disabled" }
                    ?: "absent"
            }

            else -> null
        }
    }

    /** Pulls the effective density out of `wm density` output. */
    fun parseDensity(text: String): Int? {
        var override: Int? = null
        var physical: Int? = null
        text.lineSequence().forEach { line ->
            val digits = line.filter { it.isDigit() }
            if (digits.isEmpty()) return@forEach
            val value = digits.toIntOrNull() ?: return@forEach
            when {
                line.contains("override", ignoreCase = true) -> override = value
                line.contains("physical", ignoreCase = true) -> physical = value
            }
        }
        return override ?: physical
    }

    // -- inverses ----------------------------------------------------------

    /**
     * Builds the action that undoes [action], or `null` when undoing would be
     * wrong (an already-disabled package, a fire-and-forget command).
     */
    fun inverseAction(action: Action, previous: String?): Action? = when (action.op) {
        "settings_put", "settings_delete" -> {
            if (previous == null) {
                null
            } else if (previous.isEmpty()) {
                Action("settings_delete", mapOf("ns" to action.namespace, "key" to action.settingKey))
            } else {
                Action(
                    "settings_put",
                    mapOf("ns" to action.namespace, "key" to action.settingKey, "value" to previous),
                )
            }
        }

        "device_config_put", "device_config_delete" -> {
            if (previous == null) {
                null
            } else if (previous.isEmpty()) {
                Action("device_config_delete", mapOf("ns" to action.namespace, "key" to action.settingKey))
            } else {
                Action(
                    "device_config_put",
                    mapOf("ns" to action.namespace, "key" to action.settingKey, "value" to previous),
                )
            }
        }

        "pm_disable" -> if (previous == "disabled") null else Action("pm_enable", mapOf("pkg" to action.packageName))
        "pm_enable" -> Action("pm_disable", mapOf("pkg" to action.packageName))

        "overlay_enable" ->
            if (previous == "enabled") null else Action("overlay_disable", mapOf("pkg" to action.packageName))

        "overlay_disable" ->
            if (previous == "disabled") null else Action("overlay_enable", mapOf("pkg" to action.packageName))

        "svc" -> Action(
            "svc",
            mapOf("service" to action.string("service"), "enable" to (action.bool("enable") != true)),
        )

        "wm_density_delta" -> previous?.let { Action("wm_density_set", mapOf("value" to it)) }

        else -> null
    }

    /** The state [action] should observe once applied, or `null` if unreadable. */
    fun expectedState(action: Action): String? = when (action.op) {
        "settings_put", "device_config_put" -> action.settingValue
        "settings_delete", "device_config_delete" -> ""
        "pm_disable", "overlay_disable" -> "disabled"
        "pm_enable", "overlay_enable" -> "enabled"
        else -> null
    }

    /**
     * A missing field becomes an empty argument: the command then fails loudly
     * on the device instead of silently doing something else.
     */
    private fun argv(vararg parts: String?): ShellCall =
        ShellCall.Argv(parts.map { it ?: "" })
}
