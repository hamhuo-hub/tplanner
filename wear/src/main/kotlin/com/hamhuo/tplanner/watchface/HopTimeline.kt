package com.hamhuo.tplanner

import java.text.BreakIterator
import java.util.Locale

/** Epoch-based input; a zero-length interval represents one event marker. */
data class HopTaskInterval(
    val id: String,
    val title: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
)

data class HopTaskSegment(
    val task: HopTaskInterval,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val lane: Int,
    val showStartCap: Boolean,
    val showEndCap: Boolean,
    val isPoint: Boolean,
)

/**
 * Time clipping and lane selection, deliberately independent of the watch's circle geometry.
 * Both tasks and the viewport are half-open [start, end); there is no modulo-day arithmetic.
 */
object HopTimeline {
    fun layout(
        tasks: List<HopTaskInterval>,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        maxLanes: Int = 2,
    ): List<HopTaskSegment> {
        require(windowEndEpochMs > windowStartEpochMs) { "The visible time window must be positive" }
        val laneCount = maxLanes.coerceIn(0, 2)
        if (laneCount == 0) return emptyList()

        val candidates = tasks.asSequence()
            .filter { it.endEpochMs >= it.startEpochMs }
            .mapNotNull { task ->
                val point = task.startEpochMs == task.endEpochMs
                val visible = if (point) {
                    task.startEpochMs >= windowStartEpochMs && task.startEpochMs < windowEndEpochMs
                } else {
                    task.startEpochMs < windowEndEpochMs && task.endEpochMs > windowStartEpochMs
                }
                if (!visible) null else HopTaskSegment(
                    task = task,
                    startEpochMs = maxOf(task.startEpochMs, windowStartEpochMs),
                    endEpochMs = minOf(task.endEpochMs, windowEndEpochMs),
                    lane = 0,
                    // A point is drawn once by the renderer, never as two coincident caps.
                    showStartCap = !point && task.startEpochMs >= windowStartEpochMs &&
                        task.startEpochMs < windowEndEpochMs,
                    showEndCap = !point && task.endEpochMs >= windowStartEpochMs &&
                        task.endEpochMs < windowEndEpochMs,
                    isPoint = point,
                )
            }
            // Original duration keeps admission priority stable as the viewport advances.
            // Short appointments win over an all-day/multi-day interval at capacity.
            .sortedWith(compareBy<HopTaskSegment> {
                it.task.endEpochMs.toDouble() - it.task.startEpochMs.toDouble()
            }.thenBy { it.task.startEpochMs }.thenBy { it.task.endEpochMs }
                .thenBy { it.task.id }.thenBy { it.task.title })
            .distinctBy { it.task.id }
            .toList()

        var selected = emptyList<HopTaskSegment>()
        for (candidate in candidates) {
            // Recolor chronologically so a lane fragmented by prior admission order cannot
            // unnecessarily reject a task that would fit in the available two lanes.
            assignLanes(selected + candidate, laneCount)?.let { selected = it }
        }
        return selected
    }

    /** The same linear time coordinate should drive ticks, task endpoints and labels. */
    fun fraction(epochMs: Long, windowStartEpochMs: Long, windowEndEpochMs: Long): Float {
        require(windowEndEpochMs > windowStartEpochMs)
        return ((epochMs.toDouble() - windowStartEpochMs.toDouble()) /
            (windowEndEpochMs.toDouble() - windowStartEpochMs.toDouble())).toFloat()
    }

    /**
     * Active titles follow the current time within the readable intersection.
     * Other titles use the visible segment's midpoint, never an offscreen task midpoint.
     * The caller insets readable bounds by half the measured label width in time units.
     */
    fun labelAnchor(
        segment: HopTaskSegment,
        readableStartEpochMs: Long,
        readableEndEpochMs: Long,
        nowEpochMs: Long,
    ): Long {
        require(readableEndEpochMs >= readableStartEpochMs)
        val active = segment.task.startEpochMs <= nowEpochMs && nowEpochMs < segment.task.endEpochMs
        val preferred = if (active) nowEpochMs else midpoint(segment.startEpochMs, segment.endEpochMs)
        return preferred.coerceIn(readableStartEpochMs, readableEndEpochMs)
    }

    private fun midpoint(start: Long, end: Long): Long =
        (start and end) + ((start xor end) shr 1)

