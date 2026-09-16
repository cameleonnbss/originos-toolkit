package dev.cameleonnbss.originostoolkit.core.shell

import dev.cameleonnbss.originostoolkit.core.ops.ShellCall

data class ShellResult(
    val command: String,
    val stdout: String = "",
    val stderr: String = "",
    val code: Int = 0,
) {
    val ok: Boolean get() = code == 0

    /** Best available output, trimmed. */
    val output: String get() = stdout.trim().ifEmpty { stderr.trim() }
}

/** Trimmed, display-ready form used by the UI. */
data class CommandResult(val command: String, val output: String, val ok: Boolean)

/**
 * Anything that can run a command on this device.
 *
 * Two implementations exist on purpose:
 *  * [ShizukuShell] — full privileges (the `shell` user), used for every write;
 *  * [LocalShell] — unprivileged, used only for read-only probes so the
 *    dashboard still shows something useful before Shizuku is set up.
 */
interface ShellRunner {
    /** Human-readable name for the status card. */
    val label: String

    fun isAvailable(): Boolean

    fun exec(call: ShellCall): ShellResult

    /** Convenience for raw shell strings. */
    fun exec(command: String): ShellResult = exec(ShellCall.Raw(command))

    /** True when this runner may perform state-changing commands. */
    val canWrite: Boolean
}

/**
 * Runs commands as this app, with no elevated privileges.
 *
 * Useful for `getprop` and a few other reads; most `settings`/`pm` writes will
 * be refused, which is exactly the point of the no-root design.
 */
class LocalShell : ShellRunner {
    override val label: String = "unprivileged (app uid)"
    override val canWrite: Boolean = false

    override fun isAvailable(): Boolean = true

    override fun exec(call: ShellCall): ShellResult {
        val command = call.render()
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            ShellResult(command = command, stdout = stdout, stderr = stderr, code = code)
        } catch (error: Exception) {
            ShellResult(command = command, stderr = error.message ?: error::class.java.simpleName, code = 127)
        }
    }
}
