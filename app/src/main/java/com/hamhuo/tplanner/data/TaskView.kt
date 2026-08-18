package com.hamhuo.tplanner

import java.time.LocalDate

/**
 * A selectable task view.
 *
 * [Inbox] and [Today] are read-only filters over the same task dataset. Only [CustomList]
 * represents persisted list membership on a [ScheduleItem].
 */
sealed class TaskView(val key: String) {
    sealed class Filter(key: String) : TaskView(key)

    data object Inbox : Filter("inbox")
    data object Today : Filter("today")
    data class CustomList(val id: String, val name: String) : TaskView(id)

    /** A new item inherits membership only when it is created from a real custom list. */
    fun listIdForNewItem(): String = (this as? CustomList)?.id.orEmpty()

    /** Applies this view without allowing items from another custom list to leak into the result. */
    fun filter(items: List<ScheduleItem>, date: LocalDate = appToday()): List<ScheduleItem> =
        when (this) {
            Inbox -> items.filter { it.deletedAt == 0L }
            Today -> items.forDate(date)
            is CustomList -> items.filter { it.deletedAt == 0L && it.listId == id }
        }

    companion object {
        val FILTERS: List<Filter>
            get() = listOf(Inbox, Today)

        fun fromKey(key: String, lists: List<UserList>): TaskView =
            when (key) {
                Inbox.key -> Inbox
                Today.key -> Today
                else -> lists.firstOrNull { it.id == key }
                    ?.let { CustomList(it.id, it.name) }
                    ?: Inbox
            }
    }
}
