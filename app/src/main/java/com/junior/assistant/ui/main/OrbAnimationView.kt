package com.junior.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState {
    IDLE,
    LISTENING,
    SPEAKING,
    THINKING
}

class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var orbState = OrbState.IDLE
    private var waveRms = 0f

    private var rotationAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var waveAnimator: ValueAnimator? = null
    private var thinkingAnimator: ValueAnimator? = null

    private var rotationAngle = 0f
    private var pulseScale = 1.0f
    private var waveOffset = 0f
    private var thinkingOffset = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 15f), 0f)
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    init {
        setupAnimators()
    }

    private fun setupAnimators() {
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
        }
        rotationAnimator?.start()

        pulseAnimator = ValueAnimator.ofFloat(0.95f, 1.05f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulseScale = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
        }
        pulseAnimator?.start()

        waveAnimator = ValueAnimator.ofFloat(0f, 2f * Math.PI.toFloat()).apply {
            duration = 500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                waveOffset = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
        }
        waveAnimator?.start()

        thinkingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                thinkingOffset = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
        }
        thinkingAnimator?.start()
    }

    fun setState(state: OrbState) {
        this.orbState = state
        postInvalidate()
    }

    fun setRms(rms: Float) {
        this.waveRms = rms
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (width.coerceAtMost(height) / 3f)

        if (width == 0 || height == 0) return

        val glowRadius = baseRadius * 1.6f * pulseScale
        val glowColor = getGlowColorByState()
        val gradient = RadialGradient(cx, cy, glowRadius, glowColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        glowPaint.shader = gradient
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        val coreRadius = baseRadius * pulseScale
        solidPaint.shader = RadialGradient(
            cx - coreRadius * 0.3f,
            cy - coreRadius * 0.3f,
            coreRadius * 1.2f,
            getSphereColorByState(),
            getDarkColorByState(),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius, solidPaint)

        accentRingPaint.color = getAccentColorByState()
        canvas.save()
        canvas.rotate(rotationAngle, cx, cy)
        canvas.drawCircle(cx, cy, baseRadius * 1.1f, accentRingPaint)
        canvas.rotate(45f, cx, cy)
        canvas.drawCircle(cx, cy, baseRadius * 1.25f, accentRingPaint)
        canvas.rotate(-90f, cx, cy)
        canvas.drawCircle(cx, cy, baseRadius * 1.4f, accentRingPaint)
        canvas.restore()

        if (orbState == OrbState.SPEAKING || orbState == OrbState.LISTENING) {
            wavePaint.color = getAccentColorByState()
            val magnitude = (waveRms / 150f).coerceIn(4f, 35f)
            val waveRadius = baseRadius * 1.15f
            val path = Path()
            val points = 72
            for (i in 0..points) {
                val angleRad = Math.toRadians((i * (360f / points)).toDouble()).toFloat()
                val displacement = sin(angleRad * 6f + waveOffset) * magnitude
                val r = waveRadius + displacement
                val x = cx + cos(angleRad) * r
                val y = cy + sin(angleRad) * r
                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            path.close()
            canvas.drawPath(path, wavePaint)
        }

        if (orbState == OrbState.THINKING) {
            arcPaint.color = 0xFF40C4FF.toInt()
            val rectF = RectF(cx - baseRadius * 1.3f, cy - baseRadius * 1.3f, cx + baseRadius * 1.3f, cy + baseRadius * 1.3f)
            canvas.save()
            canvas.rotate(thinkingOffset, cx, cy)
            canvas.drawArc(rectF, 45f, 90f, false, arcPaint)
            canvas.drawArc(rectF, 225f, 90f, false, arcPaint)
            canvas.restore()
        }

        if (orbState != OrbState.IDLE) {
            particlePaint.color = getAccentColorByState()
            val pCount = 12
            val orbitRadius = baseRadius * 1.5f
            for (i in 0 until pCount) {
                val pAngle = rotationAngle + (i * (360f / pCount))
                val pAngleRad = Math.toRadians(pAngle.toDouble()).toFloat()
                val px = cx + cos(pAngleRad) * orbitRadius
                val py = cy + sin(pAngleRad) * orbitRadius
                canvas.drawCircle(px, py, 6f, particlePaint)
            }
        }

        canvas.drawCircle(cx - coreRadius * 0.35f, cy - coreRadius * 0.35f, coreRadius * 0.15f, specularPaint)
    }

    private fun getGlowColorByState(): Int {
        return when (orbState) {
            OrbState.IDLE -> 0x33B71C1C.toInt()
            OrbState.LISTENING -> 0x55FF1744.toInt()
            OrbState.SPEAKING -> 0x55E040FB.toInt()
            OrbState.THINKING -> 0x5540C4FF.toInt()
        }
    }

    private fun getSphereColorByState(): Int {
        return when (orbState) {
            OrbState.IDLE -> 0xFF880E4F.toInt()
            OrbState.LISTENING -> 0xFFFF1744.toInt()
            OrbState.SPEAKING -> 0xFFE040FB.toInt()
            OrbState.THINKING -> 0xFF00B0FF.toInt()
        }
    }

    private fun getDarkColorByState(): Int {
        return when (orbState) {
            OrbState.IDLE -> 0xFF310014.toInt()
            OrbState.LISTENING -> 0xFF4A000A.toInt()
            OrbState.SPEAKING -> 0xFF350041.toInt()
            OrbState.THINKING -> 0xFF002B41.toInt()
        }
    }

    private fun getAccentColorByState(): Int {
        return when (orbState) {
            OrbState.IDLE -> 0xFFB71C1C.toInt()
            OrbState.LISTENING -> 0xFFFF1744.toInt()
            OrbState.SPEAKING -> 0xFFE040FB.toInt()
            OrbState.THINKING -> 0xFF40C4FF.toInt()
        }
    }
}
