package com.hamhuo.tplanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.wear.compose.material3.TimePicker
import androidx.wear.compose.material3.TimePickerDefaults
import androidx.wear.compose.material3.TimePickerType
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** The complete, phone-compatible task payload produced by the watch creation flow. */
data class WatchTaskDraft(
    val id: String,
    val title: String,
    val type: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val alarmEnabled: Boolean,
    val alarmOffsetMinutes: Int,
    val colorId: Int,
    val updatedAtEpochMs: Long,
)

/** First creation destination. The Wear OS activity stack owns all back navigation. */
class CreateTitleActivity : WearPageActivity() {
    private lateinit var titleInput: EditText
    private lateinit var draftId: String
    private var draftUpdatedAtEpochMs: Long = 0L

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.task_create_title_page)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        draftId = savedInstanceState?.getString(STATE_DRAFT_ID) ?: UUID.randomUUID().toString()
        draftUpdatedAtEpochMs = savedInstanceState?.getLong(STATE_UPDATED_AT)?.takeIf { it > 0L }
            ?: System.currentTimeMillis()

        titleInput = EditText(this).apply {
            id = View.generateViewId()
            setText(savedInstanceState?.getString(STATE_TITLE).orEmpty())
            setTextColor(CREATION_PRIMARY)
            textSize = 19f
            typeface = CREATION_MEDIUM
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            filters = arrayOf(InputFilter.LengthFilter(MAX_TITLE_LENGTH))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = creationRounded(CREATION_CARD, dp(14).toFloat())
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_NEXT) {
                    continueToType()
                    true
                } else {
                    false
                }
            }
        }

        val content = creationContent().apply {
            addView(creationTopSpacer())
            addView(
                creationHeading(getString(R.string.task_create_title_required)).apply {
                    labelFor = titleInput.id
                },
            )
            addView(
                titleInput,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply {
                    topMargin = dp(10)
                },
            )
            addView(creationBottomSpacer())
        }
        setContentView(creationScrollPage(content))

        titleInput.requestFocus()
        titleInput.postDelayed({
            getSystemService(InputMethodManager::class.java)?.showSoftInput(
                titleInput,
                InputMethodManager.SHOW_IMPLICIT,
            )
        }, KEYBOARD_DELAY_MS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_DRAFT_ID, draftId)
        outState.putLong(STATE_UPDATED_AT, draftUpdatedAtEpochMs)
        outState.putString(STATE_TITLE, titleInput.text?.toString().orEmpty())
        super.onSaveInstanceState(outState)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        propagateCreationResult(requestCode, resultCode)
    }

    private fun continueToType() {
        val taskTitle = titleInput.text?.toString()?.trim().orEmpty()
        if (taskTitle.isEmpty()) {
            titleInput.error = getString(R.string.task_create_title_required)
            titleInput.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return
        }
        titleInput.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        @Suppress("DEPRECATION")
        startActivityForResult(
            CreateTypeActivity.createIntent(
                this,
                CreationRoute(draftId, draftUpdatedAtEpochMs, taskTitle),
            ),
            REQUEST_CREATION_NEXT,
        )
    }

    companion object {
        private const val STATE_DRAFT_ID = "draft_id"
        private const val STATE_UPDATED_AT = "draft_updated_at"
        private const val STATE_TITLE = "draft_title"
        private const val MAX_TITLE_LENGTH = 80
        private const val KEYBOARD_DELAY_MS = 220L

        fun createIntent(context: Context): Intent = Intent(context, CreateTitleActivity::class.java)
    }
}

