package com.example.moniq.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.moniq.lyrics.SyllableLine
import android.text.StaticLayout
import android.text.TextPaint

/** Apple Music-style lyrics view with gradient fill and smooth animations */
class LyricsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
        ScrollView(context, attrs) {
    private val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }

    private data class LineInfo(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val syllableOffsets: List<Int>,
        val syllables: List<com.example.moniq.lyrics.Syllable>,
        val transliteration: String?,
        val translation: String?,
    )

    private val lines = mutableListOf<LineInfo>()
    private val textViews = mutableListOf<FillTextView>()
    private val backgroundViews = mutableListOf<FillTextView>()
    private val translationViews = mutableListOf<TextView>()
    private val lineContainers = mutableListOf<LinearLayout>()
    private val activeIndices = mutableSetOf<Int>()
    private var lastPrimaryIdx: Int = -1

    private var accentColor: Int =
            try {
                context.getColor(com.example.moniq.R.color.purple_700)
            } catch (_: Exception) {
                Color.MAGENTA
            }
    
    private var showTransliteration = true
    private var showTranslation = true
    private var onSeekListener: ((Long) -> Unit)? = null

    init {
        addView(container)
        isVerticalScrollBarEnabled = false
    }

    fun setOnSeekListener(listener: (Long) -> Unit) {
        onSeekListener = listener
    }

    fun setLines(syllableLines: List<SyllableLine>) {
        container.removeAllViews()
        lines.clear()
        textViews.clear()
        backgroundViews.clear()
        translationViews.clear()
        lineContainers.clear()

        for (sl in syllableLines) {
            // Separate main and background syllables
            val mainSyllables = sl.syllables.filter { !it.isBackground }
            val bgSyllables = sl.syllables.filter { it.isBackground }
            
            // Build main text
            val mainSb = StringBuilder()
            val mainTranslitSb = StringBuilder()
            val mainOffsets = mutableListOf<Int>()
            for (s in mainSyllables) {
                mainOffsets.add(mainSb.length)
                mainSb.append(s.text)
                if (s.transliteration != null) {
                    mainTranslitSb.append(s.transliteration)
                }
            }
            
            val mainLineText = mainSb.toString()
            val mainTranslitText = if (mainTranslitSb.isNotEmpty()) mainTranslitSb.toString() else null
            
           val start = sl.startMs
val end = if (mainSyllables.isNotEmpty()) {
    // Use ONLY main syllables for line end time
    val last = mainSyllables.last()
    (last.startMs + last.durationMs).coerceAtLeast(start + 100L)
} else start + 2000L

            lines.add(
                LineInfo(
                    start,
                    end,
                    mainLineText,
                    mainOffsets,
                    mainSyllables,
                    mainTranslitText,
                    sl.translation
                )
            )

           val lineContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.START
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        bottomMargin = (32 * resources.displayMetrics.density).toInt()
    }
    isClickable = true
    isFocusable = true
    val ripple = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(accentColor and 0x40FFFFFF),
        null,
        null
    )
    background = ripple
    setOnClickListener { onSeekListener?.invoke(start) }
    // No pivot on outer container - it doesn't get animated
}
lineContainers.add(lineContainer)

// Create a container for ONLY the main lyrics that will be scaled
val mainLyricContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.START
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    pivotX = 0f  // Scale from left
    pivotY = 0f
    alpha = 0.4f  // Start dimmed
    tag = "main_container"  // Tag to identify for animations
}

// Main lyrics text view
if (mainLineText.isNotBlank()) {
    val tv = FillTextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (24 * resources.displayMetrics.density).toInt()
        }
        gravity = Gravity.START
        maxLines = Int.MAX_VALUE
        isSingleLine = false
        baseColor = Color.WHITE
        baseAlpha = 0.35f
        fillColor = Color.WHITE
        fillAlpha = 1.0f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
        typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        text = mainLineText
        syllables = mainSyllables
        syllableOffsets = mainOffsets
        showRomanization = showTransliteration
        val padding = (16 * resources.displayMetrics.density).toInt()
        setPadding(0, padding, 0, padding * 2)
    }
    textViews.add(tv)
    mainLyricContainer.addView(tv)
} else {
    textViews.add(FillTextView(context).apply { visibility = GONE })
}

