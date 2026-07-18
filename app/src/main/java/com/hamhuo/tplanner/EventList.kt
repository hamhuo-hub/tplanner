package com.hamhuo.tplanner

/**
 * 事件清单抽象。Inbox 是母类——所有未删除事件的集合；
 * Today 是按日期过滤的子视图。后续新增清单类型只需加一个 data object 即可。
 */
sealed class EventList(val key: String) {
    data object Inbox : EventList("inbox")
    data object Today : EventList("today")

    companion object {
        /** get() 延迟求值——避免伴生对象立即初始化时 Inbox 尚未完成构造导致 null。 */
        val ALL: List<EventList>
            get() = listOf(Inbox, Today)

        fun fromKey(key: String): EventList =
            ALL.firstOrNull { it.key == key } ?: Inbox
    }
}
