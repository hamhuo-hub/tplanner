package com.hamhuo.tplanner.timeline

import com.hamhuo.tplanner.TaskEvent
import java.time.Instant

/**
 * A per-day visual segment. [visibleStart]/[visibleEnd] may be clipped at a day
 * boundary, while [event] always remains the original, unsplit entity.
 */
internal data class TimelinePlacement(
    val event: TaskEvent,
    val visibleStart: Instant,
    val visibleEnd: Instant,
    val laneIndex: Int,
    val laneCount: Int,
    val conflictIds: Set<String>,
    val isShadow: Boolean = false,
)

internal object TimelineLayoutEngine {

    /**
     * Builds render-only segments for the half-open interval
     * `[dayStart, dayEnd)`. No event is mutated or split in storage.
     */
    internal fun layoutDay(
        events: List<TaskEvent>,
        dayStart: Instant,
        dayEnd: Instant,
    ): List<TimelinePlacement> {
        if (!dayEnd.isAfter(dayStart)) return emptyList()

        val timedEvents = events
            .asSequence()
            .filter { it.deletedAt == 0L }
            .filter { it.type != "status" }
            .filter { it.end.isAfter(it.start) }
            .filter { overlaps(it.start, it.end, dayStart, dayEnd) }
            .sortedWith(compareBy<TaskEvent>({ it.start }, { it.end }, { it.id }))
            .toList()

        val conflictIdsByEvent = timedEvents.associate { it.id to linkedSetOf<String>() }
        for (leftIndex in timedEvents.indices) {
            val left = timedEvents[leftIndex]
            for (rightIndex in leftIndex + 1 until timedEvents.size) {
                val right = timedEvents[rightIndex]
                if (!right.start.isBefore(left.end)) break
                if (!isConflictPair(left, right)) continue
                conflictIdsByEvent.getValue(left.id) += right.id
                conflictIdsByEvent.getValue(right.id) += left.id
            }
        }

        val placements = mutableListOf<TimelinePlacement>()
        splitIntoOverlapClusters(timedEvents).forEach { cluster ->
            val lanes = mutableListOf<MutableList<TaskEvent>>()
            val assigned = cluster.map { event ->
                val laneIndex = lanes.indexOfFirst { lane ->
                    lane.none { existing ->
                        overlaps(existing.start, existing.end, event.start, event.end)
                    }
                }.takeIf { it >= 0 } ?: lanes.size
                if (laneIndex == lanes.size) lanes.add(mutableListOf())
                lanes[laneIndex] += event
                event to laneIndex
            }
            val laneCount = lanes.size.coerceAtLeast(1)
            assigned.forEach { (event, laneIndex) ->
                placements += TimelinePlacement(
                    event = event,
                    visibleStart = maxOf(event.start, dayStart),
                    visibleEnd = minOf(event.end, dayEnd),
                    laneIndex = laneIndex,
                    laneCount = laneCount,
                    conflictIds = conflictIdsByEvent.getValue(event.id).toSet(),
                    isShadow = event.type == "task" && event.completed,
                )
            }
        }

        return placements.sortedWith(
            compareBy<TimelinePlacement>(
                { it.visibleStart },
                { if (it.isShadow) 0 else 1 },
                { it.laneIndex },
                { it.event.id },
            )
        )
    }

    private fun splitIntoOverlapClusters(events: List<TaskEvent>): List<List<TaskEvent>> {
        if (events.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<TaskEvent>>()
        var current = mutableListOf<TaskEvent>()
        var clusterEnd = Instant.MIN

        events.forEach { event ->
            if (current.isNotEmpty() && !event.start.isBefore(clusterEnd)) {
                clusters += current
                current = mutableListOf()
                clusterEnd = Instant.MIN
            }
            current += event
            if (event.end.isAfter(clusterEnd)) clusterEnd = event.end
        }
        if (current.isNotEmpty()) clusters += current
        return clusters
    }

    private fun isConflictPair(left: TaskEvent, right: TaskEvent): Boolean {
        if (left.type == "task" && left.completed) return false
        if (right.type == "task" && right.completed) return false
        if (left.type !in CONFLICT_TYPES || right.type !in CONFLICT_TYPES) return false
        if (left.type != right.type) return false
        return overlaps(left.start, left.end, right.start, right.end)
    }

    private fun overlaps(
        leftStart: Instant,
        leftEnd: Instant,
        rightStart: Instant,
        rightEnd: Instant,
    ): Boolean = leftStart.isBefore(rightEnd) && rightStart.isBefore(leftEnd)

    private val CONFLICT_TYPES = setOf("event", "task")
}
