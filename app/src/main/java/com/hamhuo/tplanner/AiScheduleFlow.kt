package com.hamhuo.tplanner

import android.Manifest
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

private const val LLM_LOG_TAG = "TplannerLLM"

enum class PhoneLocationState { IDLE, LOCATING, READY, UNAVAILABLE }

data class AiScheduleState(
    var showScheduleSheet: Boolean = false,
    var openingScheduleSheet: Boolean = false,
    var thinking: Boolean = false,
    var sheetAction: DeepSeekAnalysisService.ProposedAction? = null,
    var sheetRequestId: String = "",
    var untangleInput: String = "",
    var prefillLocation: String = "",
    var gpsLat: Double = 0.0,
    var gpsLng: Double = 0.0,
    var pendingLocationRequestId: String? = null,
    var locationPermissionGeneration: Int = 0,
    var locationPermissionInFlight: Boolean = false,
    var phoneLocationForeground: Boolean = false,
    var phoneLocationState: PhoneLocationState = PhoneLocationState.IDLE,
    var activeLocationHandle: LocationCapture.Handle? = null,
)

class AiScheduleFlow(
    private val scope: CoroutineScope,
    private val context: Context,
    private val eventStore: EventStore,
    private val eventWriteMutex: Mutex,
    private val deepseekService: DeepSeekAnalysisService?,
    private val amapApiKey: String,
    private val fetchEvents: suspend (String) -> List<TaskEvent>,
    private val getServerUrl: () -> String,
) {
    val state = AiScheduleState()

    fun startDirectAiExtraction() {
        if (deepseekService == null) {
            Toast.makeText(context, R.string.ai_service_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (state.showScheduleSheet || state.openingScheduleSheet) return
        state.openingScheduleSheet = true
        scope.launch {
            try {
                state.showScheduleSheet = true
                state.thinking = false
                state.sheetAction = null
                val openedRequestId = "direct-${UUID.randomUUID()}"
                state.sheetRequestId = openedRequestId
                state.untangleInput = ""
                state.prefillLocation = ""
                state.gpsLat = 0.0; state.gpsLng = 0.0
                state.phoneLocationState = PhoneLocationState.LOCATING

                if (!state.showScheduleSheet ||
                    state.sheetRequestId != openedRequestId ||
                    !state.phoneLocationForeground
                ) return@launch

                Log.i(LLM_LOG_TAG,
                    "phase=sheet_open source=direct requestId=$openedRequestId " +
                        "locationApiConfigured=${amapApiKey.isNotBlank()}")
                state.pendingLocationRequestId = openedRequestId
                if (!hasPhoneLocationPermission(context)) {
                    state.locationPermissionInFlight = true
                    runCatching {
                        locationPermissionLauncher?.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            ),
                        )
                    }.onFailure { error ->
                        state.locationPermissionInFlight = false
                        state.pendingLocationRequestId = null
                        state.phoneLocationState = PhoneLocationState.UNAVAILABLE
                        Log.w(LLM_LOG_TAG, "Unable to request phone location permission", error)
                    }
                }
            } finally {
                state.openingScheduleSheet = false
            }
        }
    }

    var locationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null

    fun submitForExtraction(text: String) {
        if (state.thinking) return
        val requestId = "ui-${UUID.randomUUID()}"
        state.sheetRequestId = requestId
        state.untangleInput = text
        val stamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply {
            timeZone = appLegacyTimeZone()
        }.format(java.util.Date())
        val loc = state.prefillLocation.ifBlank { "" }

        Log.i(LLM_LOG_TAG,
            "request=$requestId phase=submit inputChars=${text.length} locationProvided=${loc.isNotBlank()}")
        state.thinking = true
        state.sheetAction = null
        scope.launch {
            try {
                val action = deepseekService?.extractSchedule(text, stamp, loc, requestId)
                if (state.sheetRequestId != requestId || !state.showScheduleSheet) return@launch

                if (action != null) {
                    Log.i(LLM_LOG_TAG, "request=$requestId phase=route result=proposal type=${action.type}")
                    state.sheetAction = action
                    state.thinking = false
                } else {
                    Log.w(LLM_LOG_TAG, "request=$requestId phase=route result=unavailable")
                    if (state.sheetRequestId == requestId && state.showScheduleSheet) {
                        state.thinking = false
                        Toast.makeText(context, R.string.ai_service_unavailable, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (error: Exception) {
                Log.e(LLM_LOG_TAG, "request=$requestId phase=submit result=failed", error)
                if (state.showScheduleSheet && state.sheetRequestId == requestId) {
                    state.thinking = false
                    Toast.makeText(context, R.string.schedule_create_failed_toast, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun confirmAction(act: DeepSeekAnalysisService.ProposedAction) {
        val requestId = state.sheetRequestId.ifBlank { return }
        val start = parseAgentDatetime(act.startIso)
        val end = parseAgentDatetime(act.endIso)
        if (start == null || end == null || !end.isAfter(start)) {
            Toast.makeText(context, R.string.schedule_create_failed_toast, Toast.LENGTH_SHORT).show()
            state.sheetAction = null
            return
        }
        val ev = TaskEvent(
            id = stableUntangleId("event", requestId),
            title = act.title, type = act.type,
            start = start, end = end, completed = false,
            checklist = act.checklist.mapIndexed { index, item ->
                CheckItem(stableUntangleId("check:$index", requestId), item, false)
            },
            colorId = act.colorId, note = act.note,
            deletedAt = 0L, updatedAt = System.currentTimeMillis(),
            alarmEnabled = act.alarmEnabled, alarmOffsetMinutes = act.alarmOffsetMinutes,
            lat = state.gpsLat, lng = state.gpsLng,
        )
        if (state.thinking) return
        state.thinking = true
        scope.launch {
            try {
                eventWriteMutex.withLock { eventStore.save(ev) }
                if (state.showScheduleSheet && state.sheetRequestId == requestId) {
                    state.showScheduleSheet = false
                    state.thinking = false
                    state.sheetAction = null
                    state.sheetRequestId = ""
                    state.untangleInput = ""
                    state.prefillLocation = ""
                    state.gpsLat = 0.0; state.gpsLng = 0.0
                    val alarmMsg = when {
                        !act.alarmEnabled -> context.getString(R.string.schedule_created_toast, act.title)
                        TaskAlarmScheduler.canScheduleExactAlarms(context) ->
                            context.getString(R.string.schedule_created_with_alarm_toast, act.title)
                        else -> context.getString(R.string.schedule_created_with_fallback_alarm_toast, act.title)
                    }
                    Toast.makeText(context, alarmMsg, Toast.LENGTH_SHORT).show()
                }
                fetchEvents(getServerUrl())
            } catch (e: Exception) {
                Log.e(LLM_LOG_TAG, "request=$requestId phase=confirm result=failed", e)
                if (state.showScheduleSheet && state.sheetRequestId == requestId) {
                    state.thinking = false
                    Toast.makeText(context, R.string.schedule_create_failed_toast, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun dismissSheet() {
        Log.d(LLM_LOG_TAG, "phase=sheet_close reason=dismissed")
        state.showScheduleSheet = false
        state.thinking = false
        state.sheetAction = null
        state.sheetRequestId = ""
        state.untangleInput = ""
    }
}

@Composable
fun rememberAiScheduleFlow(
    scope: CoroutineScope,
    context: Context,
    eventStore: EventStore,
    eventWriteMutex: Mutex,
    deepseekService: DeepSeekAnalysisService?,
    amapApiKey: String,
    fetchEvents: suspend (String) -> List<TaskEvent>,
    getServerUrl: () -> String,
): AiScheduleFlow {
    val flow = remember {
        AiScheduleFlow(scope, context, eventStore, eventWriteMutex, deepseekService, amapApiKey, fetchEvents, getServerUrl)
    }
    val state = flow.state
    val lifecycleOwner = LocalLifecycleOwner.current

    state.phoneLocationForeground =
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    flow.locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        state.locationPermissionInFlight = false
        val granted = hasPhoneLocationPermission(context)
        if (granted && state.pendingLocationRequestId != null) {
            state.locationPermissionGeneration++
        } else {
            state.pendingLocationRequestId = null
            if (state.phoneLocationState == PhoneLocationState.LOCATING) {
                state.phoneLocationState = PhoneLocationState.UNAVAILABLE
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> state.phoneLocationForeground = true
                Lifecycle.Event.ON_STOP -> {
                    state.phoneLocationForeground = false
                    state.activeLocationHandle?.let(LocationCapture::cancel)
                    state.activeLocationHandle = null
                    if (!state.locationPermissionInFlight &&
                        state.phoneLocationState == PhoneLocationState.LOCATING
                    ) {
                        state.phoneLocationState = PhoneLocationState.UNAVAILABLE
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.activeLocationHandle?.let(LocationCapture::cancel)
            state.activeLocationHandle = null
        }
    }

    LaunchedEffect(
        state.showScheduleSheet,
        state.pendingLocationRequestId,
        state.locationPermissionGeneration,
        state.phoneLocationForeground,
        state.thinking,
        state.sheetAction,
    ) {
        val targetRequestId = state.pendingLocationRequestId ?: return@LaunchedEffect
        if (!state.phoneLocationForeground) return@LaunchedEffect
        if (!state.showScheduleSheet || state.thinking || state.sheetAction != null) {
            state.pendingLocationRequestId = null
            if (!state.showScheduleSheet) state.phoneLocationState = PhoneLocationState.IDLE
            return@LaunchedEffect
        }
        if (!hasPhoneLocationPermission(context)) return@LaunchedEffect

        val fix = LocationCapture.capture(context)
        if (!state.showScheduleSheet || state.thinking || state.sheetAction != null) return@LaunchedEffect

        val resolvedLocation = fix?.let {
            AmapGeocoder.reverseGeocode(it.lat, it.lng, amapApiKey)
        }.orEmpty()
        if (!state.showScheduleSheet || state.thinking || state.sheetAction != null) return@LaunchedEffect

        if (fix == null) {
            state.phoneLocationState = PhoneLocationState.UNAVAILABLE
            state.pendingLocationRequestId = null
            return@LaunchedEffect
        }

        state.gpsLat = fix.lat
        state.gpsLng = fix.lng
        state.prefillLocation = resolvedLocation
        state.phoneLocationState = if (resolvedLocation.isBlank()) {
            PhoneLocationState.UNAVAILABLE
        } else {
            PhoneLocationState.READY
        }
        state.pendingLocationRequestId = null
    }

    return flow
}