lineContainer.addView(mainLyricContainer)

// Background vocals with independent container
if (bgSyllables.isNotEmpty()) {
    val bgSb = StringBuilder()
    val bgOffsets = mutableListOf<Int>()
    for (s in bgSyllables) {
        bgOffsets.add(bgSb.length)
        bgSb.append(s.text)
    }
    val bgText = bgSb.toString()
    
    // Independent container for background vocals
    val bgContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (8 * resources.displayMetrics.density).toInt()
        }
        pivotX = 0f  // Scale from left, not center!
        pivotY = 0f
        alpha = 0.35f
        scaleX = 1.0f
        scaleY = 1.0f
        tag = "bg_container"  // Tag to identify for animations
    }
                
    val bgTv = FillTextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        gravity = Gravity.START
        maxLines = Int.MAX_VALUE
        isSingleLine = false
        baseColor = Color.WHITE
        baseAlpha = 0.3f
        fillColor = Color.WHITE
        fillAlpha = 0.85f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        text = bgText
        syllables = bgSyllables
        syllableOffsets = bgOffsets
        showRomanization = false
        val padding = (6 * resources.displayMetrics.density).toInt()
        setPadding(0, padding, 0, padding)
    }
    
    bgContainer.addView(bgTv)
    backgroundViews.add(bgTv)
    lineContainer.addView(bgContainer)  // Sibling of mainLyricContainer, not child
} else {
    backgroundViews.add(FillTextView(context).apply { visibility = GONE })
}

// Translation view
val translationTv = TextView(context).apply {
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    gravity = Gravity.START
    setTextColor(Color.WHITE)
    alpha = 0.35f
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    text = sl.translation ?: ""
    val isDuplicate = !sl.translation.isNullOrEmpty() &&
        sl.translation.trim().equals(mainLineText.trim(), ignoreCase = true)
    visibility = if (sl.translation.isNullOrEmpty() || !showTranslation || isDuplicate)
        GONE
    else VISIBLE
    val padding = (8 * resources.displayMetrics.density).toInt()
    setPadding(0, 0, 0, padding)
}
translationViews.add(translationTv)
lineContainer.addView(translationTv)

