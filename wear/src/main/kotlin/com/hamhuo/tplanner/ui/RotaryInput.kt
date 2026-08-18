package com.hamhuo.tplanner

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.roundToInt

/** Returns the crown axis only for a real rotary scroll event. */
internal fun MotionEvent.rotaryScrollAxisOrNull(): Float? {
    if (actionMasked != MotionEvent.ACTION_SCROLL) return null
    if (!isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return null
    return getAxisValue(MotionEvent.AXIS_SCROLL).takeIf { it != 0f }
}

/** Scrolls the first visible vertical page contained by this view hierarchy. */
internal fun View.scrollPageWithCrown(axis: Float): Boolean {
    val target = findVisibleScrollView() ?: return false
    val factor = ViewConfiguration.get(context).scaledVerticalScrollFactor
    val rawDelta = -axis * factor
    val delta = rawDelta.roundToInt().takeIf { it != 0 }
        ?: if (axis > 0f) -1 else 1
    target.scrollBy(0, delta)
    target.postInvalidateOnAnimation()
    return true
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
