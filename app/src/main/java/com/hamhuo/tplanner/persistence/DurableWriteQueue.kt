package com.hamhuo.tplanner.persistence

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Application-process writer for small recovery records.
 *
 * Compose scopes are cancelled during Activity recreation, which must not cancel a keystroke that
 * the editor already accepted. Writes for one key are serialized, while different drafts may
 * proceed independently. Critical operations join the same tail via [submitAndAwait], so a newer
 * keystroke can never be overtaken by an older commit snapshot.
 */
internal object DurableWriteQueue {
    private const val TAG = "TplannerDraftWriter"
    private const val STOP_FLUSH_TIMEOUT_MS = 1_000L

    private data class AutosaveSlot(val key: String, val segment: Long)

    private val lock = Any()
    private val tails = mutableMapOf<String, Deferred<*>>()
    private val autosaveSegments = mutableMapOf<String, Long>()
    private val latestAutosaveVersion = mutableMapOf<AutosaveSlot, Long>()
    private val failures = mutableMapOf<String, Throwable>()
    private var nextVersion = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun submit(key: String, write: suspend () -> Unit) {
        require(key.isNotBlank())
        synchronized(lock) {
            ++nextVersion
            val slot = AutosaveSlot(key, autosaveSegments[key] ?: 0L)
            val version = nextVersion.also { latestAutosaveVersion[slot] = it }
            // Version registration and tail installation must be one critical section. Otherwise
            // the previous IO completion can erase the new version before its Deferred exists.
            enqueue(
                key = key,
                autosaveSlot = slot,
                autosaveVersion = version,
                clearFailureOnSuccess = { true },
                recordFailure = true,
                operation = write,
            )
        }
    }

    /**
     * Enqueues a complete replacement/commit/delete as part of the chain. A successful operation
     * supersedes any earlier failed autosave for the same key.
     */
    suspend fun <T> submitAndAwait(
        key: String,
        clearsPreviousFailure: (T) -> Boolean = { true },
        operation: suspend () -> T,
    ): T =
        enqueue(
            key = key,
            startsNewAutosaveSegment = true,
            clearFailureOnSuccess = clearsPreviousFailure,
            operation = operation,
        ).await()

    /** Reads or initializes only after every accepted autosave is durable. */
    suspend fun <T> readAfterPending(key: String, operation: suspend () -> T): T =
        enqueue(
            key = key,
            startsNewAutosaveSegment = true,
            failOnPreviousFailure = true,
            operation = operation,
        ).await()

    private fun <T> enqueue(
        key: String,
        autosaveSlot: AutosaveSlot? = null,
        autosaveVersion: Long? = null,
        startsNewAutosaveSegment: Boolean = false,
        clearFailureOnSuccess: ((T) -> Boolean)? = null,
        failOnPreviousFailure: Boolean = false,
        recordFailure: Boolean = false,
        operation: suspend () -> T,
    ): Deferred<T> {
        require(key.isNotBlank())
        synchronized(lock) {
            if (startsNewAutosaveSegment) {
                autosaveSegments[key] = (autosaveSegments[key] ?: 0L) + 1L
            }
            val previous = tails[key]
            lateinit var next: Deferred<T>
            next = scope.async(start = CoroutineStart.LAZY) {
                previous?.join()
                if (autosaveSlot != null && autosaveVersion != null) {
                    val isLatest = synchronized(lock) {
                        latestAutosaveVersion[autosaveSlot] == autosaveVersion
                    }
                    if (!isLatest) {
                        @Suppress("UNCHECKED_CAST")
                        return@async Unit as T
                    }
                }
                if (failOnPreviousFailure) {
                    synchronized(lock) { failures[key] }?.let { throw it }
                }
                try {
                    operation().also { result ->
                        if (clearFailureOnSuccess?.invoke(result) == true) {
                            synchronized(lock) { failures.remove(key) }
                        }
                    }
                } catch (error: Throwable) {
                    if (recordFailure && error !is CancellationException) {
                        synchronized(lock) { failures[key] = error }
                    }
                    throw error
                }
            }
            tails[key] = next
            next.invokeOnCompletion { error ->
                if (error != null) Log.e(TAG, "Durable operation failed for key=$key", error)
                synchronized(lock) {
                    if (autosaveSlot != null &&
                        latestAutosaveVersion[autosaveSlot] == autosaveVersion
                    ) {
                        latestAutosaveVersion.remove(autosaveSlot)
                    }
                    if (tails[key] === next) {
                        tails.remove(key)
                        autosaveSegments.remove(key)
                    }
                }
            }
            next.start()
            return next
        }
    }

    suspend fun flush(key: String) {
        while (true) {
            val pending = synchronized(lock) { tails[key] }
            if (pending == null) {
                synchronized(lock) { failures[key] }?.let { throw it }
                return
            }
            pending.await()
            if (synchronized(lock) { tails[key] } === pending) {
                synchronized(lock) { failures[key] }?.let { throw it }
                return
            }
        }
    }

    suspend fun flushPrefix(prefix: String) {
        require(prefix.isNotBlank())
        while (true) {
            val keys = synchronized(lock) {
                (tails.keys + failures.keys).filter { it.startsWith(prefix) }.toSet()
            }
            if (keys.isEmpty()) return
            keys.forEach { flush(it) }
        }
    }

    private suspend fun flushAll() {
        while (true) {
            val keys = synchronized(lock) { (tails.keys + failures.keys).toSet() }
            if (keys.isEmpty()) return
            keys.forEach { flush(it) }
        }
    }

    /** Bounded lifecycle barrier; Room draft writes are tiny and normally finish in milliseconds. */
    fun flushAllOnStop(): Boolean = runBlocking(Dispatchers.IO) {
        runCatching {
            withTimeoutOrNull(STOP_FLUSH_TIMEOUT_MS) {
                flushAll()
                true
            } ?: false
        }.onFailure { error ->
            Log.e(TAG, "onStop recovery barrier failed", error)
        }.getOrDefault(false)
    }
}
