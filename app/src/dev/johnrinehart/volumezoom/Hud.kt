package dev.johnrinehart.volumezoom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Transient fine-level indicator at the screen edge next to the volume keys. Uses an accessibility
 * overlay window, so no "display over other apps" permission is needed. It never takes touches or
 * focus.
 */
class Hud(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val view = HudView(context)
    private var attached = false
    private val hide = Runnable { detach() }

    fun show(step: Int, settings: ZoomController.Settings, message: String? = null) {
        view.update(step, settings, message)
        if (!attached) {
            val d = context.resources.displayMetrics.density
            val lp =
                WindowManager.LayoutParams(
                        (64 * d).roundToInt(),
                        (300 * d).roundToInt(),
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT,
                    )
                    .apply {
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        x = (12 * d).roundToInt()
                        title = "Volume Zoom"
                    }
            runCatching { wm.addView(view, lp) }.onSuccess { attached = true }
        }
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, if (message != null) 1000 else 1800)
    }

    fun detach() {
        handler.removeCallbacks(hide)
        if (attached) runCatching { wm.removeViewImmediate(view) }
        attached = false
    }

    private class HudView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 32, 33, 36) }
        private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(138, 180, 248) }
        private val off =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
        private val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = 13 * d
            }
        private val small =
            Paint(text).apply {
                textSize = 10 * d
                color = Color.argb(180, 255, 255, 255)
            }
        private val rect = RectF()

        private var step = 0
        private var steps = Prefs.STEPS
        private var label = ""
        private var caption = ""

        fun update(step: Int, s: ZoomController.Settings, message: String?) {
            this.step = step
            steps = s.steps
            label = message ?: if (step == 0) "0 dB" else "%.1f".format(s.attenuationDb(step))
            caption = if (message != null) "" else "fine"
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, w / 2, w / 2, bg)

            canvas.drawText(label, w / 2, 30 * d, text)
            canvas.drawText(caption, w / 2, 44 * d, small)

            // Segments, bottom = quietest. Lit segments = remaining loudness within the floor step.
            val top = 56 * d
            val bottom = h - 20 * d
            val gap = 4 * d
            val segH = (bottom - top - gap * (steps - 1)) / steps
            val left = w * 0.3f
            val right = w * 0.7f
            val lit = steps - step
            for (i in 0 until steps) {
                val y1 = bottom - i * (segH + gap)
                rect.set(left, y1 - segH, right, y1)
                canvas.drawRoundRect(rect, 3 * d, 3 * d, if (i < lit) on else off)
            }
        }
    }
}
