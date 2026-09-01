package com.hamhuo.tplanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Why a real synchronization transaction was requested. */
enum class SyncReason {
    STARTUP,
    USER_GESTURE,
    REMOTE_CHANGE,
}

/**
 * Transaction phases shared by every phone screen.
 *
 * These values describe durable/business progress. They are deliberately independent from any
 * pull-to-refresh or animation state owned by Compose.
 */
enum class SyncPhase(val wireName: String) {
    IDLE("idle"),
    SAVED("saved"),
    UPLOADING("uploading"),
    UPDATING("updating"),
    SUCCESS("success"),
    ERROR("error"),
    ;

    val isRunning: Boolean
        get() = this == SAVED || this == UPLOADING || this == UPDATING
}

data class SyncOperationState(
    val operationId: String? = null,
    val reason: SyncReason? = null,
    val phase: SyncPhase = SyncPhase.IDLE,
    val errorCode: String? = null,
    val detail: String? = null,
)

/** Testable single-flight transaction core; production owns one process-scoped instance below. */
internal class SyncTransactionCoordinator(
    private val scope: CoroutineScope,
) {
    private val startupRequested = AtomicBoolean(false)
    private val lock = Any()
    private var activeJob: Job? = null
    private val completions = LinkedHashMap<String, CompletableDeferred<SyncOperationState>>()

    private val mutableState = MutableStateFlow(SyncOperationState())
    val state: StateFlow<SyncOperationState> = mutableState.asStateFlow()

    fun requestStartupSync(
        operation: suspend (report: (SyncPhase) -> Unit) -> Unit,
    ): String? {
        if (!startupRequested.compareAndSet(false, true)) return state.value.operationId
        return requestSync(SyncReason.STARTUP, operation)
    }

    fun requestSync(
        reason: SyncReason,
        operation: suspend (report: (SyncPhase) -> Unit) -> Unit,
    ): String = synchronized(lock) {
        activeJob?.takeIf { it.isActive }?.let {
            return@synchronized requireNotNull(mutableState.value.operationId)
        }

        val operationId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<SyncOperationState>()
        completions[operationId] = completion
        while (completions.size > MAX_COMPLETION_HISTORY) {
            val oldest = completions.entries.firstOrNull { it.value.isCompleted } ?: break
            completions.remove(oldest.key)
        }
        mutableState.value = SyncOperationState(
            operationId = operationId,
            reason = reason,
            phase = SyncPhase.SAVED,
        )
        activeJob = scope.launch {
            try {
                report(operationId, SyncPhase.UPLOADING)
                operation { phase -> report(operationId, phase) }
                report(operationId, SyncPhase.UPDATING)
                // Give observers a scheduling turn; this is not a fixed delay and does not hold
                // up network work, but keeps the Updating phase observable before Success.
                yield()
                report(operationId, SyncPhase.SUCCESS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = SyncOperationState(
                    operationId = operationId,
                    reason = reason,
                    phase = SyncPhase.ERROR,
                    errorCode = error.toSyncErrorCode(),
                    detail = error.message,
                )
            } finally {
                synchronized(lock) {
                    val terminal = mutableState.value.takeIf {
                        it.operationId == operationId && !it.phase.isRunning
                    } ?: SyncOperationState(
                        operationId = operationId,
                        reason = reason,
                        phase = SyncPhase.ERROR,
                        errorCode = "ERROR008",
                        detail = "Synchronization was cancelled before a terminal state",
                    )
                    completion.complete(terminal)
                    if (mutableState.value.operationId == operationId) activeJob = null
                }
            }
        }
        operationId
    }

    suspend fun awaitCompletion(operationId: String): SyncOperationState {
        val completion = synchronized(lock) {
            completions[operationId] ?: mutableState.value.takeIf { current ->
                current.operationId == operationId && !current.phase.isRunning
            }?.let { terminal -> CompletableDeferred(terminal) }
        } ?: error("Unknown synchronization operation: $operationId")
        return completion.await()
    }

    private companion object {
        const val MAX_COMPLETION_HISTORY = 128
    }

    private fun report(operationId: String, phase: SyncPhase) {
        if (phase == SyncPhase.IDLE || phase == SyncPhase.ERROR) return
        val current = mutableState.value
        if (current.operationId == operationId && current.phase.isRunning) {
            mutableState.value = current.copy(phase = phase, errorCode = null, detail = null)
        }
    }
}

/**
 * Process-scoped synchronization coordinator.
 *
 * A Composable may disappear, recompose, or move between tabs without owning or restarting the
 * transaction. Concurrent requests join the active operation and therefore cannot duplicate the
 * network work or manufacture a second spinner lifecycle.
 */
object SyncCoordinator {
    private val delegate = SyncTransactionCoordinator(
        CoroutineScope(SupervisorJob() + Dispatchers.Main),
    )

    val state: StateFlow<SyncOperationState> = delegate.state

    fun requestStartupSync(
        operation: suspend (report: (SyncPhase) -> Unit) -> Unit,
    ): String? = delegate.requestStartupSync(operation)

    fun requestSync(
        reason: SyncReason,
        operation: suspend (report: (SyncPhase) -> Unit) -> Unit,
    ): String = delegate.requestSync(reason, operation)

    suspend fun awaitCompletion(operationId: String): SyncOperationState =
        delegate.awaitCompletion(operationId)
}

private fun Throwable.toSyncErrorCode(): String {
    val explicit = Regex("ERROR\\d{3}").find(message.orEmpty())?.value
    if (explicit != null) return explicit
    return when (this) {
        is UnknownHostException,
        is NoRouteToHostException,
        -> "ERROR001"

        is ConnectException,
        is SocketTimeoutException,
        -> "ERROR002"

        else -> "ERROR008"
    }
}
