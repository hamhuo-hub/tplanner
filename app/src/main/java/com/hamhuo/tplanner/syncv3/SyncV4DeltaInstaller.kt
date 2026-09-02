package com.hamhuo.tplanner.syncv3

import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * delta-v1 下行安装器(见 docs/sync-v3.md §9.3/§9.4),与桌面 src/syncV3/deltaInstaller.js 同语义:
 *
 *   - delta 只更新 server mirror;Displayed State 永远是
 *     reduce(mirror, surviving pending commands),绝不直接改 UI 状态;
 *   - 一页 /changes 的全部 commits + mirror + phone 行 + installed 指针 + cursor
 *     在同一个 Room transaction 内提交(见 [RoomSyncV3ProjectionInstaller.installMirrorAtomically]),
 *     进程死亡只会整体回滚,绝不出现"cursor 已推进、实体还停在旧版本";
 *   - 任何断链 / 未知 change type / hash 失配 / 410 / schema 不符都抛
 *     [DeltaFallbackException] fail closed 到 snapshot,绝不猜测修补、绝不跳版本;
 *   - 只有被终态回执 + snapshot/broker 证明覆盖的 outbox 命令才删除。
 */
class DeltaFallbackException(val reason: String) : Exception("delta fallback required: $reason")

data class DeltaChange(
    val type: String,
    val entityId: String,
    val entityBrokerSequence: Long,
    val value: JSONObject,
)

data class DeltaCommit(
    val snapshotVersion: Long,
    val parentVersion: Long,
    val brokerFromSequence: Long,
    val brokerToSequence: Long,
    val stateHashAfter: String,
    val changes: List<DeltaChange>,
)

data class DeltaPage(
    val serverInstanceId: String,
    val fromCursor: String,
    val toCursor: String,
    val headSnapshotVersion: Long,
    val hasMore: Boolean,
    val commits: List<DeltaCommit>,
)

data class DeltaInstallResult(val installed: Boolean, val version: Long, val appliedCommits: Int)

data class DeltaSyncResult(val installed: Boolean, val version: Long, val appliedCommits: Int)

