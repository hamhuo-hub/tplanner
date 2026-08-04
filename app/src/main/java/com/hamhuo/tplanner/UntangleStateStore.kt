package com.hamhuo.tplanner

import android.content.Context
import androidx.room.withTransaction
import com.hamhuo.tplanner.persistence.PendingActionEntity
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import com.hamhuo.tplanner.persistence.DurableWriteQueue
import org.json.JSONArray
import org.json.JSONObject

enum class UntanglePhase { EDITING, THINKING, PROPOSAL }

internal const val UNTANGLE_QUEUE_KEY = "untangle"

data class UntangleRecoveryState(
    val requestId: String,
    val inputText: String,
    val location: String,
    val lat: Double,
    val lng: Double,
    val phase: UntanglePhase,
    val proposal: DeepSeekAnalysisService.ProposedAction? = null,
    /** Immutable journal operation captured at submit time for exact retry across restarts/days. */
    val journalDate: String = "",
    val journalLine: String = "",
    val submissionInput: String = "",
    val submissionStamp: String = "",
    val submissionLocation: String = "",
    /** Old interrupted rows without an immutable journal operation must start a new generation. */
    val requiresFreshSubmission: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

class UntangleStateStore(
    context: Context,
    private val database: TPlannerDatabase = TPlannerDatabase.get(context),
) {
    suspend fun latest(): UntangleRecoveryState? =
        DurableWriteQueue.readAfterPending(UNTANGLE_QUEUE_KEY) {
            database.withTransaction {
                val rows = database.pendingActionDao().all(KIND)
                val recoverable = rows.mapNotNull { row -> decode(row)?.let { row to it } }
                val selected = recoverable.firstOrNull()
                // The UI owns one operation. Remove only older rows proven decodable; retain an
                // unreadable payload for a future explicit migration instead of destroying it.
                recoverable.drop(1).forEach { (row, _) ->
                    database.pendingActionDao().delete(row.requestId)
                }
                selected?.second
            }
        }

    suspend fun save(state: UntangleRecoveryState) {
        DurableWriteQueue.submitAndAwait(UNTANGLE_QUEUE_KEY) { saveNow(state) }
    }

    fun enqueue(state: UntangleRecoveryState) {
        DurableWriteQueue.submit(UNTANGLE_QUEUE_KEY) { saveNow(state) }
    }

    private suspend fun saveNow(state: UntangleRecoveryState) {
        database.pendingActionDao().upsert(toEntity(state))
    }

    /** Atomically replaces a superseded UI operation while keeping at least one durable row. */
    suspend fun replace(previousRequestId: String, state: UntangleRecoveryState) {
        DurableWriteQueue.submitAndAwait(UNTANGLE_QUEUE_KEY) {
            database.withTransaction {
                database.pendingActionDao().upsert(toEntity(state))
                if (previousRequestId.isNotBlank() && previousRequestId != state.requestId) {
                    database.pendingActionDao().delete(previousRequestId)
                }
            }
        }
    }

    suspend fun delete(requestId: String) {
        DurableWriteQueue.submitAndAwait(UNTANGLE_QUEUE_KEY) {
            database.pendingActionDao().delete(requestId)
        }
    }

    private fun encode(state: UntangleRecoveryState): String = JSONObject().apply {
        put("requestId", state.requestId)
        put("inputText", state.inputText)
        put("location", state.location)
        put("lat", state.lat)
        put("lng", state.lng)
        put("phase", state.phase.name)
        put("journalDate", state.journalDate)
        put("journalLine", state.journalLine)
        put("submissionInput", state.submissionInput)
        put("submissionStamp", state.submissionStamp)
        put("submissionLocation", state.submissionLocation)
        put("requiresFreshSubmission", state.requiresFreshSubmission)
        put("updatedAt", state.updatedAt)
        state.proposal?.let { action ->
            put("proposal", JSONObject().apply {
                put("type", action.type)
                put("title", action.title)
                put("startIso", action.startIso)
                put("endIso", action.endIso)
                put("note", action.note)
                put("colorId", action.colorId)
                put("checklist", JSONArray(action.checklist))
                put("alarmEnabled", action.alarmEnabled)
                put("alarmOffsetMinutes", action.alarmOffsetMinutes)
                put("requestId", action.requestId)
            })
        }
    }.toString()

    private fun decode(row: PendingActionEntity): UntangleRecoveryState? = runCatching {
        val obj = JSONObject(row.payloadJson)
        val proposal = obj.optJSONObject("proposal")?.let { action ->
            val checklist = action.optJSONArray("checklist") ?: JSONArray()
            DeepSeekAnalysisService.ProposedAction(
                type = action.optString("type", "event"),
                title = action.optString("title", ""),
                startIso = action.optString("startIso", ""),
                endIso = action.optString("endIso", ""),
                note = action.optString("note", ""),
                colorId = action.optInt("colorId", 0),
                checklist = (0 until checklist.length()).map(checklist::getString),
                alarmEnabled = action.optBoolean("alarmEnabled", false),
                alarmOffsetMinutes = action.optInt("alarmOffsetMinutes", 0),
                requestId = action.optString("requestId", row.requestId),
            )
        }
        val storedPhase = runCatching {
            UntanglePhase.valueOf(obj.optString("phase", row.state))
        }.getOrDefault(UntanglePhase.EDITING)
        val journalDate = obj.optString("journalDate", "")
        val journalLine = obj.optString("journalLine", "")
        val submissionInput = obj.optString("submissionInput", "")
        val submissionStamp = obj.optString("submissionStamp", "")
        val requiresFreshSubmission = obj.optBoolean("requiresFreshSubmission", false) ||
            (storedPhase != UntanglePhase.PROPOSAL &&
                (journalDate.isBlank() || journalLine.isBlank() || submissionStamp.isBlank()))
        UntangleRecoveryState(
            requestId = obj.optString("requestId", row.requestId),
            inputText = obj.optString("inputText", ""),
            location = obj.optString("location", ""),
            lat = obj.optDouble("lat", 0.0),
            lng = obj.optDouble("lng", 0.0),
            // A killed HTTP call cannot still be running; restore its exact input as editable.
            phase = if (storedPhase == UntanglePhase.THINKING) UntanglePhase.EDITING else storedPhase,
            proposal = proposal,
            journalDate = journalDate,
            journalLine = journalLine,
            submissionInput = submissionInput,
            submissionStamp = submissionStamp,
            submissionLocation = obj.optString("submissionLocation", ""),
            requiresFreshSubmission = requiresFreshSubmission,
            updatedAt = obj.optLong("updatedAt", row.updatedAt),
        )
    }.getOrNull()

    companion object {
        const val KIND = "UNTANGLE"
    }

    private suspend fun toEntity(state: UntangleRecoveryState): PendingActionEntity =
        PendingActionEntity(
            requestId = state.requestId,
            kind = KIND,
            state = state.phase.name,
            payloadJson = encode(state),
            createdAt = database.pendingActionDao().get(state.requestId)?.createdAt
                ?: state.updatedAt,
            updatedAt = state.updatedAt,
        )
}
