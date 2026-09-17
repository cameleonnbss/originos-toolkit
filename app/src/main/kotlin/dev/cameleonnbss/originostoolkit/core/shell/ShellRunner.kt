package dev.cameleonnbss.originostoolkit.core.shell

import dev.cameleonnbss.originostoolkit.core.ops.AccessLevel
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
 * Three implementations exist on purpose:
 *  * [ShizukuShell] — full privileges (the `shell` user), used for every write
 *    that genuinely needs uid 2000;
 *  * [SettingsShell] — unprivileged writes to the `system` namespace through
 *    the app's own ContentResolver, once the user grants *modify system
 *    settings*. This is what makes the toolkit usable without Shizuku;
 *  * [LocalShell] — no privileges at all, for the few reads that still work
 *    when nothing has been granted.
 */
interface ShellRunner {
    /** Human-readable name for the status card. */
    val label: String

    /** The strongest action this runner is allowed to perform. */
    val level: AccessLevel

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
    override val level: AccessLevel = AccessLevel.NONE
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
