package com.hamhuo.tplanner
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import java.util.UUID

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
