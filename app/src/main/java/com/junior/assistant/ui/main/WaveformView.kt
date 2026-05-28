package com.junior.assistant.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 20
    private var targetHeights = FloatArray(barCount)
    private var currentHeights = FloatArray(barCount)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF1744.toInt()
        style = Paint.Style.FILL
    }

    fun setAmplitude(rms: Float) {
        val normalized = (rms / 200f).coerceIn(4f, 120f)
        for (i in 0 until barCount) {
            val factor = if (i % 2 == 0) 0.5f else 1.3f
            val randomOffset = (Math.random() * 12).toFloat()
            targetHeights[i] = normalized * factor + randomOffset
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val spacing = 8f
        val w = width.toFloat()
        val h = height.toFloat()
        val totalSpacing = spacing * (barCount - 1)
        val barWidth = (w - totalSpacing) / barCount

        for (i in 0 until barCount) {
            currentHeights[i] = currentHeights[i] + (targetHeights[i] - currentHeights[i]) * 0.3f

            val left = i * (barWidth + spacing)
            val right = left + barWidth
            val barHeight = currentHeights[i].coerceIn(8f, h * 0.95f)
            val top = (h - barHeight) / 2f
            val bottom = top + barHeight

            canvas.drawRoundRect(left, top, right, bottom, barWidth / 2f, barWidth / 2f, paint)
        }

        for (i in 0 until barCount) {
            targetHeights[i] = (targetHeights[i] * 0.88f).coerceAtLeast(8f)
        }
        postInvalidateOnAnimation()
    }
}