/** Second destination: select one of the three task types already supported by the phone. */
class CreateTypeActivity : WearPageActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.task_create_type_page)
        val route = intent.creationRouteOrNull() ?: run {
            finish()
            return
        }

        val content = creationContent().apply {
            addView(creationTopSpacer())
            addView(creationHeading(getString(R.string.task_create_type_page)))
            addView(creationTypeButton(
                R.string.task_create_type_event,
                R.string.task_create_type_event_description,
            ) { openTime(route, TYPE_EVENT) })
            addView(creationTypeButton(
                R.string.task_create_type_status,
                R.string.task_create_type_status_description,
            ) { openTime(route, TYPE_STATUS) })
            addView(creationTypeButton(
                R.string.task_create_type_task,
                R.string.task_create_type_task_description,
            ) { openTime(route, TYPE_TASK) })
            addView(creationBottomSpacer())
        }
        setContentView(creationScrollPage(content))
    }

    private fun openTime(route: CreationRoute, type: String) {
        @Suppress("DEPRECATION")
        startActivityForResult(
            CreateTimeActivity.createIntent(this, route.copy(type = type)),
            REQUEST_CREATION_NEXT,
        )
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        propagateCreationResult(requestCode, resultCode)
    }

    companion object {
        fun createIntent(context: Context, route: CreationRoute): Intent =
            Intent(context, CreateTypeActivity::class.java).putCreationRoute(route)
    }
}

/** Third destination: Google's open-source full-screen Wear Material 3 wheel picker. */
class CreateTimeActivity : WearPageActivity() {
    private var destinationOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.task_create_time_page)
        val route = intent.creationRouteOrNull()?.takeIf { !it.type.isNullOrBlank() } ?: run {
            finish()
            return
        }
        val initialTime = LocalTime.now(CREATION_ZONE).withSecond(0).withNano(0)

        setContent {
            TimePicker(
                initialTime = initialTime,
                onTimePicked = { picked ->
                    if (destinationOpened) return@TimePicker
                    destinationOpened = true
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        CreateSettingsActivity.createIntent(
                            this,
                            route.copy(hour = picked.hour, minute = picked.minute),
                        ),
                        REQUEST_CREATION_NEXT,
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor.Black),
                timePickerType = TimePickerType.HoursMinutes24H,
                colors = TimePickerDefaults.timePickerColors(
                    selectedPickerContentColor = ComposeColor(CREATION_PRIMARY),
                    unselectedPickerContentColor = ComposeColor(CREATION_DIM),
                    separatorColor = ComposeColor(CREATION_PRIMARY),
                    pickerLabelColor = ComposeColor(CREATION_ACCENT),
                    confirmButtonContentColor = ComposeColor.Black,
                    confirmButtonContainerColor = ComposeColor(CREATION_ACCENT),
                ),
            )
        }
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
        fun createIntent(context: Context, route: CreationRoute): Intent =
            Intent(context, CreateTimeActivity::class.java).putCreationRoute(route)
    }
}