class SyncV4DeltaInstaller(
    private val store: SyncV3Store,
    private val projectionInstaller: RoomSyncV3ProjectionInstaller? = null,
    private val onDisplayedInstalled: ((
        displayed: DisplayedStateProjection,
        authoritative: DisplayedStateProjection,
        version: Long,
        brokerToSequence: Long,
    ) -> Unit)? = null,
) {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun canonicalStateHash(state: JSONObject): String {
        val canonical = Jcs.canonicalize(state)
        return "sha256:${sha256Hex(canonical.toByteArray(Charsets.UTF_8))}"
    }

    /** 严格 wire 校验:任何偏差都直接 DeltaFallbackException,不尝试修补。 */
    fun parsePage(json: JSONObject): DeltaPage {
        fun invalid(): Nothing = throw DeltaFallbackException("DELTA_SCHEMA_UNSUPPORTED")
        if (json.optInt("protocolVersion", -1) != 3 ||
            json.optInt("deltaVersion", -1) != 1 ||
            json.optInt("schemaVersion", -1) != 3
        ) invalid()
        val serverInstanceId = json.optString("serverInstanceId").takeIf(String::isNotBlank)
            ?: invalid()
        val fromCursor = json.optString("fromCursor").takeIf(String::isNotBlank) ?: invalid()
        val toCursor = json.optString("toCursor").takeIf(String::isNotBlank) ?: invalid()
        if (!json.has("headSnapshotVersion") || !json.has("hasMore")) invalid()
        val headSnapshotVersion = json.optLong("headSnapshotVersion", -1L)
        if (headSnapshotVersion < 1L) invalid()
        val hasMore = json.optBoolean("hasMore")
        val commitsJson = json.optJSONArray("commits") ?: invalid()
        val commits = buildList {
            for (index in 0 until commitsJson.length()) {
                val commitJson = commitsJson.optJSONObject(index) ?: invalid()
                val snapshotVersion = commitJson.optLong("snapshotVersion", -1L)
                val parentVersion = commitJson.optLong("parentVersion", -1L)
                val brokerFromSequence = commitJson.optLong("brokerFromSequence", -1L)
                val brokerToSequence = commitJson.optLong("brokerToSequence", -1L)
                if (snapshotVersion < 1L || parentVersion < 0L ||
                    brokerFromSequence < 0L || brokerToSequence < 0L
                ) invalid()
                val stateHashAfter = commitJson.optString("stateHashAfter")
                    .takeIf { SHA256_PATTERN.matches(it) } ?: invalid()
                val changesJson = commitJson.optJSONArray("changes") ?: invalid()
                val changes = buildList {
                    for (changeIndex in 0 until changesJson.length()) {
                        val changeJson = changesJson.optJSONObject(changeIndex) ?: invalid()
                        val type = changeJson.optString("type").takeIf(String::isNotBlank) ?: invalid()
                        val entityId = changeJson.optString("entityId").takeIf(String::isNotBlank)
                            ?: invalid()
                        val entityBrokerSequence = changeJson.optLong("entityBrokerSequence", -1L)
                        if (entityBrokerSequence < 0L) invalid()
                        val value = changeJson.optJSONObject("value") ?: invalid()
                        add(DeltaChange(type, entityId, entityBrokerSequence, value))
                    }
                }
                add(
                    DeltaCommit(
                        snapshotVersion,
                        parentVersion,
                        brokerFromSequence,
                        brokerToSequence,
                        stateHashAfter,
                        changes,
                    ),
                )
            }
        }
        return DeltaPage(serverInstanceId, fromCursor, toCursor, headSnapshotVersion, hasMore, commits)
    }

    /** 纯函数:把 authoritative changes 装到 mirror 副本上;未知 type fail closed。 */
    fun applyToMirror(mirror: JSONObject, changes: List<DeltaChange>): JSONObject {
        val next = JSONObject(mirror.toString())
        for (change in changes) {
            val mapKey = MAP_KEY_BY_CHANGE_TYPE[change.type]
                ?: throw DeltaFallbackException("UNKNOWN_DELTA_TYPE:${change.type}")
            next.getJSONObject(mapKey).put(change.entityId, change.value)
        }
        return next
    }

    /**
     * 安装一页 commits。版本链、逐 commit hash 全部通过后,才进入 Room 事务
     * 一锤子提交;任何失败在提交前抛出,mirror 与 cursor 都保持旧值。
     */
    fun installPage(page: DeltaPage): DeltaInstallResult {
        val meta = store.getSyncState() ?: throw DeltaFallbackException("NO_SYNC_STATE")
        if (meta.serverInstanceId != null && meta.serverInstanceId != page.serverInstanceId) {
            throw SyncV3SnapshotInstaller.SnapshotException(
                "server instance changed; client must re-bootstrap",
                "ERROR008",
            )
        }

        var expectedParent = meta.installedSnapshotVersion
        val applicable = mutableListOf<DeltaCommit>()
        for (commit in page.commits) {
            if (commit.snapshotVersion <= meta.installedSnapshotVersion) continue
            if (commit.parentVersion != expectedParent) {
                throw DeltaFallbackException(
                    "DELTA_VERSION_GAP: commit ${commit.snapshotVersion} " +
                        "parent ${commit.parentVersion}, expected $expectedParent",
                )
            }
            applicable.add(commit)
            expectedParent = commit.snapshotVersion
        }
        if (applicable.isEmpty()) {
            // 整页都是重投:只推进 cursor,不动状态。
            store.upsertSyncState(meta.copy(cursor = page.toCursor))
            return DeltaInstallResult(false, meta.installedSnapshotVersion, 0)
        }

        var mirror = JSONObject(meta.serverMirrorJson ?: throw DeltaFallbackException("NO_SERVER_MIRROR"))
        for (commit in applicable) {
            mirror = applyToMirror(mirror, commit.changes)
            val hash = canonicalStateHash(mirror)
            if (hash != commit.stateHashAfter) {
                throw DeltaFallbackException("DELTA_HASH_MISMATCH:${commit.snapshotVersion}")
            }
        }
        val last = applicable.last()

        val roomResult = projectionInstaller?.installMirrorAtomically(
            mirror = mirror,
            version = last.snapshotVersion,
            stateHash = last.stateHashAfter,
            brokerToSequence = last.brokerToSequence,
            serverInstanceId = page.serverInstanceId,
            cursor = page.toCursor,
        )
        if (roomResult == null) {
            // JVM/non-Room 实现(协议测试):mirror + 指针 + cursor 一起落库。
            store.upsertSyncState(
                meta.copy(
                    installedSnapshotVersion = last.snapshotVersion,
                    installedSnapshotHash = last.stateHashAfter,
                    serverInstanceId = page.serverInstanceId,
                    serverMirrorJson = mirror.toString(),
                    installedBrokerToSequence = last.brokerToSequence,
                    cursor = page.toCursor,
                ),
            )
        } else if (roomResult.installed) {
            onDisplayedInstalled?.invoke(
                roomResult.displayed,
                roomResult.authoritative,
                last.snapshotVersion,
                last.brokerToSequence,
            )
        }
        return DeltaInstallResult(true, last.snapshotVersion, applicable.size)
    }

    /** cursor 驱动的同步循环;任何 [DeltaFallbackException] 向上抛给引擎走 snapshot。 */
    fun syncByCursor(http: SyncHttpClient, serverUrl: String): DeltaSyncResult {
        val meta = store.getSyncState() ?: throw DeltaFallbackException("NO_SYNC_STATE")
        var cursor = meta.cursor ?: throw DeltaFallbackException("NO_CURSOR")
        var applied = 0
        var version = meta.installedSnapshotVersion
        while (true) {
            val response = http.get(
                "$serverUrl/tplanner/v3/changes?cursor=${encode(cursor)}&maxCommits=100",
            )
            if (response.code == 410) {
                val body = runCatching { response.json() }.getOrNull()
                throw DeltaFallbackException(body?.optString("error") ?: "SYNC_CURSOR_EXPIRED")
            }
            if (!response.isOk) {
                throw SyncV3Uploader.SyncException(
                    "changes request failed: ${response.code}",
                    response.code,
                    null,
                )
            }
            val page = parsePage(response.json())
            if (page.fromCursor != cursor) throw DeltaFallbackException("DELTA_CURSOR_MISMATCH")
            val result = installPage(page)
            if (result.installed) applied += result.appliedCommits
            version = maxOf(version, result.version)
            if (!page.hasMore) return DeltaSyncResult(applied > 0, version, applied)
            cursor = page.toCursor
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        val MAP_KEY_BY_CHANGE_TYPE = mapOf(
            "task.put" to "tasks",
            "customList.put" to "customLists",
            "journal.put" to "journals",
            "goal.put" to "goals",
            "insight.put" to "insights",
        )
        private val SHA256_PATTERN = Regex("^sha256:[0-9a-f]{64}$")
    }
}