    private fun assignLanes(
        candidates: List<HopTaskSegment>,
        laneCount: Int,
    ): List<HopTaskSegment>? {
        val tails = arrayOfNulls<HopTaskSegment>(laneCount)
        val result = ArrayList<HopTaskSegment>(candidates.size)
        for (segment in candidates.sortedWith(compareBy<HopTaskSegment> { it.startEpochMs }
            .thenBy { it.endEpochMs }.thenBy { it.task.startEpochMs }
            .thenBy { it.task.endEpochMs }.thenBy { it.task.id }.thenBy { it.task.title })) {
            val lane = tails.indexOfFirst { previous ->
                previous == null || segment.startEpochMs > previous.endEpochMs ||
                    (segment.startEpochMs == previous.endEpochMs && !previous.isPoint)
            }
            if (lane < 0) return null
            val placed = segment.copy(lane = lane)
            tails[lane] = placed
            result += placed
        }
        return result
    }
}

data class HopLabelPlan(
    val text: String,
    val graphemes: List<String>,
    val letterSpacingEm: Float,
    val widthPx: Float,
    val truncated: Boolean,
)

/** Fits measured text without reducing the chosen, readable type size. */
object HopLabelPlanner {
    fun plan(
        text: String,
        availableArcLengthPx: Float,
        fontSizePx: Float,
        measureText: (String) -> Float,
        maxLetterSpacingEm: Float = 0.12f,
    ): HopLabelPlan? {
        if (!availableArcLengthPx.isFinite() || availableArcLengthPx <= 0f ||
            !fontSizePx.isFinite() || fontSizePx <= 0f) return null
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return null
        val clusters = graphemes(clean)
        val naturalWidth = measureText(clean)
        if (!naturalWidth.isFinite() || naturalWidth < 0f) return null

        if (naturalWidth <= availableArcLengthPx) {
            val gaps = clusters.size - 1
            val spacingLimit = if (maxLetterSpacingEm.isFinite()) {
                maxLetterSpacingEm.coerceIn(0f, 0.12f)
            } else 0f
            // Positive tracking means the renderer draws clusters separately. Only simple
            // CJK/kana characters can opt into that path; Latin kerning, Arabic joining,
            // ligatures and combined/emoji clusters must retain the measured whole run.
            if (gaps > 0 && spacingLimit > 0f && clusters.all(::canDrawSeparately)) {
                val advances = clusters.map(measureText)
                val separateWidth = advances.sum()
                if (advances.all { it.isFinite() && it >= 0f } &&
                    separateWidth.isFinite() && separateWidth < availableArcLengthPx) {
                    val spacing = ((availableArcLengthPx - separateWidth) / gaps / fontSizePx)
                        .coerceIn(0f, spacingLimit)
                    if (spacing > 0f) {
                        return HopLabelPlan(clean, clusters, spacing,
                            separateWidth + gaps * spacing * fontSizePx, false)
                    }
                }
            }
            return HopLabelPlan(clean, clusters, 0f, naturalWidth, false)
        }

        // An isolated ellipsis says nothing useful. Require at least one complete grapheme.
        // Measure whole candidates rather than summing glyphs: kerning and shaping matter.
        for (count in clusters.size - 1 downTo 1) {
            val kept = clusters.take(count) + "…"
            val candidate = kept.joinToString("")
            val width = measureText(candidate)
            if (width.isFinite() && width >= 0f && width <= availableArcLengthPx) {
                return HopLabelPlan(candidate, kept, 0f, width, true)
            }
        }
        return null
    }

    private fun canDrawSeparately(grapheme: String): Boolean {
        if (grapheme.codePointCount(0, grapheme.length) != 1) return false
        return when (Character.UnicodeScript.of(grapheme.codePointAt(0))) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA -> true
            else -> false
        }
    }

    /**
     * BreakIterator handles combining sequences on Android/JVM. Older JDKs split emoji
     * modifiers and ZWJ sequences, so merge those boundaries (and regional flag pairs).
     */
    fun graphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val result = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val next = text.substring(start, end)
            val firstCodePoint = next.codePointAt(0)
            val previous = result.lastOrNull()
            val joinsPrevious = previous != null && (
                previous.codePointBefore(previous.length) == 0x200D ||
                    firstCodePoint == 0x200D || isExtension(firstCodePoint) ||
                    (isRegionalIndicator(firstCodePoint) &&
                        previous.codePoints().allMatch { isRegionalIndicator(it) } &&
                        previous.codePointCount(0, previous.length) % 2 == 1)
                )
            if (joinsPrevious) result[result.lastIndex] = previous + next else result += next
            start = end
            end = iterator.next()
        }
        return result
    }

    private fun isRegionalIndicator(codePoint: Int) = codePoint in 0x1F1E6..0x1F1FF

    private fun isExtension(codePoint: Int): Boolean =
        codePoint in 0x1F3FB..0x1F3FF || codePoint in 0xFE00..0xFE0F ||
            codePoint in 0xE0100..0xE01EF || codePoint in 0xE0020..0xE007F ||
            Character.getType(codePoint) in setOf(
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
            )
}
