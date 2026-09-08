package com.hamhuo.tplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HopTimelineTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val noon = Instant.parse("2026-09-08T12:00:00Z").toEpochMilli()

    private fun task(id: String, start: Long, end: Long, title: String = "写申论") =
        HopTaskInterval(id, title, start, end)

    @Test fun `all four endpoint states clip to real times and caps`() {
        val examples = listOf(
            Triple(task("inside", noon + 10 * minute, noon + 30 * minute), true, true),
            Triple(task("left", noon - hour, noon + 30 * minute), false, true),
            Triple(task("right", noon + 10 * minute, noon + 2 * hour), true, false),
            Triple(task("both", noon - hour, noon + 2 * hour), false, false),
        )
        for ((input, startCap, endCap) in examples) {
            val segment = HopTimeline.layout(listOf(input), noon, noon + hour).single()
            assertEquals(input.id, startCap, segment.showStartCap)
            assertEquals(input.id, endCap, segment.showEndCap)
            assertEquals(maxOf(noon, input.startEpochMs), segment.startEpochMs)
            assertEquals(minOf(noon + hour, input.endEpochMs), segment.endEpochMs)
        }
    }

    @Test fun `five minute task never receives a fake minimum duration`() {
        val input = task("short", noon, noon + 5 * minute)
        val segment = HopTimeline.layout(listOf(input), noon - hour, noon + hour).single()
        assertEquals(5 * minute, segment.endEpochMs - segment.startEpochMs)
        val width = HopTimeline.fraction(segment.endEpochMs, noon - hour, noon + hour) -
            HopTimeline.fraction(segment.startEpochMs, noon - hour, noon + hour)
        assertEquals(5f / 120f, width, 0.00001f)
    }

    @Test fun `midnight uses epoch intersection without wrapping`() {
        val midnight = Instant.parse("2026-09-09T00:00:00Z").toEpochMilli()
        val segment = HopTimeline.layout(listOf(task("overnight", midnight - 15 * minute,
            midnight + 20 * minute)), midnight - 30 * minute, midnight + 30 * minute).single()
        assertEquals(35 * minute, segment.endEpochMs - segment.startEpochMs)
        assertTrue(segment.showStartCap)
        assertTrue(segment.showEndCap)
        assertEquals(0.25f, HopTimeline.fraction(segment.startEpochMs,
            midnight - 30 * minute, midnight + 30 * minute), 0f)
    }

    @Test fun `24 and 72 hour tasks stay continuous through a short viewport`() {
        for (duration in listOf(day, 3 * day)) {
            val segment = HopTimeline.layout(listOf(task("long", noon - duration / 2,
                noon + duration / 2)), noon - hour, noon + hour).single()
            assertEquals(2 * hour, segment.endEpochMs - segment.startEpochMs)
            assertFalse(segment.showStartCap)
            assertFalse(segment.showEndCap)
            assertEquals(noon, HopTimeline.labelAnchor(segment, noon - 20 * minute, noon + 20 * minute))
        }
    }

    @Test fun `half open boundaries exclude finished and future tasks`() {
        val inputs = listOf(
            task("finished", noon - hour, noon),
            task("future", noon + hour, noon + 2 * hour),
            task("at start", noon, noon),
            task("at end", noon + hour, noon + hour),
            task("invalid", noon + minute, noon),
        )
        val segment = HopTimeline.layout(inputs, noon, noon + hour).single()
        assertEquals("at start", segment.task.id)
        assertTrue(segment.isPoint)
        assertFalse(segment.showStartCap)
        assertFalse(segment.showEndCap)
        assertEquals(segment.startEpochMs, segment.endEpochMs)
    }

    @Test fun `real end exactly on viewport edge does not invent an in-window cap`() {
        val segment = HopTimeline.layout(listOf(task("edge", noon, noon + hour)),
            noon, noon + hour).single()
        assertTrue(segment.showStartCap)
        assertFalse(segment.showEndCap)
    }

    @Test fun `overlap selection is deterministic and all day yields to appointments`() {
        val inputs = listOf(
            task("all day", noon - 12 * hour, noon + 12 * hour),
            task("b", noon + 5 * minute, noon + 25 * minute),
            task("a", noon + 5 * minute, noon + 25 * minute),
            task("c", noon + 30 * minute, noon + 45 * minute),
        )
        val expected = HopTimeline.layout(inputs, noon, noon + hour)
        assertEquals(listOf("a", "b", "c"), expected.map { it.task.id })
        assertEquals(listOf(0, 1, 0), expected.map { it.lane })
        for (seed in 0..20) {
            assertEquals(expected, HopTimeline.layout(inputs.shuffled(kotlin.random.Random(seed)),
                noon, noon + hour))
        }
    }

    @Test fun `adjacent intervals reuse one lane while coincident points occupy separate lanes`() {
        val adjacent = HopTimeline.layout(listOf(task("a", noon, noon + 5 * minute),
            task("b", noon + 5 * minute, noon + 10 * minute)), noon, noon + hour)
        assertEquals(listOf(0, 0), adjacent.map { it.lane })
        val points = HopTimeline.layout(listOf(task("a", noon, noon), task("b", noon, noon),
            task("c", noon, noon)), noon, noon + hour)
        assertEquals(listOf("a", "b"), points.map { it.task.id })
        assertEquals(listOf(0, 1), points.map { it.lane })
    }

    @Test fun `lane recoloring retains tasks despite admission order fragmentation`() {
        val inputs = listOf(task("a", noon, noon + 10 * minute),
            task("b", noon + 20 * minute, noon + 30 * minute),
            task("c", noon + 5 * minute, noon + 25 * minute))
        val result = HopTimeline.layout(inputs, noon, noon + hour)
        assertEquals(listOf("a", "c", "b"), result.map { it.task.id })
        assertEquals(listOf(0, 1, 0), result.map { it.lane })
    }

    @Test fun `label follows original time midpoint and clamps only at readable edges`() {
        val input = task("moving", noon, noon + 40 * minute)
        val first = HopTimeline.layout(listOf(input), noon - 10 * minute, noon + hour).single()
        val second = HopTimeline.layout(listOf(input), noon + 5 * minute, noon + 75 * minute).single()
        val anchor = noon + 20 * minute
        assertEquals(anchor, HopTimeline.labelAnchor(first, noon, noon + 50 * minute))
        assertEquals(anchor, HopTimeline.labelAnchor(second, noon + 10 * minute, noon + 60 * minute))
        assertTrue(HopTimeline.fraction(anchor, noon + 5 * minute, noon + 75 * minute) <
            HopTimeline.fraction(anchor, noon - 10 * minute, noon + hour))
        assertEquals(noon + 25 * minute,
            HopTimeline.labelAnchor(second, noon + 25 * minute, noon + 35 * minute))
    }

    private val monoMeasure: (String) -> Float = { HopLabelPlanner.graphemes(it).size * 10f }

    @Test fun `short label spacing is capped even on a very long arc`() {
        val label = HopLabelPlanner.plan("写申论", 500f, 10f, monoMeasure,
            maxLetterSpacingEm = 4f)!!
        assertEquals("写申论", label.text)
        assertEquals(0.12f, label.letterSpacingEm, 0f)
        assertEquals(32.4f, label.widthPx, 0.0001f)
        assertFalse(label.truncated)
    }

    @Test fun `measured fit never shrinks text and useful ellipsis needs a character`() {
        val exact = HopLabelPlanner.plan("写申论", 30f, 10f, monoMeasure)!!
        assertEquals(0f, exact.letterSpacingEm, 0f)
        assertFalse(exact.truncated)
        val clipped = HopLabelPlanner.plan("写申论", 20f, 10f, monoMeasure)!!
        assertEquals("写…", clipped.text)
        assertEquals(20f, clipped.widthPx, 0f)
        assertTrue(clipped.truncated)
        assertNull(HopLabelPlanner.plan("写申论", 19f, 10f, monoMeasure))
        assertNull(HopLabelPlanner.plan("写", 9f, 10f, monoMeasure))
        assertNull(HopLabelPlanner.plan("   ", 100f, 10f, monoMeasure))
    }

    @Test fun `graphemes preserve combining marks emoji skin tones joined families and flags`() {
        val examples = listOf("e\u0301", "👍🏽", "👨‍👩‍👧‍👦", "🇨🇳")
        for (grapheme in examples) {
            assertEquals(listOf(grapheme), HopLabelPlanner.graphemes(grapheme))
            val clipped = HopLabelPlanner.plan(grapheme + "AB", 20f, 10f, monoMeasure)!!
            assertEquals(grapheme + "…", clipped.text)
            assertEquals(listOf(grapheme, "…"), clipped.graphemes)
        }
        assertEquals(listOf("🇨🇳", "🇬🇧"), HopLabelPlanner.graphemes("🇨🇳🇬🇧"))
    }

    @Test fun `Latin kerning preserves whole run and never switches to spaced glyphs`() {
        val shapedMeasure: (String) -> Float = { if (it == "AV") 15f else it.length * 10f }
        for (available in listOf(16f, 500f)) {
            val label = HopLabelPlanner.plan("AV", available, 10f, shapedMeasure)
            assertNotNull(label)
            assertEquals("AV", label!!.text)
            assertFalse(label.truncated)
            assertEquals(0f, label.letterSpacingEm, 0f)
            assertEquals(15f, label.widthPx, 0f)
        }
    }

    @Test fun `Arabic joining and Latin ligatures keep zero spacing and whole run width`() {
        for (title in listOf("سلام", "office", "写A", "か\u3099く")) {
            val measuredStrings = mutableListOf<String>()
            val measure: (String) -> Float = {
                measuredStrings += it
                if (it == title) 23f else 100f
            }
            val label = HopLabelPlanner.plan(title, 500f, 10f, measure)!!
            assertEquals(title, label.text)
            assertEquals(0f, label.letterSpacingEm, 0f)
            assertEquals(23f, label.widthPx, 0f)
            assertEquals(listOf(title), measuredStrings)
        }
    }

    @Test fun `CJK tracking uses separate glyph advances instead of whole run width`() {
        val measure: (String) -> Float = { if (it == "申论") 15f else 10f }
        val spaced = HopLabelPlanner.plan("申论", 21f, 10f, measure)!!
        assertEquals(0.1f, spaced.letterSpacingEm, 0.0001f)
        assertEquals(21f, spaced.widthPx, 0.0001f)
        val capped = HopLabelPlanner.plan("申论", 500f, 10f, measure)!!
        assertEquals(0.12f, capped.letterSpacingEm, 0f)
        assertEquals(21.2f, capped.widthPx, 0.0001f)
        // The separate glyphs are too wide, but the whole run still fits at full size.
        val wholeRun = HopLabelPlanner.plan("申论", 19f, 10f, measure)!!
        assertEquals(0f, wholeRun.letterSpacingEm, 0f)
        assertEquals(15f, wholeRun.widthPx, 0f)
    }

    @Test fun `simple kana permits capped spacing but mixed punctuation keeps whole run`() {
        for (title in listOf("かな", "カナ")) {
            val label = HopLabelPlanner.plan(title, 500f, 10f, monoMeasure)!!
            assertEquals(0.12f, label.letterSpacingEm, 0f)
        }
        val punctuation = HopLabelPlanner.plan("申论。", 500f, 10f, monoMeasure)!!
        assertEquals(0f, punctuation.letterSpacingEm, 0f)
        assertEquals(30f, punctuation.widthPx, 0f)
    }
}
