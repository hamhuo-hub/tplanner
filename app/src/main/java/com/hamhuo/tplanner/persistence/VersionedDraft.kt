package com.hamhuo.tplanner.persistence

import java.security.MessageDigest

/**
 * Current on-disk contract for [VersionedDraft]. Persisted drafts with another version must not be
 * restored automatically; callers should retain them for an explicit recovery/migration flow.
 */
const val CURRENT_DRAFT_VERSION: Int = 1

/** A stable identity for a draft. The payload itself is intentionally format-agnostic. */
data class DraftTarget(
    val kind: DraftEntityKind,
    val entityId: String,
) {
    init {
        require(entityId.isNotBlank()) { "Draft entityId must not be blank" }
    }

    /** Suitable as a per-entity SharedPreferences key. */
    val storageKey: String
        get() = "${kind.storagePrefix}:$entityId"

    companion object {
        fun journal(dateKey: String): DraftTarget = DraftTarget(DraftEntityKind.JOURNAL, dateKey)

        fun event(eventId: String): DraftTarget = DraftTarget(DraftEntityKind.EVENT, eventId)
    }
}

enum class DraftEntityKind(internal val storagePrefix: String) {
    JOURNAL("journal"),
    EVENT("event"),
}

/**
 * The authoritative revision against which an edit session was started or is being recovered.
 *
 * [content] may be journal text, an event note, or a deterministic serialization of a complete
 * event edit snapshot. Keeping the draft model payload-agnostic lets journal and event editors use
 * exactly the same conflict rules.
 */
data class DraftRevision(
    val content: String,
    val updatedAt: Long = 0L,
    val entityExists: Boolean = true,
    val deletedAt: Long = 0L,
) {
    val isDeleted: Boolean
        get() = deletedAt != 0L

    val contentHash: String
        get() = baseHash(content)

    companion object {
        fun missing(): DraftRevision = DraftRevision(content = "", entityExists = false)
    }
}

/**
 * State of a persisted draft.
 *
 * EDITING means the payload has not yet been handed to the authoritative Store. COMMITTING is a
 * small two-phase marker: after a crash, [VersionedDraft.targetHash] tells recovery whether the
 * Store write landed, failed to land, or raced with another edit.
 */
enum class DraftState {
    EDITING,
    COMMITTING,
}

/**
 * Versioned, per-entity recovery record shared by journal and event editing.
 *
 * [baseHash] and [baseEntityExists] are immutable for one edit session. They must describe the
 * authoritative value observed when editing began, not a later sync result. [baseUpdatedAt] is
 * retained for diagnostics and conflict UI; field content equality is the restoration authority so
 * an unrelated event-field update does not invalidate an event-note draft.
 */
data class VersionedDraft(
    val target: DraftTarget,
    val content: String,
    val baseHash: String,
    val baseUpdatedAt: Long,
    val baseEntityExists: Boolean,
    /** Tombstone observed when this edit session began; zero means the base was alive/missing. */
    val baseDeletedAt: Long = 0L,
    /** Hash of the exact editor payload shown when the session began. */
    val initialContentHash: String = com.hamhuo.tplanner.persistence.baseHash(content),
    val draftUpdatedAt: Long,
    val state: DraftState = DraftState.EDITING,
    val targetHash: String? = null,
    val version: Int = CURRENT_DRAFT_VERSION,
) {
    val contentHash: String
        get() = baseHash(content)

    /** Update the payload without moving the edit-session base. */
    fun withContent(nextContent: String, changedAt: Long): VersionedDraft = copy(
        content = nextContent,
        draftUpdatedAt = changedAt,
        state = DraftState.EDITING,
        targetHash = null,
    )

    /** Mark the exact payload that is about to be written to the authoritative Store. */
    fun beginCommit(changedAt: Long = draftUpdatedAt): VersionedDraft = copy(
        draftUpdatedAt = changedAt,
        state = DraftState.COMMITTING,
        targetHash = contentHash,
    )

    /** Return a recovered payload to editable state while retaining its original base. */
    fun resumeEditing(changedAt: Long = draftUpdatedAt): VersionedDraft = copy(
        draftUpdatedAt = changedAt,
        state = DraftState.EDITING,
        targetHash = null,
    )

    companion object {
        fun start(
            target: DraftTarget,
            base: DraftRevision,
            initialContent: String = base.content,
            changedAt: Long,
        ): VersionedDraft {
            return VersionedDraft(
                target = target,
                content = initialContent,
                baseHash = base.contentHash,
                baseUpdatedAt = base.updatedAt,
                baseEntityExists = base.entityExists,
                baseDeletedAt = base.deletedAt,
                initialContentHash = baseHash(initialContent),
                draftUpdatedAt = changedAt,
            )
        }
    }
}

