package dev.cameleonnbss.originostoolkit.core.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.cameleonnbss.originostoolkit.BuildConfig
import dev.cameleonnbss.originostoolkit.core.ops.AccessLevel
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

    /** Called when the binder comes up, so the shell can bind its helper. */
    var onReady: (() -> Unit)? = null

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
        if (alive && granted) onReady?.invoke()
    }
}

/**
 * Runs commands as the `shell` user through a Shizuku *user service*.
 *
 * Shizuku does not let an app call `newProcess` directly; the supported way to
 * get shell privileges is to have Shizuku launch a component of yours in a
 * process it controls. [UserService] is that component, and this class is the
 * client side of it.
 *
 * No root anywhere: the helper process runs as uid 2000, it dies with Shizuku,
 * and it can only do what `adb shell` could have done.
 */
class ShizukuShell(private val context: Context) : ShellRunner {

    override val label: String = "Shizuku (shell uid 2000)"
    override val level: AccessLevel = AccessLevel.SHELL
    override val canWrite: Boolean = true

    private var helper: IBinder? = null
    private var bindRequested = false

    private val args: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, UserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            helper = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            helper = null
        }
    }

    /** Binds the helper process. Safe to call repeatedly; a no-op once bound. */
    fun connect() {
        if (bindRequested || !ShizukuBridge.state.value.ready) return
        bindRequested = true
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure {
            bindRequested = false
            helper = null
        }
    }

    fun disconnect() {
        if (!bindRequested) return
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        helper = null
        bindRequested = false
    }

    override fun isAvailable(): Boolean = ShizukuBridge.state.value.ready

    override fun exec(call: ShellCall): ShellResult {
        val command = call.render()

        if (!isAvailable()) {
            return ShellResult(
                command = command,
                stderr = "Shizuku is not running, or this app is not authorised yet.",
                code = 126,
            )
        }

        val service = helper ?: run {
            connect()
            null
        }
        if (service == null) {
            return ShellResult(
                command = command,
                stderr = "Shizuku's helper process is still starting. Try again in a second.",
                code = 126,
            )
        }

        return try {
            val payload = transact(service, ShellProtocol.TRANSACTION_EXEC, command)
            decode(command, payload)
        } catch (error: Exception) {
            // A dead binder is normal: Shizuku stops whenever it wants.
            helper = null
            bindRequested = false
            ShellResult(command, stderr = error.message ?: "Shizuku call failed", code = 126)
        }
    }

    /** Verifies the identity the helper actually runs as, for the UI to display. */
    fun whoami(): String = runCatching {
        val service = helper ?: return "unknown"
        transact(service, ShellProtocol.TRANSACTION_WHOAMI, null)
    }.getOrDefault("unknown")

    /**
     * One synchronous Binder round trip: write the token, write the argument,
     * read the exception slot, read the result. Passing a non-null reply parcel
     * is what makes `transact` block until the helper answers, so the engine can
     * stay synchronous like the CLI is.
     */
    private fun transact(service: IBinder, code: Int, command: String?): String =
        ShellProtocol.withParcels { data, reply ->
            ShellProtocol.writeToken(data)
            if (command != null) data.writeString(command)
            service.transact(code, data, reply, 0)
            reply.readException()
            reply.readString().orEmpty()
        }

    private fun decode(command: String, payload: String): ShellResult {
        val (code, output) = ShellProtocol.decode(payload)
        return ShellResult(
            command = command,
            stdout = output,
            // Keep failures visible in the result dialogs: `output` prefers stdout.
            stderr = if (code == 0) "" else output,
            code = code,
        )
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
 * Shizuku first — it can do everything. Without it we fall back to
 * [SettingsShell], which is not a degraded read-only mode: it really applies
 * and reverts the tweaks that live in the `system` namespace. [LocalShell] only
 * gets a turn when neither is usable.
 */
class ShellProvider(
    private val context: Context,
    private val local: LocalShell = LocalShell(),
) : RunnerProvider {

    private val shizuku = ShizukuShell(context)
    private val settings = SettingsShell(context)

    /** True when we are running entirely without Shizuku. */
    val shizukuFree: Boolean get() = !shizuku.isAvailable()

    override val active: ShellRunner
        get() = when {
            shizuku.isAvailable() -> shizuku
            settings.canWrite || settings.isAvailable() -> settings
            else -> local
        }

    override val state: ShizukuState get() = ShizukuBridge.state.value

    fun connect() = shizuku.connect()

    fun disconnect() = shizuku.disconnect()

    /** "uid=2000(shell)" when the helper is up, so the UI can prove it. */
    fun shellIdentity(): String = shizuku.whoami()
}