/** Fourth destination: alarm, date, color, and the final save action. */
class CreateSettingsActivity : WearPageActivity() {
    private lateinit var alarmRow: LinearLayout
    private lateinit var alarmValue: TextView
    private lateinit var dateRow: LinearLayout
    private lateinit var dateValue: TextView
    private lateinit var colorRow: LinearLayout
    private lateinit var colorValue: TextView
    private lateinit var saveButton: TextView
    private lateinit var route: CreationRoute
    private var dayOffset: Int = 0
    private var colorId: Int = 0
    private var alarmEnabled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.task_create_settings_page)
        route = intent.creationRouteOrNull()?.takeIf {
            !it.type.isNullOrBlank() && it.hour in 0..23 && it.minute in 0..59
        } ?: run {
            finish()
            return
        }

        dayOffset = savedInstanceState?.getInt(STATE_DAY_OFFSET) ?: 0
        colorId = savedInstanceState?.getInt(STATE_COLOR_ID) ?: DEFAULT_COLOR_ID
        alarmEnabled = savedInstanceState?.getBoolean(STATE_ALARM_ENABLED)
            ?: (route.type == TYPE_EVENT)

        val content = creationContent().apply {
            addView(creationTopSpacer())
            addView(creationHeading(getString(R.string.task_create_settings_page)))
            addView(createAlarmRow())
            addView(createDateRow())
            addView(createColorRow())
            addView(
                creationActionRow(R.string.task_create_save) {
                    saveTask()
                }.also { saveButton = it },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                    topMargin = dp(9)
                },
            )
            addView(creationBottomSpacer())
        }
        setContentView(creationScrollPage(content))
        renderSettings()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_DAY_OFFSET, dayOffset)
        outState.putInt(STATE_COLOR_ID, colorId)
        outState.putBoolean(STATE_ALARM_ENABLED, alarmEnabled)
        super.onSaveInstanceState(outState)
    }

    private fun createAlarmRow(): LinearLayout {
        alarmRow = creationSettingRow(
            titleRes = R.string.task_create_alarm,
            descriptionRes = R.string.task_create_alarm_description,
        )
        alarmValue = alarmRow.findViewWithTag(TAG_VALUE)
        alarmRow.setOnClickListener {
            alarmEnabled = !alarmEnabled
            renderAlarm()
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        return alarmRow
    }

    private fun createDateRow(): LinearLayout {
        dateRow = creationSettingRow(
            titleRes = R.string.task_create_date,
            descriptionRes = R.string.task_create_date_today,
        )
        dateValue = dateRow.findViewWithTag(TAG_VALUE)
        dateRow.setOnClickListener {
            dayOffset = if (dayOffset == 0) 1 else 0
            renderDate()
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        return dateRow
    }

    private fun createColorRow(): LinearLayout {
        colorRow = creationSettingRow(
            titleRes = R.string.task_create_color,
            descriptionRes = R.string.task_create_color_description,
        )
        colorValue = colorRow.findViewWithTag(TAG_VALUE)
        colorRow.setOnClickListener {
            colorId = (colorId + 1) % TASK_COLORS.size
            renderColor()
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        return colorRow
    }

    private fun renderSettings() {
        renderAlarm()
        renderDate()
        renderColor()
    }

    private fun renderAlarm() {
        val value = getString(if (alarmEnabled) R.string.task_create_on else R.string.task_create_off)
        alarmValue.text = value
        alarmValue.setTextColor(if (alarmEnabled) CREATION_ACCENT else CREATION_DIM)
        alarmValue.contentDescription = value
        alarmRow.contentDescription = getString(
            R.string.task_create_setting_accessibility,
            getString(R.string.task_create_alarm),
            value,
        )
    }

    private fun renderDate() {
        val value = getString(
            if (dayOffset == 0) R.string.task_create_date_today else R.string.task_create_date_tomorrow,
        )
        dateValue.text = value
        dateRow.contentDescription = getString(
            R.string.task_create_setting_accessibility,
            getString(R.string.task_create_date),
            value,
        )
    }

    private fun renderColor() {
        val names = resources.getStringArray(R.array.task_create_color_names)
        val safeColorId = colorId.coerceIn(TASK_COLORS.indices)
        val value = names[safeColorId]
        colorValue.text = value
        colorValue.setTextColor(TASK_COLORS[safeColorId])
        colorRow.contentDescription = getString(
            R.string.task_create_setting_accessibility,
            getString(R.string.task_create_color),
            value,
        )
    }

    private fun saveTask() {
        if (!saveButton.isEnabled) return
        saveButton.isEnabled = false
        saveButton.text = getString(R.string.task_create_saving)

        val start = ZonedDateTime.now(CREATION_ZONE)
            .toLocalDate()
            .plusDays(dayOffset.toLong())
            .atTime(route.hour, route.minute)
            .atZone(CREATION_ZONE)
        val draft = WatchTaskDraft(
            id = route.id,
            title = route.title,
            type = route.type.orEmpty(),
            startEpochMs = start.toInstant().toEpochMilli(),
            endEpochMs = start.plusHours(DEFAULT_DURATION_HOURS).toInstant().toEpochMilli(),
            alarmEnabled = alarmEnabled,
            alarmOffsetMinutes = 0,
            colorId = colorId,
            updatedAtEpochMs = route.updatedAtEpochMs,
        )
        val queued = WatchTaskOutbox.enqueue(this, draft)
        if (!queued) {
            saveButton.isEnabled = true
            saveButton.text = getString(R.string.task_create_save)
            Toast.makeText(this, R.string.task_create_failed, Toast.LENGTH_SHORT).show()
            saveButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return
        }

        saveButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        Toast.makeText(this, R.string.task_create_queued, Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        private const val STATE_DAY_OFFSET = "day_offset"
        private const val STATE_COLOR_ID = "color_id"
        private const val STATE_ALARM_ENABLED = "alarm_enabled"
        private const val DEFAULT_COLOR_ID = 0
        private const val DEFAULT_DURATION_HOURS = 1L

        fun createIntent(context: Context, route: CreationRoute): Intent =
            Intent(context, CreateSettingsActivity::class.java).putCreationRoute(route)
    }
}

data class CreationRoute(
    val id: String,
    val updatedAtEpochMs: Long,
    val title: String,
    val type: String? = null,
    val hour: Int = -1,
    val minute: Int = -1,
)

private fun Context.creationScrollPage(content: LinearLayout): View = FrameLayout(this).apply {
    setBackgroundColor(Color.BLACK)
    addView(
        ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(18), 0, dp(18), dp(24))
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        },
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ),
    )
}

