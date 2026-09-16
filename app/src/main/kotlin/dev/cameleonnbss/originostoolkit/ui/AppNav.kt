package dev.cameleonnbss.originostoolkit.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.cameleonnbss.originostoolkit.core.AppContainer
import dev.cameleonnbss.originostoolkit.core.SpecialAccess
import dev.cameleonnbss.originostoolkit.ui.components.BusyIndicator
import dev.cameleonnbss.originostoolkit.ui.components.CommandOutputDialog
import dev.cameleonnbss.originostoolkit.ui.components.MessageDialog
import dev.cameleonnbss.originostoolkit.ui.components.PreviewDialog
import dev.cameleonnbss.originostoolkit.ui.components.ResultDialog
import dev.cameleonnbss.originostoolkit.ui.screens.AwesomeScreen
import dev.cameleonnbss.originostoolkit.ui.screens.DashboardScreen
import dev.cameleonnbss.originostoolkit.ui.screens.DebloatScreen
import dev.cameleonnbss.originostoolkit.ui.screens.PermissionsState
import dev.cameleonnbss.originostoolkit.ui.screens.RefreshScreen
import dev.cameleonnbss.originostoolkit.ui.screens.SettingsScreen
import dev.cameleonnbss.originostoolkit.ui.screens.TweaksScreen

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val DESTINATIONS = listOf(
    Destination("dashboard", "Home", Icons.Filled.Memory),
    Destination("tweaks", "Tweaks", Icons.Filled.Tune),
    Destination("refresh", "Refresh", Icons.Filled.Speed),
    Destination("debloat", "Debloat", Icons.Filled.DeleteSweep),
    Destination("settings", "Settings", Icons.Filled.Build),
)

/**
 * The navigation surface: five daily-use tabs, plus Discover, which is a
 * reference list rather than something you open every day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(viewModel: ToolkitViewModel, container: AppContainer) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: "dashboard"

    val openUrl: (String) -> Unit = { url -> context.openUrl(url) }
    val overlayAccess: () -> Unit = { context.startActivity(SpecialAccess.overlaySettingsIntent(context)) }
    val usageAccess: () -> Unit = { context.startActivity(SpecialAccess.usageAccessIntent()) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            DESTINATIONS.firstOrNull { it.route == currentRoute }?.label
                                ?: "Discover",
                        )
                    },
                )
                HorizontalDivider()
                BusyIndicator(state.busy)
            }
        },
        bottomBar = {
            NavigationBar {
                DESTINATIONS.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            if (currentRoute != destination.route) {
                                navController.navigate(destination.route) {
                                    popUpTo("dashboard") { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
                NavigationBarItem(
                    selected = currentRoute == "awesome",
                    onClick = {
                        if (currentRoute != "awesome") {
                            navController.navigate("awesome") { launchSingleTop = true }
                        }
                    },
                    icon = { Icon(Icons.Filled.Explore, contentDescription = "Discover") },
                    label = { Text("Find", style = MaterialTheme.typography.labelSmall) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable("dashboard") {
                DashboardScreen(
                    state = state,
                    viewModel = viewModel,
                    onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } },
                    onOpenUrl = openUrl,
                    onRequestOverlayAccess = overlayAccess,
                    onRequestUsageAccess = usageAccess,
                )
            }
            composable("tweaks") { TweaksScreen(state, viewModel) }
            composable("refresh") { RefreshScreen(state, viewModel, usageAccess) }
            composable("debloat") { DebloatScreen(state, viewModel) }
            composable("awesome") { AwesomeScreen(state, viewModel, openUrl) }
            composable("settings") {
                // Re-read on every entry: the user has usually just come back
                // from a system permission screen.
                val permissions = remember(currentRoute) {
                    PermissionsState(
                        overlay = SpecialAccess.canDrawOverlays(context),
                        usage = SpecialAccess.hasUsageAccess(context),
                    )
                }
                SettingsScreen(
                    state = state,
                    viewModel = viewModel,
                    onOpenUrl = openUrl,
                    onRequestOverlayAccess = overlayAccess,
                    onRequestUsageAccess = usageAccess,
                    permissions = permissions,
                )
            }
        }
    }

    state.preview?.let { preview -> PreviewDialog(preview) { viewModel.dismissPreview() } }
    state.lastResult?.let { result -> ResultDialog(result) { viewModel.dismissResult() } }
    state.commandOutput?.let { output -> CommandOutputDialog(output) { viewModel.dismissCommandOutput() } }
    state.message?.let { message -> MessageDialog(message) { viewModel.clearMessage() } }
}

private fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: nothing sensible to do.
    }
}
