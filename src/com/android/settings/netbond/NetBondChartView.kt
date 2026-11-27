/*
 * Copyright (C) 2026 Casanova.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.netbond

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import com.android.settings.R
import org.netbond.android.Engine
import org.netbond.android.Stats

/** Native, theme-aware scrolling stacked traffic activity chart with monotone spline smoothing. */
class NetBondChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val secondaryColor: Int

    init {
        val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        secondaryColor = try { a.getColor(0, Color.GRAY) } finally { a.recycle() }
    }

    private var series: List<Pair<String, List<Float>>> = emptyList()
    private var stacked: List<FloatArray> = emptyList()
    private var shift = 0f
    private var animator: ValueAnimator? = null

    fun colorFor(name: String): Int {
        val isWifi = name.contains("Wi-Fi", ignoreCase = true) || name.contains("wlan", ignoreCase = true)
        return context.getColor(
            if (isWifi) R.color.netbond_chart_wifi else R.color.netbond_chart_cellular,
        )
    }

    fun update(series: List<Pair<String, List<Float>>>) {
        if (this.series == series) return
        this.series = series
        stacked = NetBondChartData.stack(series.map { it.second }, Engine.HISTORY)
        animator?.cancel()
        shift = 0f
        if (isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
            animator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 1000
                interpolator = LinearInterpolator()
                addUpdateListener {
                    shift = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        shift = 0f
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics,
        )
        val labelHeight = paint.fontSpacing + 8f * density
        val top = labelHeight
        val bottom = (height - labelHeight).coerceAtLeast(top)
        val graphHeight = bottom - top
        val peak = (stacked.lastOrNull()?.maxOrNull()?.toDouble() ?: 0.0)
            .coerceAtLeast(1.0) * 1.15

        paint.color = secondaryColor
        paint.alpha = 30
        paint.strokeWidth = density
        paint.style = Paint.Style.STROKE
        for (i in 0..4) {
            val y = top + graphHeight * i / 4f
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = secondaryColor
        paint.alpha = 190
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(Stats.rate(peak), 0f, -paint.fontMetrics.top, paint)
        val bottomY = height - paint.fontMetrics.bottom
        canvas.drawText(
            context.getString(R.string.netbond_chart_start), 0f, bottomY, paint,
        )
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            context.getString(R.string.netbond_chart_end),
            width.toFloat(), bottomY, paint,
        )

        val save = canvas.save()
        canvas.clipRect(0f, top - 2f * density, width.toFloat(), bottom + 2f * density)
        val step = width.toFloat() / (Engine.HISTORY - 2)
        val xs = FloatArray(Engine.HISTORY) { i ->
            width + (i - (Engine.HISTORY - 1) + shift) * step
        }
        fun y(value: Float) = bottom - (value / peak).toFloat() * graphHeight

        val ysLower = FloatArray(Engine.HISTORY) { y(0f) }

        stacked.forEachIndexed { index, upper ->
            val ysUpper = FloatArray(Engine.HISTORY) { y(upper[it]) }

            path.reset()
            smoothPath(path, xs, ysUpper, Engine.HISTORY, reverse = false)
            smoothPath(path, xs, ysLower, Engine.HISTORY, reverse = true)
            path.close()

            paint.color = colorFor(series[index].first)
            paint.alpha = 50
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)

            path.reset()
            smoothPath(path, xs, ysUpper, Engine.HISTORY, reverse = false)
            paint.alpha = 255
            paint.strokeWidth = 2.5f * density
            paint.style = Paint.Style.STROKE
            canvas.drawPath(path, paint)

            for (i in 0 until Engine.HISTORY) {
                ysLower[i] = ysUpper[i]
            }
        }
        canvas.restoreToCount(save)
    }

    /**
     * Monotone cubic spline interpolation (Fritsch-Carlson) through data points.
     * Preserves real network throughput spikes while eliminating stair-stepping and overshoots.
     */
    private fun smoothPath(path: Path, xs: FloatArray, ys: FloatArray, n: Int, reverse: Boolean) {
        if (n < 2) return
        val deltas = FloatArray(n - 1)
        for (i in 0 until n - 1) {
            val dx = xs[i + 1] - xs[i]
            deltas[i] = if (dx != 0f) (ys[i + 1] - ys[i]) / dx else 0f
        }
        val tangents = FloatArray(n)
        tangents[0] = deltas[0]
        tangents[n - 1] = deltas[n - 2]
        for (i in 1 until n - 1) {
            val d0 = deltas[i - 1]
            val d1 = deltas[i]
            if (d0 * d1 <= 0f) {
                tangents[i] = 0f
            } else {
                tangents[i] = 2f * d0 * d1 / (d0 + d1)
            }
        }

        if (!reverse) {
            path.moveTo(xs[0], ys[0])
            for (i in 0 until n - 1) {
                val hx = (xs[i + 1] - xs[i]) / 3f
                path.cubicTo(
                    xs[i] + hx, ys[i] + tangents[i] * hx,
                    xs[i + 1] - hx, ys[i + 1] - tangents[i + 1] * hx,
                    xs[i + 1], ys[i + 1],
                )
            }
        } else {
            path.lineTo(xs[n - 1], ys[n - 1])
            for (i in n - 2 downTo 0) {
                val hx = (xs[i + 1] - xs[i]) / 3f
                path.cubicTo(
                    xs[i + 1] - hx, ys[i + 1] - tangents[i + 1] * hx,
                    xs[i] + hx, ys[i] + tangents[i] * hx,
                    xs[i], ys[i],
                )
            }
        }
    }
}
