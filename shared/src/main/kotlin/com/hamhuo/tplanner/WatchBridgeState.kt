package com.hamhuo.tplanner

/** Pure state transitions shared by production bridge code and local JVM contract tests. */
object WatchBridgeIdentityGate {
    data class Identity(
        val requestId: String,
        val identityHash: String,
        val commandIds: List<String>,
    )

    enum class Decision { ACCEPT_NEW, DUPLICATE, CONFLICT }

    fun decide(existing: Collection<Identity>, incoming: Identity): Decision {
        existing.firstOrNull { it.requestId == incoming.requestId }?.let { sameRequest ->
            return if (sameRequest.identityHash == incoming.identityHash &&
                sameRequest.commandIds == incoming.commandIds
            ) Decision.DUPLICATE else Decision.CONFLICT
        }
        val ownedCommandIds = existing.flatMap(Identity::commandIds).toSet()
        return if (incoming.commandIds.any(ownedCommandIds::contains)) {
            Decision.CONFLICT
        } else {
            Decision.ACCEPT_NEW
        }
    }
}

object WatchOutboxCompletion {
    enum class Decision { KEEP_PENDING, COMPLETE, MOVE_TO_FAILED }

    fun decide(
        request: WatchTaskProtocol.Request,
        response: WatchTaskProtocol.Response,
        installedProjectionSnapshotVersion: Long,
    ): Decision {
        val expected = request.commands.map(WatchTaskProtocol.SemanticCommand::commandId).toSet()
        if (response.requestId != request.requestId ||
            (response.commandIds.isNotEmpty() && response.commandIds.toSet() != expected)
        ) return Decision.KEEP_PENDING
        return when (response.status) {
            WatchTaskProtocol.Status.PHONE_STORED,
            WatchTaskProtocol.Status.RETRY,
            -> Decision.KEEP_PENDING
            WatchTaskProtocol.Status.REJECTED -> Decision.MOVE_TO_FAILED
            WatchTaskProtocol.Status.SNAPSHOT_PUBLISHED -> if (
                response.snapshotVersion != null &&
                response.snapshotVersion <= installedProjectionSnapshotVersion
            ) Decision.COMPLETE else Decision.KEEP_PENDING
        }
    }
}
