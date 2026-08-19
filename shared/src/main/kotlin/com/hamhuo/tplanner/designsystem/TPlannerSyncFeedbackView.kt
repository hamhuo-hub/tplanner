package com.hamhuo.tplanner.designsystem

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.TextView

enum class TPlannerSyncFeedbackTone {
    ACCENT,
    SUCCESS,
    ERROR,
}

/**
 * Compact top-of-screen sync feedback shared by the phone and Wear launchers.
 *
 * Deliberately extends the framework [TextView] rather than AppCompatTextView:
 * this design-system primitive sets its own colors/background/typeface and uses no
 * AppCompat theming, and the :wear module (which also compiles shared sources)
 * does not depend on androidx.appcompat.
 */
@SuppressLint("AppCompatCustomView")
class TPlannerSyncFeedbackView(context: Context) : TextView(context) {
    private val hideFeedback = Runnable {
        animate()
            .alpha(0f)
            .setDuration(FADE_OUT_DURATION_MILLIS)
            .withEndAction { visibility = INVISIBLE }
            .start()
    }

    init {
        gravity = Gravity.CENTER
        letterSpacing = 0.02f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(MAX_WIDTH_DP)
        setPadding(dp(HORIZONTAL_PADDING_DP), dp(VERTICAL_PADDING_DP), dp(HORIZONTAL_PADDING_DP), dp(VERTICAL_PADDING_DP))
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textSize = TEXT_SIZE_SP
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
            setColor(TPlannerColors.SurfaceRaised)
            setStroke(dp(1), TPlannerColors.Border)
        }
        alpha = 0f
        visibility = INVISIBLE
        elevation = dp(ELEVATION_DP).toFloat()
        isClickable = false
        isFocusable = false
    }

    fun show(message: String, tone: TPlannerSyncFeedbackTone, autoHide: Boolean) {
        removeCallbacks(hideFeedback)
        animate().cancel()
        text = message
        setTextColor(
            when (tone) {
                TPlannerSyncFeedbackTone.ACCENT -> TPlannerColors.Gold
                TPlannerSyncFeedbackTone.SUCCESS -> TPlannerColors.Teal
                TPlannerSyncFeedbackTone.ERROR -> TPlannerColors.Red
            },
        )
        contentDescription = message
        visibility = VISIBLE
        alpha = 1f
        announceForAccessibility(message)
        if (autoHide) postDelayed(hideFeedback, RESULT_VISIBLE_DURATION_MILLIS)
    }

    fun hide() {
        removeCallbacks(hideFeedback)
        animate().cancel()
        alpha = 0f
        visibility = INVISIBLE
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideFeedback)
        animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val TEXT_SIZE_SP = 10f
        const val MAX_WIDTH_DP = 172
        const val HORIZONTAL_PADDING_DP = 12
        const val VERTICAL_PADDING_DP = 6
        const val CORNER_RADIUS_DP = 15
        const val ELEVATION_DP = 8
        const val RESULT_VISIBLE_DURATION_MILLIS = 1_600L
        const val FADE_OUT_DURATION_MILLIS = 180L
    }
}
