package dev.cameleonnbss.originostoolkit.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cameleonnbss.originostoolkit.core.AppContainer
import dev.cameleonnbss.originostoolkit.core.AppEntry
import dev.cameleonnbss.originostoolkit.core.DeviceInfo
import dev.cameleonnbss.originostoolkit.core.DeviceSnapshot
import dev.cameleonnbss.originostoolkit.core.FpsSampler
import dev.cameleonnbss.originostoolkit.core.EngineException
import dev.cameleonnbss.originostoolkit.core.JournalEntry
import dev.cameleonnbss.originostoolkit.core.SpecialAccess
import dev.cameleonnbss.originostoolkit.core.TweakResult
import dev.cameleonnbss.originostoolkit.core.model.Catalog
import dev.cameleonnbss.originostoolkit.core.model.Tweak
import dev.cameleonnbss.originostoolkit.core.ops.Access
import dev.cameleonnbss.originostoolkit.core.ops.AccessLevel
import dev.cameleonnbss.originostoolkit.core.shell.ShizukuBridge
import dev.cameleonnbss.originostoolkit.core.shell.ShizukuState
import dev.cameleonnbss.originostoolkit.service.FpsOverlayService
import dev.cameleonnbss.originostoolkit.service.PerAppRefreshService
import dev.cameleonnbss.originostoolkit.widget.WidgetUpdater
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
    val accessLevel: AccessLevel = AccessLevel.NONE,
    val writeSettingsGranted: Boolean = false,
    val runnerLabel: String = "",
) {
    /**
     * True when *something* can be written: either Shizuku is up, or the user
     * granted this app the right to write the system settings namespace.
     */
    val canWrite: Boolean get() = shizuku.ready || writeSettingsGranted

    /** True when this app is running with no Shizuku at all. */
    val noShizukuMode: Boolean get() = !shizuku.ready

    /**
     * Whether [tweak] can actually run right now.
     *
     * Deliberately not a global "is Shizuku running" question: a tweak that only
     * touches the `system` namespace runs fine without Shizuku once the settings
     * grant is in place, and the UI should say so instead of refusing it.
     */
    fun canRun(tweak: Tweak): Boolean = when (Access.of(tweak)) {
        AccessLevel.NONE -> true
        AccessLevel.SETTINGS -> writeSettingsGranted || shizuku.ready
        AccessLevel.SHELL -> shizuku.ready
    }

    /** One sentence explaining what is missing, or `null` when nothing is. */
    fun accessHint(tweak: Tweak): String? = when {
        canRun(tweak) -> null
        Access.of(tweak) == AccessLevel.SHELL ->
            "Needs Shizuku (or the generated script over adb): this one uses the shell user."
        else ->
            "Grant \"modify system settings\" to this app, or start Shizuku."
    }

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
        val snapshot = container.snapshot()
        _state.update {
            it.copy(
                snapshot = snapshot,
                accessLevel = container.accessLevel,
                writeSettingsGranted = container.writeSettingsGranted(),
                runnerLabel = container.shell.label,
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

    /**
     * Opens the *modify system settings* screen for this app.
     *
     * This is the whole setup for the no-Shizuku path: one toggle, and every
     * tweak in the `system` namespace becomes usable.
     */
    fun requestWriteSettings() {
        val intent = container.writeSettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { container.appContext.startActivity(intent) }.onFailure {
            _state.update {
                it.copy(message = "Could not open the settings screen: grant it manually from Apps → Special access.")
            }
        }
    }

    /**
     * Re-reads the journal, and tells the home-screen components about it: what
     * they display is derived from the journal, the settings values and the
     * access level, so any apply or revert changes them.
     */
    private fun refreshJournal() {
        _state.update { it.copy(journal = container.engine.journal) }
        WidgetUpdater.refreshAll(container.appContext)
    }

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

    /**
     * Samples the real compositor timings for whatever is in front.
     *
     * `dumpsys SurfaceFlinger --latency` without a layer name returns the
     * statistics of some arbitrary layer — usually a system one — which is why
     * the previous version of this function showed a plausible but meaningless
     * number. It now resolves the foreground app to its own surface first.
     */
    fun sampleSurfaceFlinger() = background {
        val shell = container.shell
        if (!shell.level.covers(AccessLevel.SHELL)) {
            _state.update {
                it.copy(
                    commandOutput = "Reading real frame timings needs the shell user " +
                        "(Shizuku or adb): only uid 2000 may ask SurfaceFlinger for its " +
                        "present timestamps.\n\nEverything else in the toolkit still works " +
                        "without Shizuku.",
                )
            }
            return@background
        }

        val activity = shell.exec("dumpsys activity activities").stdout
        val foreground = FpsSampler.foregroundPackage(activity)
            ?: FpsSampler.foregroundPackageFromWindow(shell.exec("dumpsys window").stdout)
        if (foreground == null) {
            // Both dumps came back unparseable. Say what that means and what to
            // do about it, instead of the dead end this used to be.
            _state.update {
                it.copy(
                    commandOutput = "The system would not say which app is in front — both " +
                        "dumps (activity, window) came back empty or summarised. That usually " +
                        "means Shizuku is not really running, or OriginOS filtered the output.\n\n" +
                        "Try, in order:\n" +
                        "1. open Shizuku and make sure it says \"running\" (wireless debugging " +
                        "survives until reboot, not past it);\n" +
                        "2. come back here and tap again — a half-started Shizuku answers " +
                        "with empty dumps;\n" +
                        "3. if it still fails, the Home tab shows exactly what the app sees, " +
                        "and the per-app refresh watcher keeps working without this meter.",
                )
            }
            return@background
        }

        val layer = FpsSampler.chooseLayer(shell.exec("dumpsys SurfaceFlinger --list").stdout, foreground)
        if (layer == null) {
            _state.update {
                it.copy(
                    commandOutput = "$foreground has no SurfaceFlinger layer right now " +
                        "(it may not be drawing, or this build hides its layers).",
                )
            }
            return@background
        }

        val raw = shell.exec("dumpsys SurfaceFlinger --latency '$layer'").stdout
        val timings = FpsSampler.parseLatency(raw)
        _state.update {
            it.copy(
                commandOutput = if (timings.isEmpty) {
                    "$layer reported no presented frames. Some OriginOS builds restrict " +
                        "SurfaceFlinger statistics: the output was\n\n" + raw.take(600)
                } else {
                    buildString {
                        appendLine("foreground app : $foreground")
                        appendLine("layer          : $layer")
                        appendLine()
                        appendLine("frames sampled : ${timings.frames}")
                        appendLine("window         : %.2f s".format(timings.spanSeconds ?: 0.0))
                        appendLine("frame rate     : %.1f fps".format(timings.fps ?: 0.0))
                        timings.onePercentLowFps?.let {
                            appendLine("1%% low         : %.1f fps".format(it))
                        }
                        timings.refreshRateFromPeriod?.let {
                            appendLine("panel period   : %.1f Hz".format(it))
                        }
                        append("\nThese are present timestamps from the display pipeline, not ")
                        append("vsync callbacks, so they include frames the app dropped.")
                    }
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
                _state.update {
                    it.copy(
                        message = "The watcher writes system settings: grant \"modify system " +
                            "settings\" to this app, or start Shizuku.",
                    )
                }
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
