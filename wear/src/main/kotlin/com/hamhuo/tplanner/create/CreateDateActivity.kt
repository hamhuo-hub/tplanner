package com.hamhuo.tplanner
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import kotlin.math.abs



/** Fourth destination: Day / Month selector styled like the Wear date picker, without a year column. */
class CreateDateActivity : WearPageActivity() {
    private var destinationOpened = false
    private var selectedDayField = true
    private var day = 1
    private var month = 1

    private lateinit var dayField: CreationNumberField
    private lateinit var monthField: CreationNumberField

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.task_create_date)

        val route = intent.creationRouteOrNull()?.takeIf {
            !it.type.isNullOrBlank() && it.hour in 0..23 && it.minute in 0..59
        } ?: run {
            finish()
            return
        }

        val today = LocalDate.now(CREATION_ZONE)
        val initialDate = route.dateEpochDay
            .takeIf { it != DATE_EPOCH_DAY_UNSET }
            ?.let { runCatching { LocalDate.ofEpochDay(it) }.getOrNull() }
            ?: today

        day = savedInstanceState?.getInt(STATE_DAY, initialDate.dayOfMonth) ?: initialDate.dayOfMonth
        month = savedInstanceState?.getInt(STATE_MONTH, initialDate.monthValue) ?: initialDate.monthValue
        selectedDayField = savedInstanceState?.getBoolean(STATE_SELECTED_DAY, true) ?: true

        dayField = creationNumberField(R.string.task_create_day) {
            selectedDayField = true
            renderSelection()
        }
        monthField = creationNumberField(R.string.task_create_month) {
            selectedDayField = false
            renderSelection()
        }
        // Keep vertical adjustment on the numeric value; the label and page still scroll.
        attachVerticalAdjustGesture(dayField.value, selectDay = true)
        attachVerticalAdjustGesture(monthField.value, selectDay = false)
        val content = creationContent().apply {
            addView(creationTopSpacer())
            addView(creationHeading(getString(R.string.task_create_date)))
            addView(creationNumberFields(dayField, monthField))
            addView(creationStepperRow { adjust(it) })
            addView(creationActionRow(R.string.task_create_next, primary = true) {
                if (destinationOpened) return@creationActionRow
                destinationOpened = true
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                @Suppress("DEPRECATION")
                startActivityForResult(
                    CreateSettingsActivity.createIntent(
                        this@CreateDateActivity,
                        route.copy(dateEpochDay = resolveSelectedDate().toEpochDay()),
                    ),
                    REQUEST_CREATION_NEXT,
                )
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) })
            addView(creationBottomSpacer())
        }
        setContentView(creationScrollPage(content))
        renderDate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_DAY, day)
        outState.putInt(STATE_MONTH, month)
        outState.putBoolean(STATE_SELECTED_DAY, selectedDayField)
        super.onSaveInstanceState(outState)
    }

    /**
     * The year is intentionally absent from the UI.
     * If the selected month/day has already passed this year, use its next occurrence.
     */
    private fun resolveSelectedDate(): LocalDate {
        val today = LocalDate.now(CREATION_ZONE)
        var year = today.year

        var safeDay = day.coerceAtMost(YearMonth.of(year, month).lengthOfMonth())
        var candidate = LocalDate.of(year, month, safeDay)

        if (candidate.isBefore(today)) {
            year += 1
            safeDay = day.coerceAtMost(YearMonth.of(year, month).lengthOfMonth())
            candidate = LocalDate.of(year, month, safeDay)
        }
        return candidate
    }

    private fun adjust(delta: Int) {
        if (selectedDayField) {
            val year = inferredYearFor(month, day)
            val maxDay = YearMonth.of(year, month).lengthOfMonth()
            day = when {
                day + delta > maxDay -> 1
                day + delta < 1 -> maxDay
                else -> day + delta
            }
        } else {
            month = ((month - 1 + delta) % 12 + 12) % 12 + 1
            val year = inferredYearFor(month, day)
            day = day.coerceAtMost(YearMonth.of(year, month).lengthOfMonth())
        }

        renderDate()
    }

    private fun inferredYearFor(monthValue: Int, dayValue: Int): Int {
        val today = LocalDate.now(CREATION_ZONE)
        val currentYearMax = YearMonth.of(today.year, monthValue).lengthOfMonth()
        val safeDay = dayValue.coerceAtMost(currentYearMax)
        val candidate = LocalDate.of(today.year, monthValue, safeDay)
        return if (candidate.isBefore(today)) today.year + 1 else today.year
    }

    private fun renderDate() {
        val locale = currentWatchLocale()
        dayField.value.text = "%02d".format(locale, day)
        monthField.value.text = java.time.Month.of(month).getDisplayName(TextStyle.SHORT, locale)
        renderSelection()
    }

    private fun renderSelection() {
        dayField.renderSelection(selectedDayField)
        monthField.renderSelection(!selectedDayField)
    }

    @Suppress("ClickableViewAccessibility")
    private fun attachVerticalAdjustGesture(view: View, selectDay: Boolean) {
        var downY = 0f

        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    target.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (selectedDayField != selectDay) {
                        selectedDayField = selectDay
                        renderSelection()
                    }

                    target.parent.requestDisallowInterceptTouchEvent(false)
                    val deltaY = event.y - downY
                    if (abs(deltaY) >= dp(18)) {
                        adjust(if (deltaY < 0f) 1 else -1)
                        target.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    } else {
                        (target.parent as? View)?.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    target.parent.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> true
            }
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        if (event.rotaryScrollAxisOrNull() != null) onGenericMotionEvent(event)
        else super.dispatchGenericMotionEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        val crownAxis = event?.rotaryScrollAxisOrNull()
            ?: return super.onGenericMotionEvent(event)
        adjust(if (crownAxis > 0f) 1 else -1)
        (if (selectedDayField) dayField.root else monthField.root)
            .performCrownItemFocusFeedback(event)
        return true
    }

    override fun onResume() {
        super.onResume()
        destinationOpened = false
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        propagateCreationResult(requestCode, resultCode)
    }

    companion object {
        private const val STATE_DAY = "selected_day"
        private const val STATE_MONTH = "selected_month"
        private const val STATE_SELECTED_DAY = "is_day_selected"
        fun createIntent(context: Context, route: CreationRoute): Intent =
            Intent(context, CreateDateActivity::class.java).putCreationRoute(route)
    }
}
