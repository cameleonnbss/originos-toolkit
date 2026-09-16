package dev.cameleonnbss.originostoolkit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cameleonnbss.originostoolkit.core.AppContainer
import dev.cameleonnbss.originostoolkit.core.AppEntry
import dev.cameleonnbss.originostoolkit.core.DeviceInfo
import dev.cameleonnbss.originostoolkit.core.DeviceSnapshot
import dev.cameleonnbss.originostoolkit.core.EngineException
import dev.cameleonnbss.originostoolkit.core.JournalEntry
import dev.cameleonnbss.originostoolkit.core.SpecialAccess
import dev.cameleonnbss.originostoolkit.core.TweakResult
import dev.cameleonnbss.originostoolkit.core.model.Catalog
import dev.cameleonnbss.originostoolkit.core.model.Tweak
import dev.cameleonnbss.originostoolkit.core.shell.ShizukuBridge
import dev.cameleonnbss.originostoolkit.core.shell.ShizukuState
import dev.cameleonnbss.originostoolkit.service.FpsOverlayService
import dev.cameleonnbss.originostoolkit.service.PerAppRefreshService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The command preview shown before anything runs. */
data class PreviewState(val title: String, val commands: List<String>)

data class ToolkitUiState(
    val catalog: Catalog,
    val catalogError: String? = null,
    val snapshot: DeviceSnapshot? = null,
    val shizuku: ShizukuState = ShizukuState(),
    val journal: List<JournalEntry> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val lastResult: TweakResult? = null,
    val preview: PreviewState? = null,
    val installed: Set<String> = emptySet(),
    val disabled: Set<String> = emptySet(),
    val packagesLoaded: Boolean = false,
    val experimentalEnabled: Boolean = false,
    val verifiedOnly: Boolean = false,
    val currentRefreshRate: Float? = null,
    val supportedRates: List<Float> = emptyList(),
    val defaultRefreshRate: Int = 0,
    val perAppRates: Map<String, Int> = emptyMap(),
    val overlayEnabled: Boolean = false,
    val watcherEnabled: Boolean = false,
    val commandOutput: String? = null,
    val apps: List<AppEntry> = emptyList(),
) {
    val canWrite: Boolean get() = shizuku.ready

    fun isApplied(tweakId: String): Boolean = journal.any { it.tweakId == tweakId }

    /** Tweaks matching the current filters, in catalog order. */
    fun visibleTweaks(category: String?): List<Tweak> = catalog.tweaks.filter { tweak ->
        (category == null || tweak.category == category) &&
            (!verifiedOnly || tweak.verified) &&
            (experimentalEnabled || tweak.category != EXPERIMENTAL_CATEGORY)
    }

    companion object {
        const val EXPERIMENTAL_CATEGORY = "experimental"
    }
}

class ToolkitViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        ToolkitUiState(
            catalog = container.catalog,
            catalogError = container.catalogError,
            experimentalEnabled = container.prefs.experimentalEnabled,
            verifiedOnly = container.prefs.verifiedOnly,
            defaultRefreshRate = container.prefs.defaultRefreshRate,
            perAppRates = container.prefs.perAppRates(),
            overlayEnabled = container.prefs.overlayEnabled,
            watcherEnabled = container.prefs.refreshWatcherEnabled,
        ),
    )
    val state: StateFlow<ToolkitUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            ShizukuBridge.state.collect { shizuku ->
                _state.update { it.copy(shizuku = shizuku) }
                // Binding is idempotent, and doing it here means the helper is
                // already up by the time the user taps Apply.
                if (shizuku.ready) container.shellProvider.connect()
            }
        }
        refresh()
    }

    // -- reading -----------------------------------------------------------

    fun refresh() = background {
        if (ShizukuBridge.state.value.ready) container.shellProvider.connect()
        val snapshot = DeviceInfo.read(container.shell)
        _state.update {
            it.copy(
                snapshot = snapshot,
                journal = container.engine.journal,
                currentRefreshRate = container.currentRefreshRate(),
                supportedRates = container.supportedRefreshRates(),
                experimentalEnabled = container.prefs.experimentalEnabled,
                verifiedOnly = container.prefs.verifiedOnly,
                defaultRefreshRate = container.prefs.defaultRefreshRate,
                perAppRates = container.prefs.perAppRates(),
                overlayEnabled = container.prefs.overlayEnabled,
                watcherEnabled = container.prefs.refreshWatcherEnabled,
            )
        }
    }

    fun refreshShizuku() = ShizukuBridge.refresh()

    /** Opens Shizuku's own permission dialog (or no-ops if it is not running). */
    fun requestShizukuPermission() = ShizukuBridge.requestPermission(REQUEST_CODE)

    private fun refreshJournal() = _state.update { it.copy(journal = container.engine.journal) }

    // -- preview -----------------------------------------------------------

    fun previewTweak(tweak: Tweak) {
        val commands = container.engine.plan(tweak).map { it.render() }
        _state.update {
            it.copy(
                preview = PreviewState(
                    title = tweak.name,
                    commands = commands.ifEmpty { listOf("(this tweak needs a value read from the device)") },
                ),
            )
        }
    }

    fun previewIds(ids: List<String>) {
        val tweaks = container.catalog.resolve(ids)
        val commands = tweaks.flatMap { tweak ->
            listOf("# ${tweak.id}") + container.engine.plan(tweak).map { it.render() }
        }
        _state.update {
            it.copy(
                preview = PreviewState(
                    title = "${tweaks.size} tweak(s)",
                    commands = commands.ifEmpty { listOf("(nothing to preview)") },
                ),
            )
        }
    }

    fun dismissPreview() = _state.update { it.copy(preview = null) }

    fun dismissResult() = _state.update { it.copy(lastResult = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun dismissCommandOutput() = _state.update { it.copy(commandOutput = null) }

    // -- applying ----------------------------------------------------------

    fun applyTweak(tweak: Tweak, force: Boolean = false) = background {
        try {
            val result = container.engine.apply(tweak, force = force)
            _state.update { it.copy(lastResult = result) }
            refreshJournal()
        } catch (error: EngineException) {
            _state.update { it.copy(message = error.message) }
        }
    }

    fun applyIds(ids: List<String>) = background {
        val results = container.catalog.resolve(ids).mapNotNull { tweak ->
            runCatching { container.engine.apply(tweak) }.getOrNull()
        }
        val combined = results.fold(TweakResult("", "")) { acc, result ->
            acc.copy(
                steps = acc.steps + result.steps,
                skipped = acc.skipped + result.skipped,
                errors = acc.errors + result.errors,
            )
        }
        if (results.isNotEmpty()) {
            _state.update {
                it.copy(
                    lastResult = combined.copy(
                        tweakId = "profile",
                        name = "${results.size} tweak(s)",
                    ),
                )
            }
        }
        refreshJournal()
    }

    fun revertTweak(tweakId: String) = background {
        try {
            val result = container.engine.revert(container.catalog.tweak(tweakId), tweakId)
            _state.update { it.copy(lastResult = result) }
            refreshJournal()
        } catch (error: EngineException) {
            _state.update { it.copy(message = error.message) }
        }
    }

    fun revertAll() = background {
        val results = container.engine.revertAll()
        _state.update {
            it.copy(
                lastResult = results.firstOrNull(),
                message = if (results.isEmpty()) "The journal is empty." else "Reverted ${results.size} tweak(s).",
            )
        }
        refreshJournal()
    }

    /**
     * Reachable even when the UI is unusable: factory display metrics first,
     * then every journaled change.
     */
    fun panicReset() = background {
        val results = container.engine.panicReset()
        _state.update {
            it.copy(
                lastResult = results.firstOrNull(),
                message = "Display metrics restored and ${results.size - 1} tweak(s) reverted.",
            )
        }
        refreshJournal()
        refresh()
    }

    fun verifyTweak(tweak: Tweak) = background {
        val applied = container.engine.status(tweak)
        val verdict = when (applied) {
            true -> "looks applied on the device"
            false -> "does not look applied on the device"
            null -> "has no readable state (one-shot action)"
        }
        _state.update { it.copy(message = "${tweak.name} $verdict.") }
    }

    // -- packages ----------------------------------------------------------

    fun loadApps() = background {
        val apps = container.installedApps()
        _state.update { it.copy(apps = apps) }
    }

    fun loadPackages() = background {
        val installed = container.shell.exec("pm list packages").stdout.toPackageSet()
        val disabled = container.shell.exec("pm list packages -d").stdout.toPackageSet()
        _state.update {
            it.copy(
                installed = installed,
                disabled = disabled,
                packagesLoaded = installed.isNotEmpty(),
                message = if (installed.isEmpty()) {
                    "Could not read the package list. Shizuku must be running."
                } else {
                    null
                },
            )
        }
    }

    fun setPackageEnabled(packageName: String, enabled: Boolean) = background {
        val command = if (enabled) {
            "pm enable --user 0 $packageName"
        } else {
            "pm disable-user --user 0 $packageName"
        }
        val result = container.shell.exec(command)
        _state.update { current ->
            val disabled = current.disabled.toMutableSet()
            if (result.ok) {
                if (enabled) disabled.remove(packageName) else disabled.add(packageName)
            }
            current.copy(disabled = disabled, message = if (result.ok) null else result.output)
        }
    }

    fun sampleSurfaceFlinger() = background {
        // The only way to see real compositor timings. It needs the shell user,
        // and some OriginOS builds restrict it, so failures are reported rather
        // than swallowed.
        val result = container.shell.exec("dumpsys SurfaceFlinger --latency")
        _state.update {
            it.copy(
                commandOutput = result.output.ifEmpty {
                    "No output. This build restricts SurfaceFlinger statistics without root."
                },
            )
        }
    }

    // -- settings ----------------------------------------------------------

    fun setExperimental(enabled: Boolean) = background {
        container.prefs.experimentalEnabled = enabled
        _state.update { it.copy(experimentalEnabled = enabled) }
    }

    fun setVerifiedOnly(enabled: Boolean) = background {
        container.prefs.verifiedOnly = enabled
        _state.update { it.copy(verifiedOnly = enabled) }
    }

    fun setDefaultRefreshRate(rate: Int) = background {
        container.prefs.defaultRefreshRate = rate
        _state.update { it.copy(defaultRefreshRate = rate) }
    }

    fun setPerAppRate(packageName: String, rate: Int?) = background {
        container.prefs.setPerAppRate(packageName, rate)
        _state.update { it.copy(perAppRates = container.prefs.perAppRates()) }
    }

    fun clearPerAppRates() = background {
        container.prefs.clearPerAppRates()
        _state.update { it.copy(perAppRates = emptyMap()) }
    }

    fun toggleOverlay(): Boolean {
        val enable = !_state.value.overlayEnabled
        if (enable) {
            if (!SpecialAccess.canDrawOverlays(container.appContext)) {
                _state.update {
                    it.copy(message = "Grant \"display over other apps\" before enabling the overlay.")
                }
                return false
            }
            FpsOverlayService.start(container.appContext)
        } else {
            FpsOverlayService.stop(container.appContext)
        }
        container.prefs.overlayEnabled = enable
        _state.update { it.copy(overlayEnabled = enable) }
        return true
    }

    fun toggleWatcher(): Boolean {
        val enable = !_state.value.watcherEnabled
        if (enable) {
            if (!SpecialAccess.hasUsageAccess(container.appContext)) {
                _state.update {
                    it.copy(message = "Grant usage access so the watcher can tell which app is in front.")
                }
                return false
            }
            if (!_state.value.canWrite) {
                _state.update { it.copy(message = "Shizuku must be running before the watcher can work.") }
                return false
            }
            PerAppRefreshService.start(container.appContext)
        } else {
            PerAppRefreshService.stop(container.appContext)
        }
        container.prefs.refreshWatcherEnabled = enable
        _state.update { it.copy(watcherEnabled = enable) }
        return true
    }

    // -- plumbing ----------------------------------------------------------

    private fun background(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: error::class.java.simpleName) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun String.toPackageSet(): Set<String> = lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").substringBefore(" ") }
        .filter { it.isNotBlank() }
        .toSet()

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ToolkitViewModel(container) as T
    }

    companion object {
        const val REQUEST_CODE = 4711
    }
}
