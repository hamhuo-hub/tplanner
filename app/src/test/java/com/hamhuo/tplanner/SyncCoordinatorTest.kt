package com.hamhuo.tplanner

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoordinatorTest {
    @Test
    fun tabObserversShareOneOperationWithoutStartingWork() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val coordinator = testCoordinator()

        val operationId = coordinator.requestSync(SyncReason.USER_GESTURE) {
            calls.incrementAndGet()
            gate.await()
        }

        // Notes, Inbox and Timeline are observers of one transaction. Reading/re-reading state is
        // intentionally side-effect free, as are recomposition and tab restoration in production.
        val observedByTabs = List(3) { coordinator.state.value.operationId }
        assertEquals(listOf(operationId, operationId, operationId), observedByTabs)
        assertEquals(1, calls.get())

        gate.complete(Unit)
        val terminal = coordinator.awaitCompletion(operationId)
        assertEquals(operationId, terminal.operationId)
        assertEquals(SyncPhase.SUCCESS, terminal.phase)
    }

    @Test
    fun overlappingRequestsJoinTheActiveTransaction() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val coordinator = testCoordinator()
        val ids = Collections.synchronizedList(mutableListOf<String>())

        val workers = List(8) {
            Thread {
                ids += coordinator.requestSync(SyncReason.USER_GESTURE) {
                    calls.incrementAndGet()
                    gate.await()
                }
            }.also { it.start() }
        }
        workers.forEach(Thread::join)

        assertEquals(8, ids.size)
        assertEquals(1, ids.distinct().size)
        assertEquals(1, calls.get())
        assertTrue(coordinator.state.value.phase.isRunning)

        gate.complete(Unit)
        assertEquals(SyncPhase.SUCCESS, coordinator.state.first { !it.phase.isRunning }.phase)
    }

    @Test
    fun completionSurvivesAImmediatelyFollowingOperation() = runBlocking {
        val coordinator = testCoordinator()
        val first = coordinator.requestSync(SyncReason.REMOTE_CHANGE) { }
        val firstTerminal = coordinator.awaitCompletion(first)
        val secondGate = CompletableDeferred<Unit>()
        val second = coordinator.requestSync(SyncReason.USER_GESTURE) { secondGate.await() }

        assertEquals(SyncPhase.SUCCESS, coordinator.awaitCompletion(first).phase)
        assertTrue(first != second)
        secondGate.complete(Unit)
        coordinator.awaitCompletion(second)
        assertEquals(first, firstTerminal.operationId)
    }

    private fun testCoordinator(): SyncTransactionCoordinator = SyncTransactionCoordinator(
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )
}