container.addView(lineContainer)
        }
        activeIndices.clear()
        lastPrimaryIdx = -1
    }

    fun clear() {
        container.removeAllViews()
        lines.clear()
        textViews.clear()
        backgroundViews.clear()
        translationViews.clear()
        lineContainers.clear()
        activeIndices.clear()
        lastPrimaryIdx = -1
    }

    fun updatePosition(posMs: Long) {
    if (lines.isEmpty()) return

    val newActiveIndices = mutableSetOf<Int>()
    for ((i, l) in lines.withIndex()) {
        if (posMs >= l.startMs && posMs < l.endMs) {
            newActiveIndices.add(i)
        }
    }

    if (newActiveIndices.isEmpty() && posMs >= lines.last().endMs) {
        newActiveIndices.add(lines.size - 1)
    }

    // Handle MAIN lyric transitions (animate mainLyricContainer)
    for (idx in lineContainers.indices) {
        val wasActive = idx in activeIndices
        val isActive = idx in newActiveIndices

        if (wasActive != isActive) {
            val lineContainer = lineContainers[idx]
            // Find the main lyric container (first child with tag "main_container")
            val mainContainer = lineContainer.findViewWithTag<LinearLayout>("main_container")
            
            mainContainer?.animate()?.cancel()

            if (isActive) {
                // Main lyric active - scale up
                mainContainer?.animate()
                    ?.alpha(1.0f)
                    ?.scaleX(1.04f)
                    ?.scaleY(1.04f)
                    ?.setDuration(300)
                    ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                    ?.start()
            } else {
                // Main lyric inactive - scale down
                mainContainer?.animate()
                    ?.alpha(0.4f)
                    ?.scaleX(1.0f)
                    ?.scaleY(1.0f)
                    ?.setDuration(250)
                    ?.setInterpolator(android.view.animation.AccelerateInterpolator())
                    ?.start()
            }
        }
    }

    // Handle background vocals COMPLETELY independently
    for (idx in backgroundViews.indices) {
        val bgTv = backgroundViews[idx]
        if (bgTv.visibility != VISIBLE || bgTv.syllables.isEmpty()) continue

        // Get the bg container (parent of bgTv)
        val bgContainer = bgTv.parent as? LinearLayout
        if (bgContainer == null || bgContainer.tag != "bg_container") continue

        val bgSyllables = bgTv.syllables
        val bgStart = bgSyllables.first().startMs
        val bgEnd = bgSyllables.last().let { it.startMs + it.durationMs }

        val isBgActive = posMs >= bgStart && posMs < bgEnd
        val wasBgActive = bgContainer.getTag(com.example.moniq.R.id.bg_active_state) as? Boolean ?: false

        // Animate background vocal container independently
        if (isBgActive != wasBgActive) {
            bgContainer.setTag(com.example.moniq.R.id.bg_active_state, isBgActive)
            bgContainer.animate().cancel()
            
            if (isBgActive) {
                // Background vocal starts - scale up from LEFT
                bgContainer.animate()
                    .alpha(0.95f)
                    .scaleX(1.04f)  // Same scale as main lyrics
                    .scaleY(1.04f)
                    .setDuration(250)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            } else {
                // Background vocal ends - scale back down
                bgContainer.animate()
                    .alpha(0.35f)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .start()
            }
        }
        
        // Update background fill independently based on BG timing
        if (isBgActive) {
            bgTv.currentPosMs = posMs
            val bgProgress = calculateSyllableProgressForSyllables(bgSyllables, posMs)
            bgTv.fillProgress = bgProgress
        } else if (posMs >= bgEnd) {
            bgTv.fillProgress = 1.0f
        }
    }

    // Complete fill for removed lines
    val toRemove = activeIndices - newActiveIndices
    for (idx in toRemove) {
        if (idx in textViews.indices) {
            textViews[idx].fillProgress = 1.0f
        }
        if (idx in backgroundViews.indices) {
            backgroundViews[idx].fillProgress = 1.0f
        }
    }

    activeIndices.clear()
    activeIndices.addAll(newActiveIndices)

    // Update syllable fill progress for main lyrics
    for (idx in activeIndices) {
        if (idx in lines.indices) {
            val tv = textViews[idx]
            tv.currentPosMs = posMs   
            updateLineFill(idx, posMs)
        }
    }

    // Handle scrolling
    if (activeIndices.isNotEmpty()) {
        val primaryIdx = activeIndices.maxOrNull() ?: activeIndices.first()

        if (primaryIdx != lastPrimaryIdx && primaryIdx in lineContainers.indices) {
            lastPrimaryIdx = primaryIdx

            post {
                val lineContainer = lineContainers[primaryIdx]
                val target = (lineContainer.top - height / 3).coerceAtLeast(0)
                val currentScroll = scrollY

                android.animation.ValueAnimator.ofInt(currentScroll, target).apply {
                    duration = 400
                    interpolator = android.view.animation.DecelerateInterpolator(1.5f)
                    addUpdateListener { animator ->
                        scrollTo(0, animator.animatedValue as Int)
                    }
                    start()
                }
            }
        }
    }
}

    private fun updateLineFill(lineIndex: Int, posMs: Long) {
        val li = lines[lineIndex]
        val tv = textViews[lineIndex]
        val progress = calculateSyllableProgress(li, posMs)
        tv.fillProgress = progress
    }

    private fun calculateSyllableProgress(lineInfo: LineInfo, posMs: Long): Float {
    if (lineInfo.syllables.isEmpty()) return 0f

    var totalLength = 0f
    var filledLength = 0f

    for (syllable in lineInfo.syllables) {
        val syllableEnd = syllable.startMs + syllable.durationMs
        val length = syllable.text.length.toFloat()
        totalLength += length

        when {
            posMs < syllable.startMs -> continue
            posMs >= syllableEnd -> filledLength += length  // Full syllable
            else -> {
                // Partial syllable - add tiny lead to sync with romanization
                val adjustedPos = (posMs + 30).coerceAtMost(syllableEnd)  // 30ms lead
                val rawProgress = (adjustedPos - syllable.startMs).toFloat() / syllable.durationMs.toFloat()
                val progress = rawProgress.coerceIn(0f, 1f)
                filledLength += length * progress
            }
        }
    }

    // Ensure we can reach exactly 1.0
    val result = if (totalLength > 0) (filledLength / totalLength) else 0f
    return result.coerceIn(0f, 1f)
}

