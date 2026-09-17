package dev.cameleonnbss.originostoolkit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cameleonnbss.originostoolkit.core.AppLanguage
import dev.cameleonnbss.originostoolkit.core.Prefs
import dev.cameleonnbss.originostoolkit.ui.AppNav
import dev.cameleonnbss.originostoolkit.ui.ToolkitViewModel
import dev.cameleonnbss.originostoolkit.ui.theme.AuraBackground
import dev.cameleonnbss.originostoolkit.ui.theme.ToolkitTheme

class MainActivity : ComponentActivity() {

    /**
     * The tab a home-screen component asked for, if it did.
     *
     * Snapshot state rather than a `lateinit` field, because the request can also
     * arrive while the activity is already on screen: `onNewIntent` updates this
     * and the navigation surface reacts.
     */
    private val requestedRoute = mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    /**
     * Where the in-app language choice lands. Pinning the locale here rather than
     * in `setContent` means the whole activity — resources, dialogs, the
     * launcher-provided label — resolves in the chosen language, not just the
     * Compose tree.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase, Prefs(newBase).languageTag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as OriginOsApp).container
        askForNotificationPermissionIfNeeded()
        requestedRoute.value = intent?.getStringExtra(EXTRA_ROUTE)

        setContent {
            ToolkitTheme {
                val viewModel: ToolkitViewModel = viewModel(factory = ToolkitViewModel.Factory(container))
                // The aura sits behind everything and the navigation surface draws
                // transparent over it, so the warm/cool wash is continuous from the
                // status bar to the gesture bar instead of being cut off by the
                // scaffold's own background.
                AuraBackground {
                    AppNav(viewModel, container, startRoute = requestedRoute.value)
                }
            }
        }
    }

    /** A second tap on the same component while the app is open still navigates. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedRoute.value = intent.getStringExtra(EXTRA_ROUTE)
    }

    /**
     * Only needed so the frame-rate overlay and the per-app watcher can show
     * their foreground notification. Denying it does not break the app: the
     * services simply run silently on Android 13+.
     */
    private fun askForNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        /** Extra a home-screen component uses to open the app on a given tab. */
        const val EXTRA_ROUTE = "dev.cameleonnbss.originostoolkit.extra.ROUTE"

        const val ROUTE_HOME = "dashboard"
        const val ROUTE_REFRESH = "refresh"
    }
}
