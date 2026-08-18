package com.hamhuo.tplanner

/**
 * 事件清单抽象。Inbox 是母类——所有未删除事件的集合；
 * Today 是按日期过滤的子视图。Custom 是用户新建的清单。
 */
sealed class EventList(val key: String, open val label: String) {
    data object Inbox : EventList("inbox", "")
    data object Today : EventList("today", "")

    /** key 由后端决定；label 是清单标题文本。 */
    data class Custom(val id: String, override val label: String) : EventList(id, label)

    /** Only a custom list is persisted on an item; Inbox and Today remain computed views. */
    fun assignmentId(): String = (this as? Custom)?.id.orEmpty()

    companion object {
        /** get() 延迟求值——避免伴生对象立即初始化时 Inbox 尚未完成构造导致 null。 */
        val BUILT_IN: List<EventList>
            get() = listOf(Inbox, Today)

        fun fromKey(key: String, label: String = ""): EventList =
            when (key) {
                "inbox" -> Inbox
                "today" -> Today
                else -> Custom(key, label.ifBlank { key })
            }

        fun fromKey(key: String, lists: List<com.hamhuo.tplanner.UserList>): EventList =
            when (key) {
                "inbox" -> Inbox
                "today" -> Today
                else -> {
                    val match = lists.firstOrNull { it.id == key }
                    if (match != null) Custom(match.id, match.name)
                    else Inbox
                }
            }
    }
}
