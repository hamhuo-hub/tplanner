package com.hamhuo.tplanner

import com.hamhuo.tplanner.designsystem.TPlannerTypography
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** All geometry is in logical screen units. Font scale never changes the virtual dial. */
internal object HopSizeSpec {
    const val BASE_DIAMETER_DP = 200f
    const val ORBIT_DIAMETER_RATIO = 5f / 6f
    const val TICK_DIAMETER_RATIO = 1f
    const val TASK_DIAMETER_RATIO = 1.075f
    const val EDGE_PADDING_DP = 4f
    const val MILLIS_PER_TURN = 43_200_000L
}

internal data class HopPoint(val x: Float, val y: Float)

internal data class HopFaceMetrics(
    val widthPx: Float,
    val heightPx: Float,
    val density: Float,
    val scaledDensity: Float,
) {
    init {
        require(widthPx > 0 && heightPx > 0 && density > 0 && scaledDensity > 0)
    }

    val diameterPx = min(widthPx, heightPx)
    val diameterDp = diameterPx / density
    val screenRadiusPx = diameterPx / 2f
    // A stationary white lip surrounds the moving dial; content never paints into this band.
    val bezelWidthPx = maxOf(2.5f * density, diameterPx * 0.016f)
    val faceRadiusPx = screenRadiusPx - bezelWidthPx
    val safeRadiusPx = faceRadiusPx - maxOf(HopSizeSpec.EDGE_PADDING_DP * density, diameterPx * 0.015f)
    val centerX = widthPx / 2f
    val centerY = heightPx / 2f
    val orbitRadiusPx = diameterPx * HopSizeSpec.ORBIT_DIAMETER_RATIO
    val tickRadiusPx = diameterPx * HopSizeSpec.TICK_DIAMETER_RATIO
    val hourTextSizePx = boundedSp(
        TPlannerTypography.HopHourBaseSp, TPlannerTypography.HopHourMinSp, TPlannerTypography.HopHourMaxSp,
    )
    val taskTextSizePx = boundedSp(
        TPlannerTypography.HopTaskBaseSp, TPlannerTypography.HopTaskMinSp, TPlannerTypography.HopTaskMaxSp,
    )

    private fun boundedSp(base: Float, low: Float, high: Float): Float =
        (base * diameterDp / HopSizeSpec.BASE_DIAMETER_DP).coerceIn(low, high) * scaledDensity

    fun geometry(t: ZonedDateTime): HopDialGeometry {
        val elapsedMs = t.toLocalTime().toNanoOfDay() / 1_000_000L
        val angle = elapsedMs % HopSizeSpec.MILLIS_PER_TURN * 2.0 * PI / HopSizeSpec.MILLIS_PER_TURN - PI / 2
        return HopDialGeometry(
            this, t.toInstant().toEpochMilli(), angle,
            centerX - cos(angle).toFloat() * orbitRadiusPx,
            centerY - sin(angle).toFloat() * orbitRadiusPx,
        )
    }
}

/** The current hour position is fixed at the viewport center; the dial's center travels. */
internal data class HopDialGeometry(
    val metrics: HopFaceMetrics,
    val nowEpochMs: Long,
    val nowAngle: Double,
    val dialX: Float,
    val dialY: Float,
) {
    fun angleAt(epochMs: Long): Double = nowAngle +
        (epochMs - nowEpochMs).toDouble() * 2.0 * PI / HopSizeSpec.MILLIS_PER_TURN

    fun point(radius: Float, angle: Double): HopPoint = HopPoint(
        dialX + radius * cos(angle).toFloat(), dialY + radius * sin(angle).toFloat(),
    )

    /** Circle/circle intersection, measured from NOW. No hard-coded hours or day wrapping. */
    fun halfWindowMs(radius: Float, inset: Float = 0f): Long {
        val viewportRadius = (metrics.faceRadiusPx - inset).coerceAtLeast(0f).toDouble()
        val orbit = metrics.orbitRadiusPx.toDouble()
        val track = radius.toDouble()
        val cosine = (track * track + orbit * orbit - viewportRadius * viewportRadius) / (2.0 * track * orbit)
        return (acos(cosine.coerceIn(-1.0, 1.0)) * HopSizeSpec.MILLIS_PER_TURN / (2.0 * PI)).toLong()
    }
}
