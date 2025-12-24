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

    /** Lyrics view with smooth fill animation effect and Apple Music-style scrolling */
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
                val translation: String?
        )

        private val lines = mutableListOf<LineInfo>()
        private val textViews = mutableListOf<FillTextView>()
        private val transliterationViews = mutableListOf<FillTextView>()
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
    transliterationViews.clear()
    translationViews.clear()
    lineContainers.clear()

            for (sl in syllableLines) {
        val sb = StringBuilder()
        val translitSb = StringBuilder()
        val offsets = mutableListOf<Int>()
        for (s in sl.syllables) {
            offsets.add(sb.length)
            sb.append(s.text)
            if (s.transliteration != null) {
                translitSb.append(s.transliteration)
            }
        }
        val lineText = sb.toString()
                val translitText = if (translitSb.isNotEmpty()) translitSb.toString() else null
                val start = sl.startMs
val end =
        if (sl.syllables.isNotEmpty()) {
            val last = sl.syllables.last()
            // Subtract 250ms so the line completes earlier, giving last characters time to animate up
            (last.startMs + last.durationMs).coerceAtLeast(start + 100L)
        } else start + 2000L

                lines.add(
                        LineInfo(
                                start,
                                end,
                                lineText,
                                offsets,
                                sl.syllables,
                                translitText,
                                sl.translation
                        )
                )

                val lineContainer =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.START
                            layoutParams =
                                    LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                            isClickable = true
                            isFocusable = true
                            val ripple =
                                    android.graphics.drawable.RippleDrawable(
                                            android.content.res.ColorStateList.valueOf(
                                                    accentColor and 0x40FFFFFF
                                            ),
                                            null,
                                            null
                                    )
                            background = ripple
                            setOnClickListener { onSeekListener?.invoke(start) }
                        }
                lineContainers.add(lineContainer)
                lineContainer.post {
                    lineContainer.pivotX = 0f
                    lineContainer.pivotY = lineContainer.height / 2f
                }

                val tv =
                        FillTextView(context).apply {
                            layoutParams =
                                    LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                            gravity = Gravity.START
                            maxLines = Int.MAX_VALUE
                            isSingleLine = false
                            baseColor = Color.WHITE
                            baseAlpha = 0.3f
                            fillColor = Color.WHITE
                            fillAlpha = 1.0f
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                            typeface = Typeface.DEFAULT_BOLD
                            text = lineText
                            syllables = sl.syllables
                            syllableOffsets = offsets
                            showRomanization = showTransliteration
                            val padding = (16 * resources.displayMetrics.density).toInt()
                            setPadding(0, padding, 0, padding * 2)
                        }
                textViews.add(tv)
                lineContainer.addView(tv)

                val translitTv =
                        FillTextView(context).apply {
                            layoutParams =
                                    LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                            visibility = GONE
                        }
                transliterationViews.add(translitTv)
                lineContainer.addView(translitTv)

                val translationTv =
                        TextView(context).apply {
                            layoutParams =
                                    LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                            gravity = Gravity.START
                            setTextColor(Color.WHITE)
                            alpha = 0.3f
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                            typeface = Typeface.DEFAULT_BOLD
                            text = sl.translation ?: ""
                            val isDuplicate =
                                    !sl.translation.isNullOrEmpty() &&
                                            sl.translation
                                                    .trim()
                                                    .equals(lineText.trim(), ignoreCase = true)
                            visibility =
                                    if ((sl.translation.isNullOrEmpty() ||
                                                    !showTranslation ||
                                                    isDuplicate)
                                    )
                                            GONE
                                    else VISIBLE
                            val padding = (8 * resources.displayMetrics.density).toInt()
                            setPadding(0, padding, 0, padding)
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
        transliterationViews.clear()
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

            val toRemove = activeIndices - newActiveIndices

            if (newActiveIndices.isEmpty()) {
                lastPrimaryIdx = -1
            }

            for (idx in lineContainers.indices) {
                val wasActive = idx in activeIndices
                val isActive = idx in newActiveIndices
                if (wasActive == isActive) continue

                val container = lineContainers[idx]
                container.animate().cancel()

                if (isActive) {
                    container
                            .animate()
                            .alpha(1.0f)
                            .scaleX(1.05f)
                            .scaleY(1.05f)
                            .setDuration(380)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
                            .withStartAction { container.setLayerType(LAYER_TYPE_NONE, null) }
                            .start()
                } else {
                    container
                            .animate()
                            .alpha(0.45f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(300)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .withStartAction {
                                container.setLayerType(
                                        LAYER_TYPE_SOFTWARE,
                                        Paint().apply {
                                            maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
                                        }
                                )
                            }
                            .start()
                }
            }

            if (toRemove.isNotEmpty()) {
    for (idx in toRemove) {
        if (idx in textViews.indices) {
            val tv = textViews[idx]
            // Set to 1.15f to ensure last 2-3 characters complete their upward animation
            // (animationWindow is 0.1f, so we need to exceed 1.0f by at least that much)
            tv.animateFill().fillProgress(1.15f).setDuration(200).start()
        }
    }
}

            activeIndices.clear()
            activeIndices.addAll(newActiveIndices)
            for (idx in activeIndices) {
                if (idx in lines.indices) updateLineFill(idx, posMs)
            }

            if (activeIndices.isNotEmpty()) {
                val primaryIdx = activeIndices.maxOrNull() ?: activeIndices.first()

                if (primaryIdx in textViews.indices && primaryIdx != lastPrimaryIdx) {
                    android.util.Log.d("LyricsView", "Line changed from $lastPrimaryIdx to $primaryIdx")
                    lastPrimaryIdx = primaryIdx

                    val tv = textViews[primaryIdx]
                    post {
                        val lineContainer = tv.parent as? ViewGroup
                        if (lineContainer != null) {
                            val target = (lineContainer.top - height / 3).coerceAtLeast(0)
                            val currentScroll = scrollY

                            android.util.Log.d("LyricsView", "Scrolling from $currentScroll to $target")

                            android.animation.ValueAnimator.ofInt(currentScroll, target).apply {
                                duration = 450
                                interpolator = android.view.animation.OvershootInterpolator(0.6f)
                                addUpdateListener { animator ->
                                    scrollTo(0, animator.animatedValue as Int)
                                }
                                start()
                            }

                            // Reset any previous translations for smooth scrolling
    for (i in lineContainers.indices) {
        if (i != primaryIdx) {
            lineContainers[i].translationY = 0f
        }
    }
                        }
                    }
                }
            } else {
                lastPrimaryIdx = -1
            }
        }

        private fun updateLineFill(lineIndex: Int, posMs: Long) {
            val li = lines[lineIndex]
            val tv = textViews[lineIndex]

            val syllableProgress = calculateSyllableProgress(li, posMs)
            tv.animateFill().fillProgress(syllableProgress).setDuration(200).start()
        }

        private fun calculateSyllableProgress(lineInfo: LineInfo, posMs: Long): Float {
            if (lineInfo.syllables.isEmpty()) return 0f

            var totalChars = 0
            var filledChars = 0

            for (syllable in lineInfo.syllables) {
                val syllableEnd = syllable.startMs + syllable.durationMs
                val syllableLength = syllable.text.length
                totalChars += syllableLength

                when {
                    posMs < syllable.startMs -> continue
                    posMs >= syllableEnd -> filledChars += syllableLength
                    else -> {
                        val syllableProgress =
                                ((posMs - syllable.startMs).toFloat() /
                                                syllable.durationMs.toFloat())
                                        .coerceIn(0f, 1f)
                        filledChars += (syllableLength * syllableProgress).toInt()
                    }
                }
            }

            return if (totalChars > 0) (filledChars.toFloat() / totalChars.toFloat()).coerceIn(0f, 1f)
                    else 0f
        }

        fun setAccentColor(color: Int) {
            try {
                accentColor = color
            } catch (_: Exception) {}
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
                val isDuplicate =
                        lineInfo?.let {
                            !it.translation.isNullOrEmpty() &&
                                    it.translation.trim().equals(it.text.trim(), ignoreCase = true)
                        }
                                ?: false
                tv.visibility = if (show && hasContent && !isDuplicate) VISIBLE else GONE
            }
            requestLayout()
        }

        inner class FillTextView
        @JvmOverloads
        constructor(context: Context, attrs: AttributeSet? = null) :
                androidx.appcompat.widget.AppCompatTextView(context, attrs) {

            var baseColor: Int = Color.WHITE
            var baseAlpha: Float = 0.3f
            var fillColor: Int = Color.WHITE
            var fillAlpha: Float = 1.0f
            var fillProgress: Float = 0f
    set(value) {
        field = value.coerceAtLeast(0f)  // Only prevent negative values
        invalidate()
    }

            private val charOffsetY = 3f * resources.displayMetrics.density

            var syllables: List<com.example.moniq.lyrics.Syllable> = emptyList()
            var syllableOffsets: List<Int> = emptyList()
            var showRomanization: Boolean = true

            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)

            fun animateFill(): FillAnimator {
                return FillAnimator(this)
            }

            override fun onDraw(canvas: Canvas) {
                if (text.isNullOrEmpty() || syllables.isEmpty()) {
                    super.onDraw(canvas)
                    return
                }

                val textStr = text.toString()

                canvas.save()
                canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())

                basePaint.color = baseColor
                basePaint.alpha = (baseAlpha * 255).toInt()
                basePaint.textSize = textSize
                basePaint.typeface = typeface
                basePaint.isAntiAlias = true

                fillPaint.color = fillColor
                fillPaint.alpha = (fillAlpha * 255).toInt()
                fillPaint.textSize = textSize
                fillPaint.typeface = typeface
                fillPaint.isAntiAlias = true

                val romanGreyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                romanGreyPaint.color = Color.WHITE
                romanGreyPaint.alpha = (0.3f * 255).toInt()
                romanGreyPaint.textSize = textSize * 0.5f
                romanGreyPaint.typeface = Typeface.DEFAULT_BOLD

                val romanWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG)
                romanWhitePaint.color = Color.WHITE
                romanWhitePaint.alpha = 255
                romanWhitePaint.textSize = textSize * 0.5f
                romanWhitePaint.typeface = Typeface.DEFAULT_BOLD

                var xPos = 0f
    var yPos = basePaint.fontMetrics.let { -it.ascent }
    val lineHeight = basePaint.fontMetrics.let { it.descent - it.ascent }
    val romanYOffset = lineHeight * 0.85f
    // Calculate actual available width
    val maxWidth = (width - paddingLeft - paddingRight).toFloat()
    android.util.Log.d("LyricsView", "Width: $width, Padding L/R: $paddingLeft/$paddingRight, MaxWidth: $maxWidth")

    for ((index, syllable) in syllables.withIndex()) {
        val syllableText = syllable.text
        val syllableWidth = basePaint.measureText(syllableText)
        
        // Check if adding this syllable would exceed the line width
        if (xPos > 0f && xPos + syllableWidth > maxWidth) {
            xPos = 0f
            yPos += lineHeight * 1.5f // Move to next line with spacing
        }
        
        val romanText =
                if (showRomanization && !syllable.transliteration.isNullOrEmpty()) {
                    syllable.transliteration
                } else ""


        val offset = if (index < syllableOffsets.size) syllableOffsets[index] else 0
        val syllableLength = syllableText.length
        val syllableEndOffset = offset + syllableLength
        
        val totalChars = textStr.length
        val syllableStartProgress = (offset.toFloat() / totalChars.toFloat()).coerceIn(0f, 1f)
        val syllableEndProgress = (syllableEndOffset.toFloat() / totalChars.toFloat()).coerceIn(0f, 1f)
        
        // Calculate smooth fill progress for this syllable (0f to 1f)
        val syllableFillProgress = when {
            fillProgress <= syllableStartProgress -> 0f
            fillProgress >= syllableEndProgress -> 1f
            else -> ((fillProgress - syllableStartProgress) / (syllableEndProgress - syllableStartProgress)).coerceIn(0f, 1f)
        }

        // Smooth upward movement (reduced from 12f to 4f for subtler effect)
        val syllableUpOffset = -3f * resources.displayMetrics.density * syllableFillProgress

        // Draw syllable character by character for upward movement, but keep fill at syllable level
    val syllableStartX = xPos

    // First pass: draw base text and calculate positions
    val charPositions = mutableListOf<Pair<String, Float>>()
    for (charIndex in syllableText.indices) {
        val char = syllableText[charIndex].toString()
        val charWidth = basePaint.measureText(char)
        
        if (xPos > 0f && xPos + charWidth > maxWidth) {
            xPos = 0f
            yPos += lineHeight * 1.5f
        }
        
        charPositions.add(Pair(char, xPos))
        xPos += charWidth
    }

    // Reset to start position for actual drawing
    xPos = syllableStartX

    // Draw each character with individual upward movement
    for ((charIndex, charPos) in charPositions.withIndex()) {
        val (char, charX) = charPos
        
        // Calculate this character's position in the syllable for smooth upward wave
        val charInSyllableProgress = charIndex.toFloat() / syllableText.length.toFloat()
        val charGlobalOffset = offset + charIndex
        val charProgress = (charGlobalOffset.toFloat() / totalChars.toFloat()).coerceIn(0f, 1f)
        
        // Calculate upward offset based on fill progress reaching this character
        val progressPastChar = (fillProgress - charProgress).coerceAtLeast(0f)
val animationWindow = 0.15f
val charUpOffset = -3f * resources.displayMetrics.density * (progressPastChar / animationWindow).coerceIn(0f, 1f)
        
        // Draw base (grey) character
        canvas.drawText(char, charX, yPos + charUpOffset, basePaint)
    }

    // Now draw the fill effect as a smooth gradient across the entire syllable
    if (syllableFillProgress > 0f) {
        val fillWidth = syllableWidth * syllableFillProgress
        val gradientWidth = 30f * resources.displayMetrics.density
        
        canvas.save()
        canvas.clipRect(syllableStartX, 0f, syllableStartX + fillWidth + gradientWidth, height.toFloat())
        
        // Create smooth gradient for fill transition
        if (syllableFillProgress < 1f) {
            fillPaint.shader = LinearGradient(
                syllableStartX + fillWidth - gradientWidth,
                0f,
                syllableStartX + fillWidth,
                0f,
                intArrayOf(fillColor, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            fillPaint.shader = null
        }
        
        // Draw filled characters with same upward offsets
        for ((charIndex, charPos) in charPositions.withIndex()) {
            val (char, charX) = charPos
            
            val charGlobalOffset = offset + charIndex
            val charProgress = (charGlobalOffset.toFloat() / totalChars.toFloat()).coerceIn(0f, 1f)
            
            val progressPastChar = (fillProgress - charProgress).coerceAtLeast(0f)
val animationWindow = 0.1f
val charUpOffset = -3f * resources.displayMetrics.density * (progressPastChar / animationWindow).coerceIn(0f, 1f)
            
            canvas.drawText(char, charX, yPos + charUpOffset, fillPaint)
        }
        
        canvas.restore()
        fillPaint.shader = null
    }

    // Restore xPos to end of syllable
    xPos = syllableStartX + syllableWidth

    // Draw romanization if it exists and is different
    if (romanText.isNotEmpty() && !romanText.equals(syllableText, ignoreCase = true)) {
        val romanWidth = romanGreyPaint.measureText(romanText)
        val romanX = syllableStartX + (syllableWidth - romanWidth) / 2f
        
        // Draw romanization characters with individual upward movement
        val romanCharPositions = mutableListOf<Pair<String, Float>>()
        var romanXPos = romanX
        for (charIndex in romanText.indices) {
            val char = romanText[charIndex].toString()
            val charWidth = romanGreyPaint.measureText(char)
            romanCharPositions.add(Pair(char, romanXPos))
            romanXPos += charWidth
        }
        
        // Draw base romanization
        for ((charIndex, charPos) in romanCharPositions.withIndex()) {
            val (char, charX) = charPos
            
            val romanCharProgress = (charIndex.toFloat() / romanText.length.toFloat())
            val syllableCharIndex = (romanCharProgress * syllableText.length).toInt().coerceIn(0, syllableText.length - 1)
            val charGlobalOffset = offset + syllableCharIndex
            val charProgress = (charGlobalOffset.toFloat() / totalChars.toFloat()).coerceIn(0f, 1f)
            
            val progressPastChar = (fillProgress - charProgress).coerceAtLeast(0f)
val animationWindow = 0.15f
val charUpOffset = -3f * resources.displayMetrics.density * (progressPastChar / animationWindow).coerceIn(0f, 1f)
            
            canvas.drawText(char, charX, yPos + romanYOffset + charUpOffset, romanGreyPaint)
        }
        
        // Draw filled romanization with smooth gradient
        if (syllableFillProgress > 0f) {
            val fillWidth = romanWidth * syllableFillProgress
            val gradientWidth = 20f * resources.displayMetrics.density
            
            canvas.save()
            canvas.clipRect(romanX, 0f, romanX + fillWidth + gradientWidth, height.toFloat())
            
            if (syllableFillProgress < 1f) {
                romanWhitePaint.shader = LinearGradient(
                    romanX + fillWidth - gradientWidth,
                    0f,
                    romanX + fillWidth,
                    0f,
                    intArrayOf(Color.WHITE, Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                romanWhitePaint.shader = null
            }
            
            for ((charIndex, charPos) in romanCharPositions.withIndex()) {
                val (char, charX) = charPos
                
                val romanCharProgress = (charIndex.toFloat() / romanText.length.toFloat())
                val syllableCharIndex = (romanCharProgress * syllableText.length).toInt().coerceIn(0, syllableText.length - 1)
                val charGlobalOffset = offset + syllableCharIndex
                val charProgress = (charGlobalOffset.toFloat() / totalChars.toFloat()).coerceIn(0f, 1f)
                
                val progressPastChar = (fillProgress - charProgress).coerceAtLeast(0f)
val animationWindow = 0.15f
val charUpOffset = -3f * resources.displayMetrics.density * (progressPastChar / animationWindow).coerceIn(0f, 1f)
                
                canvas.drawText(char, charX, yPos + romanYOffset + charUpOffset, romanWhitePaint)
            }
            
            canvas.restore()
            romanWhitePaint.shader = null
        }
    }
    }

                canvas.restore()
            }

            inner class FillAnimator(private val view: FillTextView) {
                private var animDuration = 200L

                fun fillProgress(target: Float): FillAnimator {
                    android.animation.ValueAnimator.ofFloat(view.fillProgress, target).apply {
    duration = animDuration
    interpolator = android.view.animation.LinearInterpolator()
                        addUpdateListener { view.fillProgress = it.animatedValue as Float }
                        start()
                    }
                    return this
                }

                fun setDuration(duration: Long): FillAnimator {
                    animDuration = duration
                    return this
                }

                fun start() {}
            }
        }
    }