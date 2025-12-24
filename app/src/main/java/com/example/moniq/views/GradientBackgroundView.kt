package com.example.moniq.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class GradientBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // default base colors (will be replaced by setColors)
    private var colors = intArrayOf(
        Color.parseColor("#1a1a1a"),
        Color.parseColor("#0d0d0d"),
        Color.parseColor("#1a1a1a")
    )

    // Target colors for smooth transition
    private var targetColors = colors.copyOf()

    // precomputed smooth arrays used by the shader
    private var stepsPerSegment = 18
    private var smoothColors: IntArray = colors
    private var targetSmoothColors: IntArray = colors
    private var smoothPositions: FloatArray? = null

    private var gradientAnimator: ValueAnimator? = null
    private var animatedFraction = 0f
    private var animationStartTime: Long = 0L
    private var animationDuration: Long = 30_000L // 30 seconds for one full rotation
    
    // Color transition variables
    private var colorTransitionAnimator: ValueAnimator? = null
    private var colorTransitionProgress = 1f // 1f means transition complete

    init {
        startAnimation()
    }

    /**
     * Set dominant colors with smooth transition from current colors.
     */
    fun setColors(dominantColors: List<Int>) {
        if (dominantColors.isEmpty()) return

        // Build base color list that ends with the first color for seamless tiling
        val base = when {
            dominantColors.size == 1 -> {
                val c = dominantColors[0]
                intArrayOf(darken(c, 0.85f), darken(c, 0.75f), darken(c, 0.85f))
            }
            dominantColors.size == 2 -> {
                val a = dominantColors[0]
                val b = dominantColors[1]
                intArrayOf(darken(a, 0.85f), darken(b, 0.75f), darken(a, 0.85f))
            }
            else -> {
                val a = dominantColors[0]
                val b = dominantColors[1]
                val c = dominantColors[2]
                intArrayOf(darken(a, 0.85f), darken(b, 0.80f), darken(c, 0.75f), darken(a, 0.85f))
            }
        }

        // Build target smooth gradient
        val (cols, poss) = buildSmoothGradient(base, stepsPerSegment)
        targetSmoothColors = cols
        targetColors = base
        smoothPositions = poss

        // Start color transition animation
        startColorTransition()
    }

    private fun startColorTransition() {
        colorTransitionAnimator?.cancel()
        colorTransitionProgress = 0f
        
        colorTransitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L // 1.5 second smooth transition
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                colorTransitionProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startAnimation() {
        gradientAnimator?.cancel()
        animationStartTime = SystemClock.uptimeMillis()

        gradientAnimator = ValueAnimator.ofInt(0, 1000).apply {
            duration = animationDuration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                val currentTime = SystemClock.uptimeMillis()
                val elapsed = (currentTime - animationStartTime) % animationDuration
                this@GradientBackgroundView.animatedFraction = elapsed.toFloat() / animationDuration.toFloat()
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        gradientAnimator?.cancel()
        colorTransitionAnimator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val centerX = w / 2f
        val centerY = h / 2f
        
        // Calculate radius to cover entire view (diagonal distance)
        val radius = kotlin.math.sqrt((w * w + h * h).toDouble()).toFloat() * 0.8f

        // Interpolate between current and target colors during transition
        val usedColors = if (colorTransitionProgress < 1f) {
            // Transition in progress - interpolate colors
            val maxSize = smoothColors.size.coerceAtLeast(targetSmoothColors.size)
            IntArray(maxSize) { idx ->
                val currentColor = smoothColors.getOrNull(idx) ?: smoothColors.last()
                val targetColor = targetSmoothColors.getOrNull(idx) ?: targetSmoothColors.last()
                lerpColor(currentColor, targetColor, colorTransitionProgress)
            }
        } else {
            // Transition complete - use target colors and update current
            if (smoothColors.size != targetSmoothColors.size || !smoothColors.contentEquals(targetSmoothColors)) {
                smoothColors = targetSmoothColors.copyOf()
                colors = targetColors.copyOf()
            }
            smoothColors
        }

        val usedPositions = smoothPositions ?: run {
            val count = usedColors.size
            FloatArray(count) { idx -> idx.toFloat() / (count - 1).toFloat() }
        }

        // Calculate animated offset position in a circular path
        val angle = animatedFraction * 360f // Simple continuous rotation
        val angleRad = Math.toRadians(angle.toDouble())
        
        // Move the gradient center in a circular path
        val orbitRadius = kotlin.math.min(w, h) * 0.3f
        val offsetX = centerX + (cos(angleRad) * orbitRadius).toFloat()
        val offsetY = centerY + (sin(angleRad) * orbitRadius).toFloat()

        // Create a radial gradient from the orbiting center point
        val gradient = RadialGradient(
            offsetX,
            offsetY,
            radius,
            usedColors,
            usedPositions,
            Shader.TileMode.CLAMP
        )

        paint.shader = gradient
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    /**
     * Build a smooth gradient by interpolating `stepsPerSegment` colors between each adjacent pair
     * of base colors. Returns a Pair(colorsArray, positionsArray).
     */
    private fun buildSmoothGradient(baseColors: IntArray, stepsPerSegmentInput: Int): Pair<IntArray, FloatArray> {
        val steps = if (stepsPerSegmentInput < 1) 1 else stepsPerSegmentInput
        if (baseColors.size < 2) {
            val cols = baseColors.copyOf()
            val poss = FloatArray(cols.size) { idx -> idx.toFloat() / (cols.size - 1).coerceAtLeast(1).toFloat() }
            return Pair(cols, poss)
        }

        val segments = baseColors.size - 1
        val tempList = ArrayList<Int>(segments * steps + 1)

        for (s in 0 until segments) {
            val startColor = baseColors[s]
            val endColor = baseColors[s + 1]
            for (step in 0 until steps) {
                val t = step.toFloat() / steps.toFloat()
                tempList.add(lerpColor(startColor, endColor, t))
            }
        }
        tempList.add(baseColors.last())

        val cols = tempList.toIntArray()
        val count = cols.size
        val poss = FloatArray(count) { idx -> idx.toFloat() / (count - 1).toFloat() }

        return Pair(cols, poss)
    }

    /**
     * Linear interpolate between two ARGB colors.
     * t in [0,1]
     */
    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val ta = 1f - t
        val ar = (Color.alpha(a) * ta + Color.alpha(b) * t).roundToInt()
        val rr = (Color.red(a) * ta + Color.red(b) * t).roundToInt()
        val gr = (Color.green(a) * ta + Color.green(b) * t).roundToInt()
        val br = (Color.blue(a) * ta + Color.blue(b) * t).roundToInt()
        return Color.argb(ar.coerceIn(0, 255), rr.coerceIn(0, 255), gr.coerceIn(0, 255), br.coerceIn(0, 255))
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = ((Color.red(color) * factor).toInt().coerceIn(0, 255))
        val g = ((Color.green(color) * factor).toInt().coerceIn(0, 255))
        val b = ((Color.blue(color) * factor).toInt().coerceIn(0, 255))
        return Color.rgb(r, g, b)
    }
}