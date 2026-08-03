package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.persistence.PendingActionEntity
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import org.json.JSONArray
import org.json.JSONObject

enum class UntanglePhase { EDITING, THINKING, PROPOSAL }

data class UntangleRecoveryState(
    val requestId: String,
    val inputText: String,
    val location: String,
    val lat: Double,
    val lng: Double,
    val phase: UntanglePhase,
    val proposal: DeepSeekAnalysisService.ProposedAction? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

class UntangleStateStore(
    context: Context,
    private val database: TPlannerDatabase = TPlannerDatabase.get(context),
) {
    suspend fun latest(): UntangleRecoveryState? = database.pendingActionDao().latest(KIND)
        ?.let(::decode)

    suspend fun save(state: UntangleRecoveryState) {
        database.pendingActionDao().upsert(
            PendingActionEntity(
                requestId = state.requestId,
                kind = KIND,
                state = state.phase.name,
                payloadJson = encode(state),
                createdAt = database.pendingActionDao().get(state.requestId)?.createdAt
                    ?: state.updatedAt,
                updatedAt = state.updatedAt,
            )
        )
    }

    suspend fun delete(requestId: String) {
        database.pendingActionDao().delete(requestId)
    }

    private fun encode(state: UntangleRecoveryState): String = JSONObject().apply {
        put("requestId", state.requestId)
        put("inputText", state.inputText)
        put("location", state.location)
        put("lat", state.lat)
        put("lng", state.lng)
        put("phase", state.phase.name)
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
        UntangleRecoveryState(
            requestId = obj.optString("requestId", row.requestId),
            inputText = obj.optString("inputText", ""),
            location = obj.optString("location", ""),
            lat = obj.optDouble("lat", 0.0),
            lng = obj.optDouble("lng", 0.0),
            // A killed HTTP call cannot still be running; restore its exact input as editable.
            phase = if (storedPhase == UntanglePhase.THINKING) UntanglePhase.EDITING else storedPhase,
            proposal = proposal,
            updatedAt = obj.optLong("updatedAt", row.updatedAt),
        )
    }.getOrNull()

    companion object {
        const val KIND = "UNTANGLE"
    }
}

