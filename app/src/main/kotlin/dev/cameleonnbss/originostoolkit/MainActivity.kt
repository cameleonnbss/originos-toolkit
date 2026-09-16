package dev.cameleonnbss.originostoolkit

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cameleonnbss.originostoolkit.ui.AppNav
import dev.cameleonnbss.originostoolkit.ui.ToolkitViewModel
import dev.cameleonnbss.originostoolkit.ui.theme.ToolkitTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as OriginOsApp).container
        askForNotificationPermissionIfNeeded()

        setContent {
            ToolkitTheme {
                val viewModel: ToolkitViewModel = viewModel(factory = ToolkitViewModel.Factory(container))
                AppNav(viewModel, container)
            }
        }
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
}