enum class DraftClearReason {
    /** The editor never diverged from its base, so no recovery payload is needed. */
    NO_CHANGES,

    /** The authoritative Store already contains the draft/commit target. */
    ALREADY_COMMITTED,
}

enum class DraftConflictReason {
    UNSUPPORTED_VERSION,
    INVALID_BASE_HASH,
    INVALID_INITIAL_CONTENT_HASH,
    INVALID_BASE_REVISION,
    INVALID_COMMIT_TARGET,
    ENTITY_MISSING,
    ENTITY_DELETED,
    ENTITY_CREATED_SINCE_EDIT_STARTED,
    CURRENT_CONTENT_DIVERGED,
}

/** Everything a conflict UI needs without having to reconstruct the decision. */
data class DraftConflict(
    val target: DraftTarget,
    val reason: DraftConflictReason,
    val draftContent: String,
    val currentContent: String?,
    val baseHash: String,
    val draftHash: String,
    val currentHash: String?,
    val baseUpdatedAt: Long,
    val baseDeletedAt: Long,
    val initialContentHash: String,
    val draftUpdatedAt: Long,
    val currentUpdatedAt: Long?,
    val currentDeletedAt: Long?,
)

sealed interface DraftRecoveryDecision {
    data object NoDraft : DraftRecoveryDecision

    /** It is safe to place [draft.content] in the editor, without writing it to the Store yet. */
    data class AutoRestore(val draft: VersionedDraft) : DraftRecoveryDecision

    /** The draft contains no recoverable work and its persisted record can be removed. */
    data class ClearDraft(
        val draft: VersionedDraft,
        val reason: DraftClearReason,
    ) : DraftRecoveryDecision

    /** Never overwrite the current Store value automatically for this result. */
    data class Conflict(val conflict: DraftConflict) : DraftRecoveryDecision
}

/**
 * Decide whether a persisted draft may be restored over [current].
 *
 * The function is deliberately side-effect free. In particular, [AutoRestore] only authorizes
 * populating an editor. The caller must repeat this check against a freshly-read Store revision
 * before committing, because a sync may finish while the editor is open.
 */
