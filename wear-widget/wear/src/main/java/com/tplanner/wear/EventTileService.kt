package com.tplanner.wear

import android.content.Context
import androidx.wear.tiles.*
import androidx.wear.tiles.material.*
import androidx.wear.tiles.material.layouts.PrimaryLayout
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.future
import java.text.SimpleDateFormat
import java.util.*

class EventTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(request: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        scope.future { buildTile(this@EventTileService) }

    override fun onResourcesRequest(request: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        scope.future {
            ResourceBuilders.Resources.Builder()
                .setVersion("1")
                .build()
        }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }

    companion object {
        fun requestUpdate(ctx: Context) {
            getUpdater(ctx).requestUpdate(EventTileService::class.java)
        }
    }
}

private fun buildTile(ctx: Context): TileBuilders.Tile {
    val events  = ctx.loadEvents()
    val todays  = events.todayActive()
    val current = todays.currentEvent()
    val next    = if (current == null) todays.nextEvent() else null

    val taskTotal = todays.count { it.type == "task" }
    val taskDone  = todays.count { it.type == "task" && it.completed }

    val dateFmt = SimpleDateFormat("M月d日 E", Locale.CHINESE)
    val dateStr = dateFmt.format(Date())

    val focusEvent = current ?: next
    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    val colors = Colors.Builder()
        .setPrimary(0xFF_C9A84C.toInt())      // gold
        .setOnPrimary(0xFF_0A0A0A.toInt())
        .setSurface(0xFF_1E1E1E.toInt())
        .setOnSurface(0xFF_E8E0D0.toInt())
        .build()
    val theme = MaterialTheme.Builder().setColors(colors).build()

    val primaryLabel = Text.Builder(ctx, focusEvent?.title ?: "今日空闲")
        .setTypography(Typography.TYPOGRAPHY_BODY1)
        .setColor(ArgbColor.argb(if (current != null) 0xFF_C9A84C.toInt() else 0xFF_E8E0D0.toInt()))
        .build()

    val secondaryLabel = Text.Builder(ctx,
        when {
            current != null -> "进行中 · ${timeFmt.format(parseIso(current.end))}"
            next    != null -> "稍后 ${timeFmt.format(parseIso(next.start))}"
            taskTotal > 0   -> "任务 $taskDone/$taskTotal"
            else             -> dateStr
        }
    ).setTypography(Typography.TYPOGRAPHY_CAPTION1)
     .setColor(ArgbColor.argb(0xFF_7A7163.toInt()))
     .build()

    val layout = PrimaryLayout.Builder(DeviceParameters.Builder()
        .setScreenWidthDp(195).setScreenHeightDp(195)
        .setScreenShape(DeviceParameters.SCREEN_SHAPE_ROUND)
        .build())
        .setContent(
            Column.Builder()
                .addContent(Text.Builder(ctx, dateStr)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(ArgbColor.argb(0xFF_C9A84C.toInt()))
                    .build())
                .addContent(primaryLabel)
                .addContent(secondaryLabel)
                .build()
        )
        .build()

    val timeline = TimelineBuilders.Timeline.Builder()
        .addTimelineEntry(TimelineBuilders.TimelineEntry.Builder()
            .setLayout(LayoutElementBuilders.Layout.Builder()
                .setRoot(layout.toLayoutElementBuilder()).build())
            .build())
        .build()

    return TileBuilders.Tile.Builder()
        .setResourcesVersion("1")
        .setTimeline(timeline)
        .setFreshnessIntervalMillis(30 * 60 * 1000L) // refresh every 30 min
        .build()
}

private fun parseIso(iso: String): Date {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.parse(iso.substring(0, 19)) ?: Date()
    } catch (_: Exception) { Date() }
}