private fun calculateSyllableProgressForSyllables(
    syllables: List<com.example.moniq.lyrics.Syllable>,
    posMs: Long
): Float {
    if (syllables.isEmpty()) return 0f

    var totalLength = 0f
    var filledLength = 0f

    for (syllable in syllables) {
        val syllableEnd = syllable.startMs + syllable.durationMs
        val length = syllable.text.length.toFloat()
        totalLength += length

        when {
            posMs < syllable.startMs -> continue
            posMs >= syllableEnd -> filledLength += length
            else -> {
                val progress = ((posMs - syllable.startMs).toFloat() / syllable.durationMs.toFloat())
                    .coerceIn(0f, 1f)
                filledLength += length * progress
            }
        }
    }

    return if (totalLength > 0) (filledLength / totalLength).coerceIn(0f, 1f) else 0f
}

    fun setAccentColor(color: Int) {
        accentColor = color
    }

    fun setShowTransliteration(show: Boolean) {
        showTransliteration = show
        for (tv in textViews) {
            tv.showRomanization = show
            tv.invalidate()
        }
        requestLayout()
    }

    fun setShowTranslation(show: Boolean) {
        showTranslation = show
        for (i in translationViews.indices) {
            val tv = translationViews[i]
            val lineInfo = if (i < lines.size) lines[i] else null
            val hasContent = !lineInfo?.translation.isNullOrBlank()
            val isDuplicate = lineInfo?.let {
                !it.translation.isNullOrEmpty() &&
                    it.translation.trim().equals(it.text.trim(), ignoreCase = true)
            } ?: false
            tv.visibility = if (show && hasContent && !isDuplicate) VISIBLE else GONE
        }
        requestLayout()
    }

    /** Apple Music-style text view with gradient fill effect */
    inner class FillTextView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
    ) : androidx.appcompat.widget.AppCompatTextView(context, attrs) {

        var currentPosMs: Long = 0L
        var baseColor: Int = Color.WHITE
        var baseAlpha: Float = 0.35f
        var fillColor: Int = Color.WHITE
        var fillAlpha: Float = 1.0f
        var fillProgress: Float = 0f
            set(value) {
                val newValue = value.coerceIn(0f, 1f)
                if (Math.abs(field - newValue) > 0.001f) {
                    field = newValue
                    invalidate()
                }
            }

        var syllables: List<com.example.moniq.lyrics.Syllable> = emptyList()
        var syllableOffsets: List<Int> = emptyList()
        var showRomanization: Boolean = true

        private val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = true
            isDither = true
            isFilterBitmap = true
        }
        
        private val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = true
            isDither = true
            isFilterBitmap = true
        }

        private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = true
            isDither = true
        }

        private var cachedLayout: StaticLayout? = null
        private var lastText: String = ""
        private var lastWidth: Int = 0

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            cachedLayout = null
        }

        override fun setText(text: CharSequence?, type: BufferType?) {
            super.setText(text, type)
            cachedLayout = null
        }

        override fun onDraw(canvas: Canvas) {
            val textStr = text?.toString()
            if (textStr.isNullOrEmpty()) return

            // Update paint properties
            basePaint.color = baseColor
            basePaint.alpha = (baseAlpha * 255).toInt()
            basePaint.textSize = textSize
            basePaint.typeface = typeface

            fillPaint.color = fillColor
            fillPaint.alpha = (fillAlpha * 255).toInt()
            fillPaint.textSize = textSize
            fillPaint.typeface = typeface

            val availableWidth = width - paddingLeft - paddingRight

            // Create/reuse StaticLayout
            if (cachedLayout == null || lastText != textStr || lastWidth != availableWidth) {
                cachedLayout = StaticLayout.Builder.obtain(
                    textStr, 0, textStr.length, basePaint, availableWidth
                ).build()
                lastText = textStr
                lastWidth = availableWidth
            }

            val layout = cachedLayout ?: return

            canvas.save()
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())

            // Draw base text
            layout.draw(canvas)

         // Draw filled portion with gradient feather
