package dev.cameleonnbss.originostoolkit.core.shell

import android.content.Context
import android.provider.Settings
import dev.cameleonnbss.originostoolkit.core.ops.Access
import dev.cameleonnbss.originostoolkit.core.ops.AccessLevel
import dev.cameleonnbss.originostoolkit.core.ops.ShellCall

/**
 * Runs the subset of the catalog that never needs the shell user.
 *
 * An ordinary app can read and write the `system` settings namespace through
 * its own `ContentResolver` as soon as the user grants *modify system settings*
 * — the same grant an app uses to change the screen timeout. No Shizuku, no
 * root, no computer, and nothing left running in the background.
 *
 * It accepts exactly the `settings get|put|delete system <key> [value]` shape
 * the rest of the engine already produces, so a tweak does not know which
 * runner executed it. Anything else is refused with a message that says why,
 * rather than pretending the write happened.
 */
class SettingsShell(private val context: Context) : ShellRunner {

    override val label: String = "no Shizuku — system settings only"

    override val level: AccessLevel = AccessLevel.SETTINGS

    override fun isAvailable(): Boolean = true

    /**
     * Reads always work; writing needs the special access, which only the user
     * can grant, so this is a live check rather than a cached flag.
     */
    override val canWrite: Boolean
        get() = runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    override fun exec(call: ShellCall): ShellResult {
        val command = call.render()
        val argv = (call as? ShellCall.Argv)?.argv
            ?: return refuse(command, "this command needs the shell user (Shizuku or adb)")

        if (argv.size < 4 || argv[0] != "settings") {
            return refuse(command, "this command needs the shell user (Shizuku or adb)")
        }

        val verb = argv[1]
        val namespace = argv[2]
        val key = argv[3]
        if (namespace != Access.APP_WRITABLE_NAMESPACE) {
            return refuse(
                command,
                "the '$namespace' namespace is not writable by an app — Shizuku or adb is required",
            )
        }

        return when (verb) {
            "get" -> read(command, key)
            "put" -> write(command, key, argv.getOrNull(4))
            "delete" -> write(command, key, null)
            else -> refuse(command, "unsupported settings verb '$verb'")
        }
    }

    private fun read(command: String, key: String): ShellResult = try {
        val value = Settings.System.getString(context.contentResolver, key)
        // The shell prints the literal `null` for a missing key, and the
        // probe interpreter maps that to "the key did not exist".
        ShellResult(command = command, stdout = value ?: "null", code = 0)
    } catch (error: Exception) {
        ShellResult(command = command, stderr = error.message ?: "read failed", code = 1)
    }

    private fun write(command: String, key: String, value: String?): ShellResult {
        if (!canWrite) {
            return ShellResult(
                command = command,
                stderr = "Grant \"modify system settings\" to this app first " +
                    "(Settings → Apps → Special access), or start Shizuku.",
                code = 126,
            )
        }
        return try {
            // A null value deletes the key, which is what the shell does too.
            Settings.System.putString(context.contentResolver, key, value)
            ShellResult(command = command, stdout = "", code = 0)
        } catch (error: SecurityException) {
            ShellResult(
                command = command,
                stderr = "the system refused the write to '$key' (SecurityException)",
                code = 126,
            )
        } catch (error: Exception) {
            ShellResult(command = command, stderr = error.message ?: "write failed", code = 1)
        }
    }

    private fun refuse(command: String, reason: String): ShellResult =
        ShellResult(command = command, stderr = reason, code = 126)
}
