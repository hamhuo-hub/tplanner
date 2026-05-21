package com.tplanner.wear.mobile

data class WearEvent(
    val id: String,
    val title: String,
    val type: String,
    val start: String,
    val end: String,
    val completed: Boolean = false,
    val deletedAt: Long = 0,
    val updatedAt: Long = 0,
    val checklist: List<CheckItem> = emptyList(),
    val colorId: Int = 0,
)

data class CheckItem(
    val id: String,
    val text: String,
    val completed: Boolean,
)

const val WEAR_PATH_EVENTS  = "/tplanner/events"
const val WEAR_PATH_JOURNAL = "/tplanner/journal"
const val MSG_TOGGLE_TASK   = "/tplanner/toggle_task"
const val MSG_TOGGLE_SUB    = "/tplanner/toggle_sub"
const val PREFS_NAME        = "tplanner_wear"
const val KEY_SERVER_IP     = "server_ip"
const val KEY_SERVER_PORT   = "server_port"