if (fillProgress > 0f) {
    val totalChars = textStr.length
    val exactFillPos = fillProgress * totalChars
    val fullChars = exactFillPos.toInt().coerceIn(0, totalChars)
    
    if (fullChars > 0 || fillProgress > 0f) {
        // Find which line we're on
        val targetOffset = fullChars.coerceAtMost(totalChars - 1)
        val line = layout.getLineForOffset(targetOffset)
        val lineTop = layout.getLineTop(line).toFloat()
        val lineBottom = layout.getLineBottom(line).toFloat()
        
        // Calculate the exact pixel position for the fill
val currentCharX = if (fullChars > 0) {
    layout.getPrimaryHorizontal(fullChars - 1)
} else {
    0f
}
val nextCharX = if (fullChars < totalChars) {
    layout.getPrimaryHorizontal(fullChars)
} else {
    // Ensure we cover the entire last character
    layout.getLineRight(layout.getLineForOffset(totalChars - 1))
}

// Interpolate within the current character
val charProgress = exactFillPos - fullChars
val fillX = (currentCharX + (nextCharX - currentCharX) * charProgress).coerceAtLeast(currentCharX)

// Smoothly approach the end - add slight extension when near completion
val lineRight = layout.getLineRight(layout.getLineForOffset(totalChars - 1))
val actualFillX = if (fillProgress >= 0.95f) {
    // Smoothly interpolate to line end in the last 5%
    val endProgress = (fillProgress - 0.95f) / 0.05f
    fillX + (lineRight - fillX) * endProgress
} else {
    fillX
}
        
        // Draw solid fill up to the feather start
val featherWidth = (basePaint.textSize * 0.6f).coerceAtLeast(15f)
val solidFillEnd = (actualFillX - featherWidth).coerceAtLeast(0f)

canvas.save()

// Draw solid filled portion
if (solidFillEnd > 0f) {
    canvas.clipRect(0f, lineTop, solidFillEnd, lineBottom)
    val solidLayout = StaticLayout.Builder.obtain(
        textStr, 0, textStr.length, fillPaint, availableWidth
    ).build()
    solidLayout.draw(canvas)
}

canvas.restore()
canvas.save()

// Draw gradient feathered edge
if (actualFillX > solidFillEnd) {
    val shader = LinearGradient(
        solidFillEnd, 0f,
        actualFillX, 0f,
        intArrayOf(
            fillColor or 0xFF000000.toInt(),  // Full alpha at start
            fillColor and 0x00FFFFFF   // Transparent at end
        ),
        null,
        Shader.TileMode.CLAMP
    )
    
    fillPaint.shader = shader
    canvas.clipRect(solidFillEnd, lineTop, actualFillX, lineBottom)
    
    val gradientLayout = StaticLayout.Builder.obtain(
        textStr, 0, textStr.length, fillPaint, availableWidth
    ).build()
    gradientLayout.draw(canvas)
    
    fillPaint.shader = null
}
        
        canvas.restore()
    }
}

            canvas.restore()

            // Draw romanization with fill effect
            if (showRomanization && syllables.isNotEmpty()) {
                drawRomanization(canvas, textStr, currentPosMs)
            }
        }

       private fun drawRomanization(canvas: Canvas, textStr: String, posMs: Long) {
    if (syllables.isEmpty()) return
    
    val romanBasePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = (0.3f * 255).toInt()
        textSize = this@FillTextView.textSize * 0.5f
        typeface = Typeface.DEFAULT
    }

    val romanFillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = (0.85f * 255).toInt()
        textSize = this@FillTextView.textSize * 0.5f
        typeface = Typeface.DEFAULT
    }

    val lineHeight = basePaint.fontMetrics.let { it.descent - it.ascent }
    val romanY = paddingTop + lineHeight + (lineHeight * 0.65f)

    canvas.save()
    canvas.translate(paddingLeft.toFloat(), 0f)

    var lastRomanEnd = 0f  // Track where the last romanization ended
    val spacing = romanBasePaint.textSize * 0.15f  // Small gap between romanizations

    for ((index, syllable) in syllables.withIndex()) {
        val romanText = syllable.transliteration ?: continue
        if (romanText.isEmpty() || romanText.equals(syllable.text, ignoreCase = true)) continue

        val offset = syllableOffsets.getOrNull(index) ?: continue
        val idealX = basePaint.measureText(textStr, 0, offset.coerceAtMost(textStr.length))
        
        // Prevent overlap: use the greater of ideal position or last end + spacing
        val x = maxOf(idealX, lastRomanEnd + spacing)
        
        // Update last end position
        val romanWidth = romanBasePaint.measureText(romanText)
        lastRomanEnd = x + romanWidth

        // Syllable-by-syllable fill matching Korean timing
        val syllableEnd = syllable.startMs + syllable.durationMs
        val syllableProgress = when {
            posMs < syllable.startMs -> 0f
            posMs >= syllableEnd -> 1f
            else -> ((posMs - syllable.startMs).toFloat() / syllable.durationMs.toFloat()).coerceIn(0f, 1f)
        }

        // Always draw base text
        canvas.drawText(romanText, x, romanY, romanBasePaint)
        
        // Draw filled portion with gradient feather only if progress > 0
        if (syllableProgress > 0.01f) {
            val fullFillWidth = romanWidth * syllableProgress
            
            // Smooth completion at 95%+ like main lyrics
            val lineRight = romanWidth
            val actualFillWidth = if (syllableProgress >= 0.95f) {
                val endProgress = (syllableProgress - 0.95f) / 0.05f
                fullFillWidth + (lineRight - fullFillWidth) * endProgress
            } else {
                fullFillWidth
            }
            
            if (actualFillWidth > 0.5f) {
                val featherWidth = (romanBasePaint.textSize * 0.35f).coerceAtLeast(6f)
                val solidEnd = (actualFillWidth - featherWidth).coerceAtLeast(0f)
                
                canvas.save()
                
                // Draw solid fill portion
                if (solidEnd > 0.5f) {
                    canvas.clipRect(
                        x, 
                        romanY - romanBasePaint.textSize, 
                        x + solidEnd, 
                        romanY + romanBasePaint.descent()
                    )
                    canvas.drawText(romanText, x, romanY, romanFillPaint)
                    canvas.restore()
                    canvas.save()
                }
                
                // Draw gradient feather edge
                if (actualFillWidth > solidEnd) {
                    val shader = LinearGradient(
                        x + solidEnd, romanY,
                        x + actualFillWidth, romanY,
                        intArrayOf(
                            Color.WHITE or 0xFF000000.toInt(),
                            Color.WHITE and 0x00FFFFFF
                        ),
                        null,
                        Shader.TileMode.CLAMP
                    )
                    
                    val tempPaint = TextPaint(romanFillPaint)
                    tempPaint.shader = shader
                    
                    canvas.clipRect(
                        x + solidEnd, 
                        romanY - romanBasePaint.textSize, 
                        x + actualFillWidth, 
                        romanY + romanBasePaint.descent()
                    )
                    canvas.drawText(romanText, x, romanY, tempPaint)
                }
                
                canvas.restore()
            }
        }
    }

    canvas.restore()
}
    } 
}