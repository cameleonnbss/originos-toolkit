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
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.cameleonnbss.originostoolkit.core.AppContainer
import dev.cameleonnbss.originostoolkit.core.SpecialAccess
import dev.cameleonnbss.originostoolkit.ui.components.BrandMark
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
 *
 * [startRoute] is how a home-screen component asks for a tab — the refresh tile
 * opens the refresh screen rather than dropping the user on Home. It is checked
 * against the real destinations, so nothing outside this file can name a route
 * that does not exist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(viewModel: ToolkitViewModel, container: AppContainer, startRoute: String? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: "dashboard"

    LaunchedEffect(startRoute) {
        val route = startRoute?.takeIf { candidate -> DESTINATIONS.any { it.route == candidate } }
        if (route != null && route != currentRoute) {
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    val openUrl: (String) -> Unit = { url -> context.openUrl(url) }
    val overlayAccess: () -> Unit = { context.startActivity(SpecialAccess.overlaySettingsIntent(context)) }
    val usageAccess: () -> Unit = { context.startActivity(SpecialAccess.usageAccessIntent()) }

    // OriginOS header: the page name on the left, the product mark on the right,
    // exactly how the system's own apps title themselves. Both bars are
    // transparent-ish so the aura behind the page stays continuous.
    val hairline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            DESTINATIONS.firstOrNull { it.route == currentRoute }?.label
                                ?: "Discover",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    actions = { BrandMark(size = 30.dp, modifier = Modifier.padding(end = 16.dp)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
                HorizontalDivider(color = hairline)
                BusyIndicator(state.busy)
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = hairline)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    DESTINATIONS.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            colors = itemColors,
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
                        colors = itemColors,
                        onClick = {
                            if (currentRoute != "awesome") {
                                navController.navigate("awesome") { launchSingleTop = true }
                            }
                        },
                        icon = { Icon(Icons.Filled.Explore, contentDescription = "Discover") },
                        label = { Text("Find", style = MaterialTheme.typography.labelSmall) },
                    )
                }
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
                    prefs = container.prefs,
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