private fun Context.creationContent(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
}

private fun Context.creationTopSpacer(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(29))
}

private fun Context.creationBottomSpacer(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26))
}

private fun Context.creationHeading(value: String): TextView = TextView(this).apply {
    text = value
    setTextColor(CREATION_PRIMARY)
    textSize = 23f
    typeface = CREATION_BOLD
    includeFontPadding = false
    gravity = Gravity.CENTER
    maxLines = 2
    ellipsize = TextUtils.TruncateAt.END
    setPadding(dp(10), dp(3), dp(10), dp(8))
}

private fun Context.creationActionRow(
    textRes: Int,
    action: () -> Unit,
): TextView = TextView(this).apply {
    setText(textRes)
    setTextColor(CREATION_ACCENT)
    textSize = 17f
    typeface = CREATION_BOLD
    includeFontPadding = false
    gravity = Gravity.CENTER
    minimumHeight = dp(54)
    setPadding(dp(18), dp(8), dp(18), dp(8))
    background = creationRippleRounded(Color.TRANSPARENT, CREATION_CARD_PRESSED, dp(14).toFloat())
    isClickable = true
    isFocusable = true
    contentDescription = getString(textRes)
    setOnClickListener { action() }
}

private fun Context.creationTypeButton(
    titleRes: Int,
    descriptionRes: Int,
    action: () -> Unit,
): LinearLayout {
    val title = getString(titleRes)
    val description = getString(descriptionRes)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(61)
        setPadding(dp(13), dp(7), dp(13), dp(7))
        background = creationRippleRounded(CREATION_CARD, CREATION_CARD_PRESSED, dp(14).toFloat())
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.task_create_type_accessibility, title, description)
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(creationRowText(title, 17f, CREATION_PRIMARY, CREATION_MEDIUM))
            addView(creationRowText(description, 12f, CREATION_DIM, CREATION_REGULAR))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(5)
        }
    }
}

private fun Context.creationSettingRow(
    titleRes: Int,
    descriptionRes: Int,
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(66)
    setPadding(dp(13), dp(8), dp(12), dp(8))
    background = creationRippleRounded(CREATION_CARD, CREATION_CARD_PRESSED, dp(14).toFloat())
    isClickable = true
    isFocusable = true
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(5) }

    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(creationRowText(getString(titleRes), 17f, CREATION_PRIMARY, CREATION_MEDIUM))
        addView(creationRowText(getString(descriptionRes), 12f, CREATION_DIM, CREATION_REGULAR).apply {
            tag = TAG_VALUE
        })
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
}