fun decideDraftRecovery(
    draft: VersionedDraft?,
    current: DraftRevision,
): DraftRecoveryDecision {
    if (draft == null) return DraftRecoveryDecision.NoDraft

    val draftHash = draft.contentHash
    val currentHash = current.contentHash.takeIf { current.entityExists }

    fun conflict(reason: DraftConflictReason): DraftRecoveryDecision.Conflict =
        DraftRecoveryDecision.Conflict(
            DraftConflict(
                target = draft.target,
                reason = reason,
                draftContent = draft.content,
                currentContent = current.content.takeIf { current.entityExists },
                baseHash = draft.baseHash,
                draftHash = draftHash,
                currentHash = currentHash,
                baseUpdatedAt = draft.baseUpdatedAt,
                baseDeletedAt = draft.baseDeletedAt,
                initialContentHash = draft.initialContentHash,
                draftUpdatedAt = draft.draftUpdatedAt,
                currentUpdatedAt = current.updatedAt.takeIf { current.entityExists },
                currentDeletedAt = current.deletedAt.takeIf { current.entityExists },
            )
        )

    if (draft.version != CURRENT_DRAFT_VERSION) {
        return conflict(DraftConflictReason.UNSUPPORTED_VERSION)
    }
    if (!draft.baseHash.isSha256()) {
        return conflict(DraftConflictReason.INVALID_BASE_HASH)
    }
    if (!draft.initialContentHash.isSha256()) {
        return conflict(DraftConflictReason.INVALID_INITIAL_CONTENT_HASH)
    }
    if (!draft.baseEntityExists && draft.baseDeletedAt != 0L) {
        return conflict(DraftConflictReason.INVALID_BASE_REVISION)
    }
    if (draft.state == DraftState.COMMITTING && draft.targetHash != draftHash) {
        return conflict(DraftConflictReason.INVALID_COMMIT_TARGET)
    }

    val beganFromTombstone = draft.baseEntityExists && draft.baseDeletedAt != 0L
    val hasNoUserChanges = if (beganFromTombstone) {
        draftHash == draft.initialContentHash
    } else {
        draftHash == draft.baseHash
    }

    if (current.isDeleted) {
        if (hasNoUserChanges) {
            return DraftRecoveryDecision.ClearDraft(draft, DraftClearReason.NO_CHANGES)
        }
        val sameTombstoneObservedAtStart = beganFromTombstone &&
            current.deletedAt == draft.baseDeletedAt &&
            current.updatedAt == draft.baseUpdatedAt &&
            currentHash == draft.baseHash
        return if (sameTombstoneObservedAtStart) {
            // The editor intentionally changed the blank view of an already-deleted date. It may
            // create a newer alive revision. A different tombstone still remains a conflict.
            DraftRecoveryDecision.AutoRestore(draft.resumeEditing())
        } else {
            conflict(DraftConflictReason.ENTITY_DELETED)
        }
    }

    // Equal content means the write already landed (or there was never a difference). Either way,
    // retaining/restoring the recovery record would only resurrect stale state later.
    if (current.entityExists && currentHash == draftHash) {
        val reason = if (hasNoUserChanges) {
            DraftClearReason.NO_CHANGES
        } else {
            DraftClearReason.ALREADY_COMMITTED
        }
        return DraftRecoveryDecision.ClearDraft(draft, reason)
    }

    // A draft that never changed carries no user work, even if the authoritative value moved on.
    if (hasNoUserChanges) {
        return DraftRecoveryDecision.ClearDraft(draft, DraftClearReason.NO_CHANGES)
    }

    if (!current.entityExists) {
        return if (draft.baseEntityExists) {
            conflict(DraftConflictReason.ENTITY_MISSING)
        } else {
            // A new entity still has no authoritative counterpart, so its full draft snapshot is
            // the only recoverable copy.
            DraftRecoveryDecision.AutoRestore(draft.resumeEditing())
        }
    }

    if (beganFromTombstone) {
        return conflict(DraftConflictReason.ENTITY_CREATED_SINCE_EDIT_STARTED)
    }

    if (!draft.baseEntityExists) {
        return conflict(DraftConflictReason.ENTITY_CREATED_SINCE_EDIT_STARTED)
    }

    return if (currentHash == draft.baseHash) {
        // This also covers COMMITTING records whose Store write did not land before process death.
        DraftRecoveryDecision.AutoRestore(draft.resumeEditing())
    } else {
        conflict(DraftConflictReason.CURRENT_CONTENT_DIVERGED)
    }
}

/** Stable lowercase SHA-256 of the exact UTF-8 payload; no whitespace or newline normalization. */
fun baseHash(content: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
    val output = CharArray(digest.size * 2)
    var outputIndex = 0
    digest.forEach { byte ->
        val value = byte.toInt() and 0xff
        output[outputIndex++] = HEX[value ushr 4]
        output[outputIndex++] = HEX[value and 0x0f]
    }
    return output.concatToString()
}

private fun String.isSha256(): Boolean =
    length == SHA_256_HEX_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }

private const val SHA_256_HEX_LENGTH = 64
private const val HEX = "0123456789abcdef"
