package com.hamhuo.tplanner

import android.content.Context
import android.widget.Toast
import com.hamhuo.tplanner.persistence.DraftCommitResult
import com.hamhuo.tplanner.persistence.DraftConflict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class JournalConflictPrompt(val details: DraftConflict) {
    val date: String get() = details.target.entityId
    val draftText: String get() = details.draftContent
}

class JournalActions(
    private val scope: CoroutineScope,
    private val context: Context,
    private val store: JournalStore,
    private val mutex: Mutex,
    private val getDateKey: () -> String,
    private val getContent: () -> String,
    private val setContent: (String) -> Unit,
    private val getHasDraft: () -> Boolean,
    private val setHasDraft: (Boolean) -> Unit,
    private val getConflict: () -> JournalConflictPrompt?,
    private val setConflict: (JournalConflictPrompt?) -> Unit,
) {
    fun saveDraft(text: String) {
        setHasDraft(true)
        store.enqueueDraft(getDateKey(), text)
    }

    fun commitDraft(text: String) {
        setHasDraft(true)
        store.enqueueDraft(getDateKey(), text)
        scope.launch {
            try {
                val result = mutex.withLock {
                    store.commitDraft(getDateKey(), text)
                }
                when (result) {
                    DraftCommitResult.Saved,
                    DraftCommitResult.AlreadySaved,
                    -> setHasDraft(false)
                    is DraftCommitResult.Conflict -> {
                        setHasDraft(true)
                        setConflict(JournalConflictPrompt(result.details))
                        Toast.makeText(
                            context,
                            "内容已在其他设备修改，当前草稿已安全保留",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (_: Exception) {
                setHasDraft(true)
                Toast.makeText(context, "保存失败，草稿仍保留在本机", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun resolveOverwrite(details: DraftConflict) {
        val overwritten = try {
            kotlinx.coroutines.runBlocking {
                mutex.withLock { store.overwriteDraft(details) }
            }
        } catch (_: Exception) { false }
        if (overwritten) {
            if (details.target.entityId == getDateKey()) {
                setContent(details.draftContent)
                setHasDraft(false)
            }
            setConflict(null)
            Toast.makeText(context, "已采用当前草稿", Toast.LENGTH_SHORT).show()
        }
    }

    fun resolveDiscard(details: DraftConflict) {
        scope.launch {
            try {
                mutex.withLock { store.discardDraft(details) }
                setConflict(null)
                setHasDraft(false)
            } catch (_: Exception) {
                Toast.makeText(context, "操作失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