private fun Context.creationRowText(
    value: String,
    sizeSp: Float,
    color: Int,
    font: Typeface,
): TextView = TextView(this).apply {
    text = value
    setTextColor(color)
    textSize = sizeSp
    typeface = font
    includeFontPadding = false
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
}

private fun Intent.putCreationRoute(route: CreationRoute): Intent = apply {
    putExtra(EXTRA_DRAFT_ID, route.id)
    putExtra(EXTRA_DRAFT_UPDATED_AT, route.updatedAtEpochMs)
    putExtra(EXTRA_DRAFT_TITLE, route.title)
    route.type?.let { putExtra(EXTRA_DRAFT_TYPE, it) }
    if (route.hour >= 0) putExtra(EXTRA_DRAFT_HOUR, route.hour)
    if (route.minute >= 0) putExtra(EXTRA_DRAFT_MINUTE, route.minute)
}

private fun Intent.creationRouteOrNull(): CreationRoute? {
    val id = getStringExtra(EXTRA_DRAFT_ID)?.takeIf { it.isNotBlank() } ?: return null
    val updatedAt = getLongExtra(EXTRA_DRAFT_UPDATED_AT, -1L).takeIf { it > 0L } ?: return null
    val title = getStringExtra(EXTRA_DRAFT_TITLE)?.takeIf { it.isNotBlank() } ?: return null
    return CreationRoute(
        id = id,
        updatedAtEpochMs = updatedAt,
        title = title,
        type = getStringExtra(EXTRA_DRAFT_TYPE),
        hour = getIntExtra(EXTRA_DRAFT_HOUR, -1),
        minute = getIntExtra(EXTRA_DRAFT_MINUTE, -1),
    )
}

private fun Activity.propagateCreationResult(requestCode: Int, resultCode: Int) {
    if (requestCode != REQUEST_CREATION_NEXT || resultCode != Activity.RESULT_OK) return
    setResult(Activity.RESULT_OK)
    finish()
}

private fun creationRounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radius
    setColor(color)
}

private fun creationRippleRounded(normal: Int, pressed: Int, radius: Float): RippleDrawable =
    RippleDrawable(
        ColorStateList.valueOf(pressed),
        creationRounded(normal, radius),
        null,
    )

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()

private const val TYPE_EVENT = "event"
private const val TYPE_STATUS = "status"
private const val TYPE_TASK = "task"
private const val TAG_VALUE = "task_creation_value"
private const val EXTRA_DRAFT_ID = "task_creation_id"
private const val EXTRA_DRAFT_UPDATED_AT = "task_creation_updated_at"
private const val EXTRA_DRAFT_TITLE = "task_creation_title"
private const val EXTRA_DRAFT_TYPE = "task_creation_type"
private const val EXTRA_DRAFT_HOUR = "task_creation_hour"
private const val EXTRA_DRAFT_MINUTE = "task_creation_minute"
private const val REQUEST_CREATION_NEXT = 9101
private const val CREATION_PRIMARY = 0xFFF5F5F7.toInt()
private const val CREATION_ACCENT = 0xFFFFD60A.toInt()
private const val CREATION_DIM = 0xFF8E8E93.toInt()
private const val CREATION_CARD = 0xFF202022.toInt()
private const val CREATION_CARD_PRESSED = 0x33FFFFFF
private val CREATION_REGULAR = Typeface.create("sans-serif", Typeface.NORMAL)
private val CREATION_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL)
private val CREATION_BOLD = Typeface.create("sans-serif", Typeface.BOLD)
private val CREATION_ZONE = ZoneId.of(WatchTaskProtocol.DEFAULT_TIME_ZONE_ID)
private val TASK_COLORS = intArrayOf(
    0xFF5B8FCC.toInt(),
    0xFFC9A84C.toInt(),
    0xFFC0697A.toInt(),
    0xFF5B9E72.toInt(),
    0xFF8B6BAE.toInt(),
    0xFFC87D5A.toInt(),
    0xFF4A9DA8.toInt(),
    0xFF8A8A8A.toInt(),
)
