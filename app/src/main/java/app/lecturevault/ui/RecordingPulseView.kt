package app.lecturevault.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.lecturevault.R
import kotlin.math.sin

/** A subtle recording-state ornament. It intentionally does not pretend to show live audio levels. */
class RecordingPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_primary)
        style = Paint.Style.STROKE
    }
    private var phase = 0f
    private var animator: ValueAnimator? = null

    var recording: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startAnimation() else stopAnimation()
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.62f
        val baseRadius = resources.displayMetrics.density * 54f
        repeat(3) { index ->
            val wave = if (recording) (sin((phase + index * 0.23f) * Math.PI * 2).toFloat() + 1f) / 2f else 0f
            val radius = baseRadius + resources.displayMetrics.density * (22f * index + 7f * wave)
            paint.alpha = if (recording) 48 - index * 10 else 20 - index * 4
            paint.strokeWidth = resources.displayMetrics.density * 1.2f
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    private fun startAnimation() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        phase = 0f
    }
}
