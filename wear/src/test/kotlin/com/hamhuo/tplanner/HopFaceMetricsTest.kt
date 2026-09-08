package com.hamhuo.tplanner

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HopFaceMetricsTest {
    private val logicalDiameters = listOf(176f, 192f, 200f, 220f, 240f)
    private val densities = listOf(1f, 1.5f, 2f, 3f)
    private val referenceTime = ZonedDateTime.of(2026, 9, 8, 12, 37, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test
    fun equalLogicalSizesKeepGeometryAcrossPixelDensities() {
        for (diameter in logicalDiameters) {
            val baseline = metrics(diameter, 1f)
            val baselineGeometry = baseline.geometry(referenceTime)
            for (density in densities) {
                val actual = metrics(diameter, density)
                val geometry = actual.geometry(referenceTime)
                assertDpEquals(diameter, actual.diameterDp)
                assertDpEquals(baseline.screenRadiusPx, actual.screenRadiusPx / density)
                assertDpEquals(baseline.bezelWidthPx, actual.bezelWidthPx / density)
                assertDpEquals(baseline.faceRadiusPx, actual.faceRadiusPx / density)
                assertDpEquals(baseline.safeRadiusPx, actual.safeRadiusPx / density)
                assertTrue(actual.safeRadiusPx > 0f)
                assertTrue(actual.safeRadiusPx < actual.faceRadiusPx)
                assertTrue(actual.faceRadiusPx < actual.screenRadiusPx)
                assertEquals(actual.bezelWidthPx, actual.screenRadiusPx - actual.faceRadiusPx, 0.0001f)
                assertDpEquals(baseline.orbitRadiusPx, actual.orbitRadiusPx / density)
                assertDpEquals(baseline.tickRadiusPx, actual.tickRadiusPx / density)
                assertDpEquals(baselineGeometry.dialX, geometry.dialX / density)
                assertDpEquals(baselineGeometry.dialY, geometry.dialY / density)
                assertDpEquals(baseline.taskTextSizePx, actual.taskTextSizePx / density)
                assertDpEquals(baseline.hourTextSizePx, actual.hourTextSizePx / density)
                val windowDelta = baselineGeometry.halfWindowMs(baseline.tickRadiusPx) -
                    geometry.halfWindowMs(actual.tickRadiusPx)
                assertTrue("Density must not change the visible time interval", windowDelta in -1L..1L)
            }
        }
    }

    @Test
    fun readableTaskTypeIsBoundedInSpAndHonorsUserFontScale() {
        for (diameter in logicalDiameters) {
            for (density in densities) {
                val baseline = metrics(diameter, density)
                for (fontScale in listOf(1f, 1.2f, 1.5f)) {
                    val actual = metrics(diameter, density, fontScale)
                    val taskSp = actual.taskTextSizePx / actual.scaledDensity
                    assertTrue("$diameter dp at $density density: $taskSp sp", taskSp in 10.9999f..14.0001f)
                    assertEquals(baseline.taskTextSizePx * fontScale, actual.taskTextSizePx, 0.0001f)
                    assertEquals(baseline.hourTextSizePx * fontScale, actual.hourTextSizePx, 0.0001f)
                    assertEquals(baseline.orbitRadiusPx, actual.orbitRadiusPx, 0f)
                    assertEquals(baseline.tickRadiusPx, actual.tickRadiusPx, 0f)
                    assertEquals(baseline.bezelWidthPx, actual.bezelWidthPx, 0f)
                    assertEquals(baseline.faceRadiusPx, actual.faceRadiusPx, 0f)
                    assertEquals(baseline.safeRadiusPx, actual.safeRadiusPx, 0f)
                    assertEquals(baseline.geometry(referenceTime), actual.geometry(referenceTime).copy(metrics = baseline))
                }
            }
        }
        assertEquals(11f, metrics(176f, 1f).taskTextSizePx, 0f)
        assertEquals(14f, metrics(240f, 1f).taskTextSizePx, 0f)
    }

    @Test
    fun nowRemainsAtViewportCenterThroughoutEveryHour() {
        for (diameter in logicalDiameters) {
            for (density in densities) {
                val metrics = metrics(diameter, density)
                for (hour in 0..23) {
                    for (minute in listOf(0, 1, 37, 59)) {
                        val time = referenceTime.withHour(hour).withMinute(minute).withSecond(59)
                        val geometry = metrics.geometry(time)
                        val now = geometry.point(metrics.orbitRadiusPx, geometry.angleAt(time.toInstant().toEpochMilli()))
                        assertEquals(metrics.centerX, now.x, 0.002f)
                        assertEquals(metrics.centerY, now.y, 0.002f)
                    }
                }
            }
        }
    }

    @Test
    fun dialMotionIsContinuousAcrossHourAndMidnightBoundaries() {
        val boundaries = listOf(
            referenceTime.withHour(13).withMinute(0).withSecond(0).withNano(0),
            referenceTime.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0),
        )
        for (diameter in logicalDiameters) {
            val metrics = metrics(diameter, 3f)
            for (boundary in boundaries) {
                val before = metrics.geometry(boundary.minusNanos(1_000_000))
                val after = metrics.geometry(boundary)
                assertTrue(distance(HopPoint(before.dialX, before.dialY), HopPoint(after.dialX, after.dialY)) < 0.002)
                // The same real task boundary must not jump when the displayed hour rolls over.
                val taskStart = boundary.plusMinutes(17).toInstant().toEpochMilli()
                val beforeMark = before.point(metrics.tickRadiusPx, before.angleAt(taskStart))
                val afterMark = after.point(metrics.tickRadiusPx, after.angleAt(taskStart))
                assertTrue(distance(beforeMark, afterMark) < 0.002)
            }
        }
    }

    @Test
    fun visibleWindowEndsAtFaceApertureAndReadableInsetShrinksIt() {
        for (diameter in logicalDiameters) {
            for (density in densities) {
                val metrics = metrics(diameter, density)
                for (hour in listOf(0, 3, 6, 9, 12, 18, 23)) {
                    val geometry = metrics.geometry(referenceTime.withHour(hour))
                    for (radius in listOf(metrics.tickRadiusPx, metrics.diameterPx * HopSizeSpec.TASK_DIAMETER_RATIO)) {
                        val fullHalfWindow = geometry.halfWindowMs(radius)
                        val edgeInset = metrics.faceRadiusPx - metrics.safeRadiusPx
                        val readableHalfWindow = geometry.halfWindowMs(radius, edgeInset)
                        assertTrue(readableHalfWindow in 1 until fullHalfWindow)
                        assertTrue(fullHalfWindow * 2 < HopSizeSpec.MILLIS_PER_TURN)
                        for ((halfWindow, circleRadius) in listOf(
                            fullHalfWindow to metrics.faceRadiusPx,
                            readableHalfWindow to metrics.safeRadiusPx,
                        )) {
                            for (direction in listOf(-1, 1)) {
                                val epoch = geometry.nowEpochMs + direction * halfWindow
                                val edge = geometry.point(radius, geometry.angleAt(epoch))
                                val fromCenter = distance(edge, HopPoint(metrics.centerX, metrics.centerY))
                                assertEquals(circleRadius.toDouble(), fromCenter, 0.025)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun taskAnglesKeepAbsoluteElapsedTimeForMultiDayIntervals() {
        val geometry = metrics(200f, 2f).geometry(referenceTime)
        val hourMs = 3_600_000L
        for (hours in listOf(-120, -48, -24, -12, -1, 1, 12, 24, 48, 120)) {
            val delta = geometry.angleAt(geometry.nowEpochMs + hours * hourMs) - geometry.nowAngle
            assertEquals(hours * PI / 6.0, delta, 0.00000001)
        }
        assertTrue(geometry.angleAt(geometry.nowEpochMs + 72 * hourMs) > geometry.nowAngle + 2 * PI)
        assertTrue(geometry.angleAt(geometry.nowEpochMs - 72 * hourMs) < geometry.nowAngle - 2 * PI)
    }

    private fun metrics(diameterDp: Float, density: Float, fontScale: Float = 1f) = HopFaceMetrics(
        widthPx = diameterDp * density,
        heightPx = diameterDp * density,
        density = density,
        scaledDensity = density * fontScale,
    )

    private fun assertDpEquals(expected: Float, actual: Float) = assertEquals(expected, actual, 0.0001f)

    private fun distance(first: HopPoint, second: HopPoint): Double =
        hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble())
}
