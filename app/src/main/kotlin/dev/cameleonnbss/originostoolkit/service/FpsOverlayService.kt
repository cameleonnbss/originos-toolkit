package dev.cameleonnbss.originostoolkit.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.cameleonnbss.originostoolkit.OriginOsApp
import dev.cameleonnbss.originostoolkit.R
import dev.cameleonnbss.originostoolkit.core.DeviceInfo
import dev.cameleonnbss.originostoolkit.core.FpsSampler
import dev.cameleonnbss.originostoolkit.core.SpecialAccess
import dev.cameleonnbss.originostoolkit.core.ops.AccessLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * A frame-rate readout drawn above every other app.
 *
 * It shows two different numbers, and never confuses them:
 *
 *  * **the refresh rate** of the panel, from the display mode plus a median of
 *    the vsync cadence this window really receives. When those disagree, the
 *    measured one wins — that is the device reporting a mode it is not using;
 *  * **the real frame rate** of the app in front, parsed from `SurfaceFlinger`
 *    present timestamps, with a 1% low. This needs the shell user, so it is
 *    simply absent (and said to be absent) without Shizuku.
 *
 * Choreographer callbacks are a measure of vsync, not of rendered frames. The
 * previous version of this file counted them and printed the result as `fps`,
 * which was wrong whenever the app could not keep up with the panel — exactly
 * the case a frame-rate meter is for.
 */
class FpsOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var view: TextView? = null
    private var choreographer: Choreographer? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sampleJob: Job? = null

    /** Recent vsync deltas in milliseconds, for the measured refresh rate. */
    private val vsyncDeltas = ArrayDeque<Double>()
    private var lastVsync = 0L

    private var measuredHz: Double? = null
    private var realFpsText: String? = null
    private var lastNotificationUpdate = 0L

    /** The layer we are currently sampling, re-resolved when the app changes. */
    private var layer: String? = null
    private var lastLayerLookup = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            onVsync(frameTimeNanos)
            choreographer?.postFrameCallback(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            Notifications.ID_OVERLAY,
            Notifications.build(
                this,
                Notifications.CHANNEL_OVERLAY,
                getString(R.string.overlay_on),
                getString(R.string.overlay_on),
            ),
        )

        if (!SpecialAccess.canDrawOverlays(this)) {
            // Nothing to do without the grant; stop instead of sitting in the
            // notification shade doing nothing.
            stopSelf()
            return START_NOT_STICKY
        }

        windowManager = getSystemService(WindowManager::class.java)
        showOverlay()
        choreographer = Choreographer.getInstance().also { it.postFrameCallback(frameCallback) }
        startSampling()
        return START_STICKY
    }

    override fun onDestroy() {
        choreographer?.removeFrameCallback(frameCallback)
        choreographer = null
        sampleJob?.cancel()
        scope.cancel()
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        (application as OriginOsApp).container.prefs.overlayEnabled = false
        super.onDestroy()
    }

    private fun showOverlay() {
        if (view != null) return
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(18, 9, 18, 9)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            text = "—"
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 160
        }
        runCatching { windowManager.addView(label, params) }
        view = label
    }

    // -- vsync side --------------------------------------------------------

    /**
     * The median interval between vsync callbacks.
     *
     * Median, not `frames / elapsed`: an average over a one-second window is
     * biased by where the window happened to start, and a single dropped
     * callback drags it around. The median ignores the outliers instead of
     * reporting them as the frame rate.
     */
    private fun onVsync(frameTimeNanos: Long) {
        if (lastVsync != 0L) {
            val deltaMs = (frameTimeNanos - lastVsync) / 1_000_000.0
            if (deltaMs > 0.0 && deltaMs < 1000.0) {
                vsyncDeltas.addLast(deltaMs)
                while (vsyncDeltas.size > VSYNC_SAMPLES) vsyncDeltas.removeFirst()
            }
        }
        lastVsync = frameTimeNanos

        if (vsyncDeltas.size >= 20) {
            val sorted = vsyncDeltas.sorted()
            measuredHz = 1000.0 / sorted[sorted.size / 2]
        }
    }

    // -- SurfaceFlinger side -----------------------------------------------

    private fun startSampling() {
        if (sampleJob?.isActive == true) return
        sampleJob = scope.launch {
            while (isActive) {
                runCatching { sampleOnce() }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private suspend fun sampleOnce() {
        val container = (application as OriginOsApp).container
        val shell = container.shell

        // Without the shell user there is no honest way to see rendered frames,
        // so we keep showing the panel rate and say so.
        if (!shell.level.covers(AccessLevel.SHELL)) {
            realFpsText = null
            render()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastLayerLookup > LAYER_LOOKUP_INTERVAL_MS || layer == null) {
            lastLayerLookup = now
            val foreground = withContext(Dispatchers.IO) {
                val activity = shell.exec("dumpsys activity activities").stdout
                FpsSampler.foregroundPackage(activity)
            }
            layer = if (foreground == null || foreground == packageName) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    val list = shell.exec("dumpsys SurfaceFlinger --list").stdout
                    FpsSampler.chooseLayer(list, foreground)
                }
            }
        }

        val target = layer
        realFpsText = if (target == null) {
            null
        } else {
            val output = withContext(Dispatchers.IO) {
                shell.exec("dumpsys SurfaceFlinger --latency ${shellQuote(target)}").stdout
            }
            val timings = FpsSampler.parseLatency(output)
            FpsSampler.describe(timings)
        }

        render()
    }

    /**
     * Layer names contain spaces and brackets, so they have to be quoted or the
     * device shell splits them into several arguments.
     */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    // -- display -----------------------------------------------------------

    private fun render() {
        val hz = measuredHz ?: DeviceInfo.currentRefreshRate(this)?.toDouble()
        val modeHz = DeviceInfo.currentRefreshRate(this)?.toDouble()

        val text = buildString {
            val real = realFpsText
            if (real != null) {
                append(real)
                if (hz != null) append(" · %.0f Hz".format(hz))
            } else {
                // No real frames available: show the refresh rate and name it
                // for what it is, rather than dressing vsync up as fps.
                append(if (hz != null) "%.0f Hz refresh".format(hz) else "— Hz")
            }
        }

        view?.post { view?.text = text }

        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationUpdate > NOTIFICATION_INTERVAL_MS) {
            lastNotificationUpdate = now
            val detail = when {
                realFpsText != null -> realFpsText.orEmpty()
                modeHz != null && hz != null && abs(modeHz - hz) > 5 ->
                    "panel mode says %.0f Hz, vsync delivers %.0f Hz".format(modeHz, hz)
                else -> "real frame rate needs Shizuku"
            }
            Notifications.update(
                this,
                Notifications.ID_OVERLAY,
                Notifications.CHANNEL_OVERLAY,
                getString(R.string.overlay_on),
                detail,
            )
        }
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 1000L
        private const val LAYER_LOOKUP_INTERVAL_MS = 4000L
        private const val NOTIFICATION_INTERVAL_MS = 15_000L
        private const val VSYNC_SAMPLES = 60

        const val ACTION_START = "dev.cameleonnbss.originostoolkit.OVERLAY_START"
        const val ACTION_STOP = "dev.cameleonnbss.originostoolkit.OVERLAY_STOP"

        fun start(context: Context) {
            val intent = Intent(context, FpsOverlayService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FpsOverlayService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
