package dev.cameleonnbss.originostoolkit.core.shell

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs commands as the user Shizuku started this service with.
 *
 * When Shizuku binds a *user service*, it launches this component in a process
 * of its own, running with the identity of whoever started Shizuku — the `shell`
 * user. So this file is the boundary of the app's privilege: everything above it
 * is ordinary app code, everything below it is `adb shell` without a cable.
 *
 * That identity cannot read `/data/data`, cannot mount filesystems and cannot
 * touch verified boot, which is exactly why the project uses it instead of
 * asking for root.
 *
 * The transport is the manual Binder protocol in [ShellProtocol] rather than
 * AIDL, so the build does not depend on the native `aidl` binary.
 */
class UserService : Service() {

    private val binder = object : Binder() {

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                ShellProtocol.TRANSACTION_EXEC -> {
                    data.enforceInterface(ShellProtocol.DESCRIPTOR)
                    val command = data.readString().orEmpty()
                    reply?.writeNoException()
                    reply?.writeString(runCommand(command))
                    true
                }

                ShellProtocol.TRANSACTION_WHOAMI -> {
                    data.enforceInterface(ShellProtocol.DESCRIPTOR)
                    reply?.writeNoException()
                    reply?.writeString(identity())
                    true
                }

                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun runCommand(command: String): String = runCatching {
        val process = ProcessBuilder("sh", "-c", command)
            // Merged on purpose: reading one pipe while the other fills up is the
            // classic way an exec helper deadlocks.
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        val code = process.waitFor()
        ShellProtocol.encode(code, output)
    }.getOrElse { error ->
        ShellProtocol.encode(
            ShellProtocol.EXIT_FAILURE,
            error.message ?: error::class.java.simpleName,
        )
    }

    /** "uid=2000(shell) ..." — lets the app prove the identity it was lent. */
    private fun identity(): String = runCatching {
        BufferedReader(InputStreamReader(ProcessBuilder("id").start().inputStream))
            .use { it.readText() }
            .trim()
    }.getOrElse { "unknown" }
}
