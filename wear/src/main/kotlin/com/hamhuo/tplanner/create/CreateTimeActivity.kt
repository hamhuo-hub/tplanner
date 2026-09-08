package com.hamhuo.tplanner

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import java.time.LocalTime

/** Time fields grow with the user's font size and remain reachable on round displays. */
class CreateTimeActivity : WearPageActivity() {
    private var destinationOpened = false
    private var selectedHour = true
    private var hour = 0
    private var minute = 0
    private lateinit var hourField: CreationNumberField
    private lateinit var minuteField: CreationNumberField

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.task_create_time_page)
        val route = intent.creationRouteOrNull()?.takeIf { !it.type.isNullOrBlank() } ?: run {
            finish()
            return
        }
        val now = LocalTime.now(CREATION_ZONE)
        hour = savedInstanceState?.getInt(STATE_HOUR, now.hour) ?: now.hour
        minute = savedInstanceState?.getInt(STATE_MINUTE, now.minute) ?: now.minute
        selectedHour = savedInstanceState?.getBoolean(STATE_SELECTED_HOUR, true) ?: true

        hourField = creationNumberField(R.string.task_create_hour) {
            selectedHour = true
            renderSelection()
        }
        minuteField = creationNumberField(R.string.task_create_minute) {
            selectedHour = false
            renderSelection()
        }
        val content = creationContent().apply {
            addView(creationTopSpacer())
            addView(creationHeading(getString(R.string.task_create_time_page)))
            addView(creationNumberFields(hourField, minuteField))
            addView(creationStepperRow { adjust(it) })
            addView(creationActionRow(R.string.task_create_next, primary = true) {
                if (destinationOpened) return@creationActionRow
                destinationOpened = true
                @Suppress("DEPRECATION")
                startActivityForResult(
                    CreateDateActivity.createIntent(this@CreateTimeActivity, route.copy(hour = hour, minute = minute)),
                    REQUEST_CREATION_NEXT,
                )
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) })
            addView(creationBottomSpacer())
        }
        setContentView(creationScrollPage(content))
        renderTime()
    }

    // The page can scroll by touch, while the crown continues to edit the selected field.
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        if (event.rotaryScrollAxisOrNull() != null) onGenericMotionEvent(event)
        else super.dispatchGenericMotionEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        val crownAxis = event?.rotaryScrollAxisOrNull() ?: return super.onGenericMotionEvent(event)
        adjust(if (crownAxis > 0f) 1 else -1)
        (if (selectedHour) hourField.root else minuteField.root).performCrownItemFocusFeedback(event)
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_HOUR, hour)
        outState.putInt(STATE_MINUTE, minute)
        outState.putBoolean(STATE_SELECTED_HOUR, selectedHour)
        super.onSaveInstanceState(outState)
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

    private fun adjust(delta: Int) {
        if (selectedHour) hour = ((hour + delta) % 24 + 24) % 24
        else minute = ((minute + delta) % 60 + 60) % 60
        renderTime()
    }

    private fun renderTime() {
        hourField.value.text = "%02d".format(currentWatchLocale(), hour)
        minuteField.value.text = "%02d".format(currentWatchLocale(), minute)
        renderSelection()
    }

    private fun renderSelection() {
        hourField.renderSelection(selectedHour)
        minuteField.renderSelection(!selectedHour)
    }

    companion object {
        private const val STATE_HOUR = "selected_hour"
        private const val STATE_MINUTE = "selected_minute"
        private const val STATE_SELECTED_HOUR = "is_hour_selected"
        fun createIntent(context: Context, route: CreationRoute): Intent =
            Intent(context, CreateTimeActivity::class.java).putCreationRoute(route)
    }
}
