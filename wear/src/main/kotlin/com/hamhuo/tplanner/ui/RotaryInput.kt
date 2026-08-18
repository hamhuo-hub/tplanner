package com.hamhuo.tplanner

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScrollFeedbackProvider
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.annotation.RequiresApi
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Returns the crown axis only for a real rotary scroll event. */
internal fun MotionEvent.rotaryScrollAxisOrNull(): Float? {
    if (actionMasked != MotionEvent.ACTION_SCROLL) return null
    if (!isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return null
    return getAxisValue(MotionEvent.AXIS_SCROLL).takeIf { it != 0f }
}

/** Scrolls the first visible vertical page and reports crown feedback to the system. */
internal fun View.scrollPageWithCrown(event: MotionEvent): Boolean {
    val axis = event.rotaryScrollAxisOrNull() ?: return false
    val target = findVisibleScrollView() ?: return false
    val factor = ViewConfiguration.get(context).scaledVerticalScrollFactor
    val rawDelta = -axis * factor
    val delta = rawDelta.roundToInt().takeIf { it != 0 }
        ?: if (axis > 0f) -1 else 1
    val previousY = target.scrollY
    target.scrollBy(0, delta)
    target.postInvalidateOnAnimation()
    val consumedDelta = target.scrollY - previousY
    target.reportCrownScrollFeedback(event, consumedDelta, delta < 0)
    return true
}

/** Reports a discrete field change driven by the crown. */
internal fun View.performCrownItemFocusFeedback(event: MotionEvent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        Api35CrownFeedback.providerFor(this).onSnapToItem(
            event.deviceId,
            event.source,
            MotionEvent.AXIS_SCROLL,
        )
    } else {
        performHapticFeedback(
            wearableScrollHaptics?.itemFocus ?: fallbackItemFocusHaptic(),
        )
    }
}

private fun View.reportCrownScrollFeedback(
    event: MotionEvent,
    consumedDelta: Int,
    isStart: Boolean,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val provider = Api35CrownFeedback.providerFor(this)
        if (consumedDelta == 0) {
            provider.onScrollLimit(
                event.deviceId,
                event.source,
                MotionEvent.AXIS_SCROLL,
                isStart,
            )
        } else {
            provider.onScrollProgress(
                event.deviceId,
                event.source,
                MotionEvent.AXIS_SCROLL,
                consumedDelta,
            )
        }
        return
    }

    performHapticFeedback(
        if (consumedDelta == 0) {
            wearableScrollHaptics?.limit ?: HapticFeedbackConstants.LONG_PRESS
        } else {
            wearableScrollHaptics?.tick ?: fallbackScrollTickHaptic()
        },
    )
}

private fun fallbackScrollTickHaptic(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // SEGMENT_TICK is deliberately more perceptible than SEGMENT_FREQUENT_TICK.
        HapticFeedbackConstants.SEGMENT_TICK
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

private fun fallbackItemFocusHaptic(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HapticFeedbackConstants.SEGMENT_TICK
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }

private data class WearableScrollHaptics(
    val tick: Int,
    val itemFocus: Int,
    val limit: Int,
)

/**
 * Wear SDK 34.1 is a system shared-library API rather than part of android.jar.
 * Resolve it when the watch exposes it, while keeping the APK compatible with older watches.
 */
private val wearableScrollHaptics: WearableScrollHaptics? by lazy {
    runCatching {
        val constants = Class.forName("com.google.wear.input.WearHapticFeedbackConstants")
        WearableScrollHaptics(
            tick = constants.getMethod("getScrollTick").invoke(null) as Int,
            itemFocus = constants.getMethod("getScrollItemFocus").invoke(null) as Int,
            limit = constants.getMethod("getScrollLimit").invoke(null) as Int,
        )
    }.getOrNull()
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private object Api35CrownFeedback {
    private val providers = WeakHashMap<View, ScrollFeedbackProvider>()

    fun providerFor(view: View): ScrollFeedbackProvider =
        providers.getOrPut(view) { ScrollFeedbackProvider.createProvider(view) }
}

private fun View.findVisibleScrollView(): ScrollView? {
    if (visibility != View.VISIBLE) return null
    if (this is ScrollView) return this
    if (this !is ViewGroup) return null
    for (index in 0 until childCount) {
        getChildAt(index).findVisibleScrollView()?.let { return it }
    }
    return null
}
