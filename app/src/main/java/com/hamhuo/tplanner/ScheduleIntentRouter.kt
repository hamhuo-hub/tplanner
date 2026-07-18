package com.hamhuo.tplanner

/** Conservative local guard for explicit schedule commands. */
internal object ScheduleIntentRouter {
    private val patterns = listOf(
        Regex("(提醒我|请提醒|帮我提醒|记得提醒|别忘了提醒|设(?:置)?(?:一个|个)?(?:闹钟|提醒))"),
        Regex("(添加|加入|新建|创建|建|加|安排|排进|写进|放进).{0,10}(日程|行程|待办|任务|提醒)"),
        Regex("(日程|行程|待办|任务|提醒).{0,10}(添加|加入|新建|创建|安排|记录|加上)"),
        Regex("(?:帮我|请|给我)?安排.{0,16}(明天|后天|下周|周[一二三四五六日天]|星期[一二三四五六日天]|\\d{1,2}[点时:])"),
        Regex("(明天|后天|大后天|下周(?:[一二三四五六日天])?|周[一二三四五六日天]|星期[一二三四五六日天]|\\d{1,2}\\s*[月/-]\\s*\\d{1,2}\\s*[日号]?).{0,12}(要|得|需要|记得|别忘|去|做|开|交|完成|参加|办理)"),
        Regex("\\b(remind me|add (?:an? )?(?:event|task|reminder)|create (?:an? )?(?:event|task|reminder)|schedule (?:an? )?(?:event|task|reminder)|put .{1,24} (?:on|in) (?:my )?calendar)\\b"),
    )

    fun isExplicitRequest(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.isNotEmpty() && patterns.any { it.containsMatchIn(normalized) }
    }

    fun hasExplicitClock(text: String): Boolean {
        val normalized = text.lowercase()
        return listOf(
            Regex("(?:[01]?\\d|2[0-3])[:：][0-5]\\d"),
            Regex("(?:凌晨|早上|上午|中午|下午|傍晚|晚上|夜里)?\\s*\\d{1,2}\\s*点(?:半|\\d{1,2}\\s*分?)?"),
            Regex("(今早|今晚|明早|明晚|凌晨|早上|上午|中午|下午|傍晚|晚上|现在|立刻|马上)"),
            Regex("\\b(?:[01]?\\d|2[0-3])(?::[0-5]\\d)?\\s*(?:am|pm)\\b"),
            Regex("\\b(now|right now|morning|noon|afternoon|evening|tonight)\\b"),
        ).any { it.containsMatchIn(normalized) }
    }

    fun explicitType(text: String): String? {
        val normalized = text.lowercase()
        Regex("(?:类型|type)\\s*[:：是为]?\\s*(提醒|状态|任务|event|reminder|status|task|todo)")
            .findAll(normalized)
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?.let { value ->
                return when (value) {
                    "提醒", "event", "reminder" -> "event"
                    "状态", "status" -> "status"
                    else -> "task"
                }
            }
        val candidates = listOf(
            "event" to listOf("提醒", "闹钟", "event", "reminder"),
            "status" to listOf("状态", "status"),
            "task" to listOf("任务", "待办", "task", "todo"),
        ).mapNotNull { (type, words) ->
            words.maxOfOrNull { normalized.lastIndexOf(it) }
                ?.takeIf { it >= 0 }
                ?.let { index -> type to index }
        }
        return candidates.maxByOrNull { it.second }?.first
    }
}
