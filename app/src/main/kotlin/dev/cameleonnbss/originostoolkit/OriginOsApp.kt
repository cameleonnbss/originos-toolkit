package dev.cameleonnbss.originostoolkit

import android.app.Application
import dev.cameleonnbss.originostoolkit.core.AppContainer
import dev.cameleonnbss.originostoolkit.core.shell.ShizukuBridge
import dev.cameleonnbss.originostoolkit.service.Notifications

class OriginOsApp : Application() {

    lateinit var container: AppContainer
        private set

    /** For services that can fire before [onCreate] has run: null rather than a crash. */
    val containerOrNull: AppContainer?
        get() = if (::container.isInitialized) container else null

    override fun onCreate() {
        super.onCreate()

        // Observe the Shizuku binder before the first UI frame, so the status
        // card is never wrong-then-corrected.
        ShizukuBridge.register()
        container = AppContainer(this)

        // As soon as Shizuku is up and authorised, bind the user service: the
        // first command should not have to wait for a cold binder handshake.
        ShizukuBridge.onReady = { container.shellProvider.connect() }
        ShizukuBridge.refresh()

        Notifications.ensureChannels(this)
    }
}
