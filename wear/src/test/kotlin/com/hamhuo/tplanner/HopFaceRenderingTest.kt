package com.hamhuo.tplanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import com.hamhuo.tplanner.designsystem.TPlannerWatchFacePalette.Hop
import java.io.File
import java.time.ZonedDateTime
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HopFaceRenderingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val time = ZonedDateTime.of(2026, 9, 8, 12, 37, 0, 0, APP_ZONE)
    private val directory = File("build/outputs/hop-preview").apply { mkdirs() }
    private fun task(id: String, title: String, start: Int, end: Int): HopTaskInterval {
        val midnight = time.toLocalDate().atStartOfDay(APP_ZONE).toInstant().toEpochMilli()
        return HopTaskInterval(id, title, midnight + start * 60_000L, midnight + end * 60_000L)
    }

    private val tasks get() = listOf(
        task("practice", "申论练习", 12 * 60, 13 * 60),
        task("review", "错题复盘", 13 * 60 + 5, 13 * 60 + 45),
        task("long", "保持专注", 0, 24 * 60),
    )

    private fun render(
        dp: Int = 200,
        t: ZonedDateTime = time,
        items: List<HopTaskInterval> = tasks,
        stage: HopFacePainter.Stage = HopFacePainter.Stage.TASKS,
        ambient: Boolean = false,
        fontScale: Float = 1f,
        density: Float = 2f,
    ): Bitmap {
        val size = (dp * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val circle = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
        canvas.clipPath(circle)
        HopFacePainter(context).draw(
            canvas, HopFaceMetrics(size.toFloat(), size.toFloat(), density, density * fontScale),
            t, items, ambient = ambient, burnInProtection = true, stage = stage,
        )
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun rendersActualPainterAcrossSizesStagesAndEdgeCases() {
        val sizes = listOf(176, 192, 200, 220, 240)
        val images = sizes.map { size -> render(size).also { save(it, "hop-${size}dp") } }
        save(sheet(images, sizes.map { "${it}dp" }, "HOP / TPLANNER", "12:37 · 同一时刻，五种逻辑尺寸"), "size-comparison")

        val stageImages = HopFacePainter.Stage.entries.map { stage -> render(stage = stage).also { save(it, "stage-${stage.ordinal}") } }
        save(sheet(stageImages, listOf("00  纯表盘", "01  时间区间", "02  文字", "03  多任务"), "从时间，到日程", "同一套几何 · 同一个 Android Canvas 绘制器"), "stages")

        val edgeCases = listOf(
            render(items = emptyList()),
            render(items = listOf(task("short", "五分钟", 12 * 60 + 35, 12 * 60 + 40))),
            render(items = listOf(task("day", "练习", 0, 24 * 60))),
            render(t = time.withHour(23).withMinute(57), items = listOf(task("night", "跨日练习", 23 * 60 + 30, 25 * 60))),
            render(items = listOf(task("3days", "保持专注", -24 * 60, 48 * 60))),
            render(176, fontScale = 1.3f),
        )
        save(sheet(edgeCases, listOf("空日程", "5 分钟", "24 小时", "跨午夜 · 23:57", "72 小时", "176dp · 字体 130%"), "时间有长短，文字有分寸", "真实边界 · 可见交集 · 可读字号"), "edge-cases")
        save(render(232, density = 2f), "picker")
        save(render(240), "hero")
        save(render(240, t = time.withHour(13).withMinute(22), items = emptyList()), "reference-time")
        save(render(200, ambient = true), "ambient")
        val hours = listOf(0, 3, 6, 9, 12, 15)
        save(sheet(hours.map { render(t = time.withHour(it)) }, hours.map { "%02d:37".format(it) },
            "当前时间留在中央，表盘继续行走", "正立数字 · 共同时间坐标 · 全天运动检查"), "motion")
        // A small time scrubber can show actual native-rendered frames without a second
        // JavaScript implementation drifting away from the production geometry.
        for (minute in 0 until 720 step 5) {
            save(render(t = time.withHour(12).withMinute(0).plusMinutes(minute.toLong())), "frame-$minute")
        }
        // This guards a failed native renderer/blank previews, rather than just file existence.
        for (bitmap in images) {
            var ink = 0
            var accent = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel == Hop.Ink) ink++
                if (pixel == Hop.Now) accent++
            }
            assertTrue("Numerals and ticks must actually render", ink > 300)
            assertTrue("NOW remains a thin visible accent", accent in 80..(bitmap.width * bitmap.height / 50))
        }
    }

    @Test fun ambientIsDarkSparseAndHasNoTaskLayer() {
        val withTasks = render(ambient = true)
        val withoutTasks = render(ambient = true, items = emptyList())
        assertTrue("Ambient omits tasks", withTasks.sameAs(withoutTasks))
        var lit = 0
        for (y in 0 until withTasks.height) for (x in 0 until withTasks.width) {
            val pixel = withTasks.getPixel(x, y)
            assertTrue("No paper or orange in ambient", pixel != Hop.Paper && pixel != Hop.Now)
            if (Color.alpha(pixel) > 0 && pixel != Hop.AmbientBackground) lit++
        }
        assertTrue("Ambient limits lit pixels", lit < withTasks.width * withTasks.height / 10)
    }

    @Test fun offscreenTasksDoNotChangeDialOrCreateWrappedRings() {
        val empty = render(items = emptyList())
        val farAway = render(items = listOf(task("tomorrow", "明天", 24 * 60, 25 * 60)))
        assertTrue(empty.sameAs(farAway))
        val day = render(items = listOf(task("span", "练习", 0, 24 * 60)))
        val threeDays = render(items = listOf(task("span", "练习", -24 * 60, 48 * 60)))
        assertTrue("Same visible intersection must render identically", day.sameAs(threeDays))
        assertEquals(Hop.Paper, empty.getPixel(210, 280))
    }

    @Test fun stationaryWhiteRimSurvivesMovingContentAndShadowStaysInside() {
        for (dp in listOf(176, 200, 240)) {
            val m = HopFaceMetrics(dp * 2f, dp * 2f, 2f, 2.6f)
            val sampleRadius = (m.faceRadiusPx + m.screenRadiusPx) / 2
            for (hour in listOf(0, 3, 6, 9)) {
                val bitmap = render(dp, time.withHour(hour), fontScale = 1.3f)
                for (degree in 0 until 360) {
                    val angle = Math.toRadians(degree.toDouble())
                    val x = (m.centerX + cos(angle) * sampleRadius).roundToInt()
                    val y = (m.centerY + sin(angle) * sampleRadius).roundToInt()
                    assertEquals("Rim interrupted at ${dp}dp, $hour h, $degree degrees", Hop.Rim, bitmap.getPixel(x, y))
                }
            }
        }
        val dial = render(items = emptyList())
        // Pick unprinted top/bottom points equally far inside the lip: the top recess is darker.
        val top = dial.getPixel(200, 16)
        val bottom = dial.getPixel(200, 384)
        assertTrue(Color.red(top) < Color.red(bottom) - 20)
        assertEquals("Ambient must not retain the bright lip", Hop.AmbientBackground, render(ambient = true).getPixel(200, 3))
    }

    private fun sheet(images: List<Bitmap>, labels: List<String>, title: String, subtitle: String): Bitmap {
        val columns = if (images.size == 5) 5 else if (images.size == 4) 4 else 3
        val cell = 510
        val rows = (images.size + columns - 1) / columns
        val bitmap = Bitmap.createBitmap(columns * cell + 80, rows * 550 + 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Hop.Ink)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hop.Paper; typeface = Typeface.DEFAULT; textSize = 38f }
        canvas.drawText(title, 55f, 72f, paint)
        paint.color = Hop.Track
        paint.textSize = 22f
        canvas.drawText(subtitle, 55f, 112f, paint)
        images.forEachIndexed { index, image ->
            val x = 40 + index % columns * cell + (cell - image.width) / 2f
            val y = 165 + index / columns * 550 + (480 - image.height) / 2f
            canvas.drawBitmap(image, x, y, null)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(labels[index], 40f + index % columns * cell + cell / 2, 690f + index / columns * 550, paint)
        }
        return bitmap
    }
}
