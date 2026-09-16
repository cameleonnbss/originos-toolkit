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
import dev.cameleonnbss.originostoolkit.core.SpecialAccess

/**
 * A frame-rate readout drawn above every other app.
 *
 * Honest about what it measures: it counts the vsync callbacks this overlay
 * receives, which tracks the panel's actual refresh rate. It is *not* a
 * per-game render counter — that needs `dumpsys SurfaceFlinger`, which is why
 * the dashboard offers a manual sample of the real framebuffer statistics.
 */
class FpsOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var view: TextView? = null
    private var choreographer: Choreographer? = null

    private var frames = 0
    private var windowStart = 0L
    private var fps = 0f
    private var lastNotificationUpdate = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            onFrame()
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
        windowStart = SystemClock.elapsedRealtime()
        choreographer = Choreographer.getInstance().also { it.postFrameCallback(frameCallback) }
        return START_STICKY
    }

    override fun onDestroy() {
        choreographer?.removeFrameCallback(frameCallback)
        choreographer = null
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
            text = "— fps"
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

    private fun onFrame() {
        frames++
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - windowStart
        if (elapsed < SAMPLE_WINDOW_MS) return

        fps = frames * 1000f / elapsed
        frames = 0
        windowStart = now

        val hz = DeviceInfo.currentRefreshRate(this)
        view?.text = if (hz != null) {
            "%.0f fps · %.0f Hz".format(fps, hz)
        } else {
            "%.0f fps".format(fps)
        }

        if (now - lastNotificationUpdate > NOTIFICATION_INTERVAL_MS) {
            lastNotificationUpdate = now
            Notifications.update(
                this,
                Notifications.ID_OVERLAY,
                Notifications.CHANNEL_OVERLAY,
                getString(R.string.overlay_on),
                "%.0f fps".format(fps),
            )
        }
    }

    companion object {
        private const val SAMPLE_WINDOW_MS = 1000L
        private const val NOTIFICATION_INTERVAL_MS = 10_000L

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
