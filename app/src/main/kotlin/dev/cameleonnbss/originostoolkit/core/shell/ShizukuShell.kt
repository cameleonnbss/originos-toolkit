package dev.cameleonnbss.originostoolkit.core.shell

import android.content.pm.PackageManager
import dev.cameleonnbss.originostoolkit.core.ops.ShellCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

data class ShizukuState(
    val binderAlive: Boolean = false,
    val permissionGranted: Boolean = false,
) {
    val ready: Boolean get() = binderAlive && permissionGranted
}

/**
 * Tracks the Shizuku binder so the UI can show an honest status.
 *
 * Shizuku can be started, killed and restarted at any moment by the user or by
 * Android, so the state is observed rather than cached.
 */
object ShizukuBridge {

    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private var registered = false

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, grantResult -> refresh(grantResult) }

    /** Idempotent: safe to call from Application.onCreate(). */
    fun register() {
        if (registered) return
        registered = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
        }
        refresh()
    }

    fun requestPermission(requestCode: Int) {
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    fun refresh(grantResult: Int? = null) {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = when {
            !alive -> false
            grantResult != null -> grantResult == PackageManager.PERMISSION_GRANTED
            else -> runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) ==
                PackageManager.PERMISSION_GRANTED
        }
        _state.value = ShizukuState(binderAlive = alive, permissionGranted = granted)
    }
}

/**
 * Runs commands as the `shell` user through Shizuku — the same identity `adb
 * shell` gives you. No root, no su, and it dies the moment Shizuku stops.
 */
class ShizukuShell : ShellRunner {

    override val label: String = "Shizuku (shell uid 2000)"
    override val canWrite: Boolean = true

    override fun isAvailable(): Boolean = ShizukuBridge.state.value.ready

    override fun exec(call: ShellCall): ShellResult {
        val command = call.render()
        if (!isAvailable()) {
            return ShellResult(
                command = command,
                stderr = "Shizuku is not running or this app is not authorised.",
                code = 126,
            )
        }
        return try {
            // The type is inferred on purpose: Shizuku's remote process class has
            // moved between API versions, while the Process-style surface has not.
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            // Output from our commands is small (a settings value, a package
            // name), so reading the streams sequentially before waitFor() is
            // safe here and avoids a second thread per command.
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            ShellResult(command = command, stdout = stdout, stderr = stderr, code = code)
        } catch (error: Exception) {
            ShellResult(command = command, stderr = error.message ?: "Shizuku call failed", code = 1)
        }
    }
}

/** What the engine needs from a shell source; swappable in unit tests. */
interface RunnerProvider {
    /** The best runner available right now. */
    val active: ShellRunner

    val state: ShizukuState
}

/**
 * Chooses the best runner available right now and reports what it can do.
 *
 * When Shizuku is up we write through it; otherwise reads still work through
 * [LocalShell] so the dashboard is never empty, and the UI warns that nothing
 * can be applied yet.
 */
class ShellProvider(private val local: LocalShell = LocalShell()) : RunnerProvider {

    private val shizuku = ShizukuShell()

    override val active: ShellRunner
        get() = if (shizuku.isAvailable()) shizuku else local

    override val state: ShizukuState get() = ShizukuBridge.state.value
}
