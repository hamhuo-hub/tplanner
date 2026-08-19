package com.hamhuo.tplanner

/** Stable diagnostic codes for watch-initiated phone synchronization. Never shown in the UI. */
enum class WatchSyncErrorCode(
    val id: String,
    val description: String,
) {
    PHONE_UNREACHABLE("ERROR001", "No connected phone endpoint is reachable"),
    RESPONSE_TIMEOUT("ERROR002", "The phone did not acknowledge before the timeout"),
    INVALID_RESPONSE("ERROR003", "The phone response was invalid or could not be stored"),
    BLUETOOTH_UNAVAILABLE("ERROR004", "Bluetooth permission, adapter, or paired phone is unavailable"),
    TRANSPORT_FAILURE("ERROR005", "Data Layer or Bluetooth transport/protocol failed"),
}
