package com.hamhuo.tplanner.persistence

import com.hamhuo.tplanner.ScheduleItem
import org.json.JSONObject

enum class EventEditStage { NAMING, DETAIL }

data class EventEditDraftSnapshot(
    val event: ScheduleItem,
    val stage: EventEditStage,
)

/** Versioned envelope so a raw legacy note draft can never be mistaken for a full event snapshot. */
object EventEditDraftCodec {
    private const val FORMAT = "tplanner-event-edit-v1"

    /**
     * DETAIL deliberately omits the stage field to preserve the exact v1 serialization used by
     * existing base hashes. NAMING is the only additional persisted state.
     */
    fun encode(
        event: ScheduleItem,
        stage: EventEditStage = EventEditStage.DETAIL,
    ): String = JSONObject().apply {
        put("format", FORMAT)
        if (stage != EventEditStage.DETAIL) put("stage", stage.name)
        put("event", EventWireMapper.encodeObject(event))
    }.toString()

    fun decodeSnapshotOrNull(payload: String): EventEditDraftSnapshot? = runCatching {
        val envelope = JSONObject(payload)
        if (envelope.optString("format") != FORMAT) return null
        EventEditDraftSnapshot(
            event = EventWireMapper.decodeObject(envelope.getJSONObject("event")),
            stage = runCatching {
                EventEditStage.valueOf(
                    envelope.optString("stage", EventEditStage.DETAIL.name)
                )
            }.getOrDefault(EventEditStage.DETAIL),
        )
    }.getOrNull()

    fun decodeOrNull(payload: String): ScheduleItem? = decodeSnapshotOrNull(payload)?.event
}

sealed interface EventDraftRecovery {
    data object None : EventDraftRecovery
    data class Recovered(
        val event: ScheduleItem,
        val isNew: Boolean,
        val stage: EventEditStage = EventEditStage.DETAIL,
    ) : EventDraftRecovery

    data class Conflict(
        val details: DraftConflict,
        val event: ScheduleItem? = null,
        val stage: EventEditStage = EventEditStage.DETAIL,
    ) : EventDraftRecovery
}
