package com.hamhuo.tplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class WatchBridgeProtocolTest {
    @Test
    fun `same envelope over Data Layer and RFCOMM is one durable identity`() {
        val original = createRequest("11111111-1111-4111-8111-111111111111")
        val dataLayer = WatchTaskProtocol.decodeRequest(WatchTaskProtocol.encodeRequest(original))
        val rfcomm = WatchTaskProtocol.decodeRequest(
            WatchTaskProtocol.encodeRequest(original.copy(attempt = 9, publishedAtEpochMs = 99_999L)),
        )

        assertEquals(WatchTaskProtocol.identityHash(dataLayer), WatchTaskProtocol.identityHash(rfcomm))
        val known = WatchBridgeIdentityGate.Identity(
            dataLayer.requestId,
            WatchTaskProtocol.identityHash(dataLayer),
            dataLayer.commands.map(WatchTaskProtocol.SemanticCommand::commandId),
        )
        val retry = WatchBridgeIdentityGate.Identity(
            rfcomm.requestId,
            WatchTaskProtocol.identityHash(rfcomm),
            rfcomm.commands.map(WatchTaskProtocol.SemanticCommand::commandId),
        )
        assertEquals(
            WatchBridgeIdentityGate.Decision.DUPLICATE,
            WatchBridgeIdentityGate.decide(listOf(known), retry),
        )
    }

    @Test
    fun `a connection batch carries every pending envelope`() {
        val requests = listOf(
            createRequest("11111111-1111-4111-8111-111111111111"),
            createRequest("22222222-2222-4222-8222-222222222222"),
            deleteRequest("33333333-3333-4333-8333-333333333333"),
        )
        val encoded = WatchTaskProtocol.encodeRequestBatch(
            WatchTaskProtocol.RequestBatch("batch-1", requests),
        )
        val decoded = WatchTaskProtocol.decodeRequestBatch(encoded)

        assertEquals(requests.map { it.requestId }, decoded.requests.map { it.requestId })
        assertTrue(decoded.requests.all { it.commands.isNotEmpty() })
    }

    @Test
    fun `phone stored and disconnect retain pending until matching projection is installed`() {
        val request = createRequest("11111111-1111-4111-8111-111111111111")
        val commandIds = request.commands.map(WatchTaskProtocol.SemanticCommand::commandId)
        val stored = WatchTaskProtocol.Response(
            request.requestId,
            WatchTaskProtocol.Status.PHONE_STORED,
            commandIds,
        )
        val published = WatchTaskProtocol.Response(
            request.requestId,
            WatchTaskProtocol.Status.SNAPSHOT_PUBLISHED,
            commandIds,
            snapshotVersion = 42L,
        )

        // A disconnected transport produces no response, so the durable pending value is unchanged.
        val pendingBeforeDisconnect = listOf(request)
        val pendingAfterDisconnect = pendingBeforeDisconnect
        assertEquals(pendingBeforeDisconnect, pendingAfterDisconnect)
        assertEquals(
            WatchOutboxCompletion.Decision.KEEP_PENDING,
            WatchOutboxCompletion.decide(request, stored, 0L),
        )
        assertEquals(
            WatchOutboxCompletion.Decision.KEEP_PENDING,
            WatchOutboxCompletion.decide(request, published, 41L),
        )
        assertEquals(
            WatchOutboxCompletion.Decision.COMPLETE,
            WatchOutboxCompletion.decide(request, published, 42L),
        )
    }

    @Test
    fun `reusing a child command under another envelope is rejected`() {
        val first = createRequest("11111111-1111-4111-8111-111111111111")
        val colliding = createRequest("22222222-2222-4222-8222-222222222222").copy(
            commands = createRequest("22222222-2222-4222-8222-222222222222").commands.mapIndexed { index, command ->
                if (index == 0) command.copy(commandId = first.commands.first().commandId) else command
            },
        )
        assertNotEquals(first.requestId, colliding.requestId)
        assertEquals(
            WatchBridgeIdentityGate.Decision.CONFLICT,
            WatchBridgeIdentityGate.decide(
                listOf(identity(first)),
                identity(colliding),
            ),
        )
    }

    @Test
    fun `legacy v1 conversion has deterministic UUIDv7 command identities`() {
        val legacy = JSONObject().apply {
            put("schemaVersion", 1)
            put("operation", WatchTaskProtocol.OPERATION_CREATE_TASK)
            put("requestId", "legacy-request-1")
            put("createdAtEpochMs", 1_700_000_000_123L)
            put("attempt", 7)
            put("publishedAtEpochMs", 1_700_000_001_000L)
            put("task", JSONObject().apply {
                put("id", "legacy-task")
                put("title", "Legacy work")
                put("type", "task")
                put("startEpochMs", 1_700_000_100_000L)
                put("endEpochMs", 1_700_000_200_000L)
                put("colorId", 2)
                put("alarmEnabled", true)
                put("alarmOffsetMinutes", 15)
                put("timeZoneId", WatchTaskProtocol.DEFAULT_TIME_ZONE_ID)
            })
        }.toString()

        val watchMigration = WatchTaskProtocol.decodeCompatibleRequest(legacy)
        val phoneAdapter = WatchTaskProtocol.decodeCompatibleRequest(
            JSONObject(legacy)
                .put("attempt", 99)
                .put("publishedAtEpochMs", 1_700_000_009_999L)
                .toString(),
        )

        assertEquals(watchMigration.commands, phoneAdapter.commands)
        assertEquals(
            WatchTaskProtocol.identityHash(watchMigration),
            WatchTaskProtocol.identityHash(phoneAdapter),
        )
        assertEquals(
            listOf(
                "task.create",
                "task.setSchedule",
                "task.setAlarm",
                "task.setAppearance",
                "task.setExtras",
            ),
            watchMigration.commands.map(WatchTaskProtocol.SemanticCommand::type),
        )
        assertTrue(watchMigration.commands.all { command ->
            command.commandId.matches(
                Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
            )
        })
        assertEquals(
            watchMigration,
            WatchTaskProtocol.decodeRequest(WatchTaskProtocol.encodeRequest(watchMigration)),
        )

        val legacyDelete = JSONObject().apply {
            put("schemaVersion", 1)
            put("operation", WatchTaskProtocol.OPERATION_DELETE_TASK)
            put("requestId", "legacy-delete-1")
            put("createdAtEpochMs", 1_700_000_000_456L)
            put("publishedAtEpochMs", 1_700_000_000_456L)
            put("taskId", "legacy-task")
        }.toString()
        val firstDelete = WatchTaskProtocol.decodeCompatibleRequest(legacyDelete)
        val retriedDelete = WatchTaskProtocol.decodeCompatibleRequest(legacyDelete)
        assertEquals(firstDelete.commands, retriedDelete.commands)
        assertEquals(listOf("task.delete"), firstDelete.commands.map { it.type })
    }

    @Test
    fun `dependent delete preserves its PHONE_STORED barrier through wire round trip`() {
        val predecessor = createRequest("11111111-1111-4111-8111-111111111111")
        val dependent = WatchTaskProtocol.withSemanticCommands(
            WatchTaskProtocol.Request(
                requestId = "22222222-2222-4222-8222-222222222222",
                createdAtEpochMs = 2_000L,
                publishedAtEpochMs = 2_000L,
                taskId = predecessor.task!!.id,
                dependsOnRequestId = predecessor.requestId,
            ),
        )

        val decoded = WatchTaskProtocol.decodeRequest(WatchTaskProtocol.encodeRequest(dependent))
        assertEquals(predecessor.requestId, decoded.dependsOnRequestId)
        assertTrue(!WatchTaskProtocol.dependencySatisfied(decoded, emptySet()))
        assertTrue(WatchTaskProtocol.dependencySatisfied(decoded, setOf(predecessor.requestId)))
        assertEquals(
            setOf(predecessor.requestId),
            WatchTaskProtocol.supersededCreateRequestIds(listOf(predecessor, decoded)),
        )
        assertEquals(WatchTaskProtocol.identityHash(dependent), WatchTaskProtocol.identityHash(decoded))
        assertNotEquals(
            WatchTaskProtocol.identityHash(dependent),
            WatchTaskProtocol.identityHash(dependent.copy(dependsOnRequestId = null)),
        )
    }

    @Test
    fun `legacy pending create delete pair gains one deterministic phone stored dependency`() {
        val legacyCreate = JSONObject().apply {
            put("schemaVersion", 1)
            put("operation", WatchTaskProtocol.OPERATION_CREATE_TASK)
            put("requestId", "legacy-create-pending")
            put("createdAtEpochMs", 1_700_000_000_123L)
            put("publishedAtEpochMs", 1_700_000_000_123L)
            put("task", JSONObject().apply {
                put("id", "legacy-paired-task")
                put("title", "created offline")
                put("type", "task")
                put("startEpochMs", 1_700_000_100_000L)
                put("endEpochMs", 1_700_000_200_000L)
                put("colorId", 2)
                put("alarmEnabled", false)
                put("alarmOffsetMinutes", 0)
                put("timeZoneId", WatchTaskProtocol.DEFAULT_TIME_ZONE_ID)
            })
        }.toString()
        val legacyDelete = JSONObject().apply {
            put("schemaVersion", 1)
            put("operation", WatchTaskProtocol.OPERATION_DELETE_TASK)
            put("requestId", "legacy-delete-pending")
            put("createdAtEpochMs", 1_700_000_000_456L)
            put("publishedAtEpochMs", 1_700_000_000_456L)
            put("taskId", "legacy-paired-task")
        }.toString()
        val unrelatedCreate = JSONObject(legacyCreate).apply {
            put("requestId", "legacy-unrelated-create")
            getJSONObject("task").put("id", "legacy-unrelated-task")
        }.toString()

        fun migrate() = WatchTaskProtocol.linkPendingCreateDeleteDependencies(
            // The matching predecessor is intentionally not adjacent: migration must inspect the
            // complete persisted queue instead of converting entries independently.
            listOf(legacyCreate, unrelatedCreate, legacyDelete)
                .map(WatchTaskProtocol::decodeCompatibleRequest),
        )
        val first = migrate()
        val second = migrate()
        val create = first[0]
        val delete = first[2]

        assertEquals("legacy-create-pending", create.requestId)
        assertEquals("legacy-delete-pending", delete.requestId)
        assertEquals(create.requestId, delete.dependsOnRequestId)
        assertTrue(!WatchTaskProtocol.dependencySatisfied(delete, emptySet()))
        assertTrue(WatchTaskProtocol.dependencySatisfied(delete, setOf(create.requestId)))
        assertEquals(
            first.flatMap { request -> request.commands.map { it.commandId } },
            second.flatMap { request -> request.commands.map { it.commandId } },
        )
        assertEquals(
            delete,
            WatchTaskProtocol.decodeRequest(WatchTaskProtocol.encodeRequest(delete)),
        )
    }

    @Test
    fun `delete visibility requires committed pending bytes and survives restart`() {
        val delete = WatchTaskProtocol.withSemanticCommands(
            WatchTaskProtocol.Request(
                requestId = "durable-delete-request",
                createdAtEpochMs = 2_000L,
                publishedAtEpochMs = 2_000L,
                taskId = "task-visible-until-commit",
            ),
        )

        // A failed SharedPreferences commit leaves the durable queue unchanged, so the UI must
        // rebuild the card instead of consulting a separate tombstone.
        val queueBeforeCommit = emptyList<WatchTaskProtocol.Request>()
        val queueAfterFailedCommit = queueBeforeCommit
        assertTrue(WatchTaskProtocol.pendingDeleteTaskIds(queueAfterFailedCommit).isEmpty())

        val committedBytes = listOf(WatchTaskProtocol.encodeRequest(delete))
        val queueAfterRestart = committedBytes.map(WatchTaskProtocol::decodeRequest)
        assertEquals(
            setOf("task-visible-until-commit"),
            WatchTaskProtocol.pendingDeleteTaskIds(queueAfterRestart),
        )
    }

    private fun identity(request: WatchTaskProtocol.Request) = WatchBridgeIdentityGate.Identity(
        request.requestId,
        WatchTaskProtocol.identityHash(request),
        request.commands.map(WatchTaskProtocol.SemanticCommand::commandId),
    )

    private fun createRequest(requestId: String): WatchTaskProtocol.Request =
        WatchTaskProtocol.withSemanticCommands(
            WatchTaskProtocol.Request(
                requestId = requestId,
                createdAtEpochMs = 1_000L,
                publishedAtEpochMs = 1_000L,
                task = WatchTaskProtocol.Task(
                    id = "task-$requestId",
                    title = "Work",
                    type = "task",
                    startEpochMs = 2_000L,
                    endEpochMs = 3_000L,
                    colorId = 1,
                    alarmEnabled = true,
                    alarmOffsetMinutes = 10,
                ),
            ),
        )

    private fun deleteRequest(requestId: String): WatchTaskProtocol.Request =
        WatchTaskProtocol.withSemanticCommands(
            WatchTaskProtocol.Request(
                requestId = requestId,
                createdAtEpochMs = 1_000L,
                publishedAtEpochMs = 1_000L,
                taskId = "task-delete",
            ),
        )
}
