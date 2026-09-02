package com.hamhuo.tplanner.syncv3

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.hamhuo.tplanner.ScheduleItem
import com.hamhuo.tplanner.persistence.SettingsRepository
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import com.hamhuo.tplanner.persistence.LegacyImportResult
import com.hamhuo.tplanner.persistence.LegacyPreferencesImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class SyncV3Phase(val wire: String) {
    IDLE("idle"),
    SAVED("saved"),
    UPLOADED("uploaded"),
    UPDATING("updating"),
    SUCCESS("success"),
    ERROR("error"),
}

data class SyncV3RunResult(
    val installedSnapshotVersion: Long,
    val pendingCommands: Int,
    val uploadedCommands: Int,
    val phase: SyncV3Phase,
)

class SyncV3RunException(
    val errorCode: String,
    cause: Throwable,
) : Exception("$errorCode: ${cause.message.orEmpty()}", cause)

/**
 * Single Android V3 transaction pump: bootstrap barrier → ordered upload → central receipt →
 * immutable snapshot install. There is no dataset GET/PUT or client-side merge in this path.
 */
class SyncV3Engine(
    context: Context,
    private val database: TPlannerDatabase = TPlannerDatabase.get(context),
    private val http: SyncHttpClient = HttpUrlConnectionSyncHttpClient(),
    private val onDisplayedInstalled: (
        displayedEvents: List<ScheduleItem>,
        authoritativeEvents: List<ScheduleItem>,
        snapshotVersion: Long,
        brokerToSequence: Long,
    ) -> Boolean = { _, _, _, _ -> false },
    /** Android canary 开关(§9.3):默认关,capability + cursor 齐备才走 delta。 */
    private val deltaEnabled: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val dao = database.syncV3Dao()
    private val store = RoomSyncV3Store(dao)
    private val commands = SyncV3CommandRepository(appContext, database)
    private val projection = RoomSyncV3ProjectionInstaller(database)

    private val onDeltaDisplayedInstalled: (
        DisplayedStateProjection,
        DisplayedStateProjection,
        Long,
        Long,
    ) -> Unit = { displayed, authoritative, version, brokerToSequence ->
        if (onDisplayedInstalled(displayed.events, authoritative.events, version, brokerToSequence)) {
            projection.markWatchProjectionPublished(version, brokerToSequence)
        }
    }

    suspend fun syncOnce(serverUrl: String = SettingsRepository.DEFAULT_SERVER_URL): SyncV3RunResult =
        processMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    // Every background entry point crosses the local upgrade barrier before it
                    // can publish a bootstrap marker. This prevents a Watch/GMS service launched
                    // after package replacement from racing ahead of MainActivity and orphaning
                    // still-valid SharedPreferences facts.
                    val localMigration = LegacyPreferencesImporter(appContext, database).importIfNeeded()
                    if (localMigration is LegacyImportResult.Blocked) {
                        throw SyncV3SnapshotInstaller.SnapshotException(
                            "local storage upgrade is blocked: " +
                                localMigration.issues.joinToString { it.message },
                            "ERROR007",
                        )
                    }
                    requireNetwork()
                    val base = normalizeServerUrl(serverUrl)
                    val capabilities = verifyCapabilities(base)
                    commands.prepareForServerInstance(capabilities.optString("serverInstanceId"))

                    val installer = SyncV3SnapshotInstaller(
                        store = store,
                        kv = UnusedRoomKv,
                        http = http,
                        serverUrl = base,
                        projectionInstaller = projection,
                        onDisplayedInstalled = { displayed, authoritative, version, brokerToSequence ->
                            if (onDisplayedInstalled(
                                    displayed.events,
                                    authoritative.events,
                                    version,
                                    brokerToSequence,
                                )
                            ) {
                                projection.markWatchProjectionPublished(version, brokerToSequence)
                            }
                        },
                    )

                    // Cutover reads a verified central baseline without installing it, persists
                    // only missing/local intent as guarded semantic commands, then atomically
                    // installs baseline + overlay. A stale upgrading phone can never full-upsert
                    // over newer central fields, and local-only facts never disappear in-between.
                    val baseline = if (commands.needsBootstrap()) {
                        installer.fetchLatestVerified()
                    } else {
                        null
                    }
                    if (baseline != null &&
                        baseline.verified.serverInstanceId != capabilities.optString("serverInstanceId")
                    ) {
                        throw SyncV3SnapshotInstaller.SnapshotException(
                            "capabilities and snapshot identify different server instances",
                            "ERROR008",
                        )
                    }
                    val bootstrap = commands.bootstrapIfNeeded(baseline?.verified?.state)
                    if (bootstrap.performed && baseline != null) {
                        installer.install(
                            baseline.verified.state,
                            baseline.manifest,
                            baseline.verified.serverInstanceId,
                            baseline.verified.brokerToSequence,
                        )
                    }
                    update(SyncV3Phase.SAVED)

                    val receiptCursorBeforeRun = store.acceptedThrough() ?: 0L
                    val uploader = SyncV3Uploader(store, http, base)
                    uploader.flush()
                    update(SyncV3Phase.UPLOADED)

                    update(SyncV3Phase.UPDATING)
                    downlink(installer, capabilities, base)
                    drainReceipts(uploader)
                    reconcileReceiptsAndWatch()

                    // A 202 means broker-persisted, not yet visible. Wait only while the central
                    // snapshot we need is absent; notifications wake immediately on publication.
                    val requiredVersion = dao.maxReceiptSnapshotVersion() ?: 0L
                    var installed = dao.getSyncState()?.installedSnapshotVersion ?: 0L
                    if (store.uploadedCount() > 0 || installed < requiredVersion) {
                        val notification = SyncV3NotificationClient(
                            store,
                            http,
                            base,
                            waitMs = PUBLICATION_WAIT_MILLIS,
                        ).pollOnce()
                        if (notification.hasNewVersion) downlink(installer, capabilities, base)
                        drainReceipts(uploader)
                        reconcileReceiptsAndWatch()
                        installed = dao.getSyncState()?.installedSnapshotVersion ?: installed
                    }

                    val proof = dao.getSyncState()
                    dao.latestFailedReceiptAfter(
                        receiptCursorBeforeRun,
                        proof?.installedSnapshotVersion ?: 0L,
                        proof?.installedBrokerToSequence ?: 0L,
                    )?.let { receipt ->
                        throw SyncV3Uploader.SyncException(
                            "central reducer rejected ${receipt.commandId}: ${receipt.errorCode}",
                            status = 422,
                            errorCode = receipt.errorCode ?: "COMMAND_REJECTED",
                        )
                    }

                    val pending = store.pendingCount()
                    val uploaded = store.uploadedCount()
                    val phase = if (pending == 0 && uploaded == 0) {
                        SyncV3Phase.SUCCESS
                    } else {
                        SyncV3Phase.UPLOADED
                    }
                    update(phase)
                    SyncV3RunResult(installed, pending, uploaded, phase)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    val code = error.toSyncV3ErrorCode()
                    store.updateSyncStatus("error", code, System.currentTimeMillis())
                    if (store.pendingCount() > 0) {
                        store.recordPendingFailure(
                            nextAttemptAt = System.currentTimeMillis() + RETRY_BASE_MILLIS,
                            errorCode = code,
                        )
                    }
                    throw SyncV3RunException(code, error)
                }
            }
        }

    /** One notification wait used by the foreground monitor; data still arrives only as snapshot. */
    suspend fun awaitNewSnapshot(
        serverUrl: String = SettingsRepository.DEFAULT_SERVER_URL,
        waitMs: Int = 25_000,
    ): SyncV3NotificationClient.NotificationResult = withContext(Dispatchers.IO) {
        // Identity is initialized here, but the cutover itself belongs to syncOnce where a
        // verified central baseline is available.
        commands.needsBootstrap()
        SyncV3NotificationClient(store, http, normalizeServerUrl(serverUrl), waitMs).pollOnce()
    }

    private fun drainReceipts(uploader: SyncV3Uploader) {
        while (uploader.collectReceipts() >= RECEIPT_PAGE_SIZE) {
            // Cursor is persisted in Room, so every iteration advances and survives process death.
        }
    }

    private fun reconcileReceiptsAndWatch() {
        val result = projection.reconcileInstalledState() ?: return
        val meta = dao.getSyncState() ?: return
        if (result.removedCommands == 0 &&
            meta.watchProjectionSnapshotVersion >= result.installedVersion
        ) return
        if (onDisplayedInstalled(
                result.displayed.events,
                result.authoritative.events,
                result.installedVersion,
                result.installedBrokerToSequence,
            )
        ) {
            projection.markWatchProjectionPublished(
                result.installedVersion,
                result.installedBrokerToSequence,
            )
        }
    }

    private fun verifyCapabilities(base: String): JSONObject {
        val response = http.get("$base/tplanner/v3/capabilities", timeoutMs = 10_000)
        if (!response.isOk) {
            throw SyncV3Uploader.SyncException(
                "capabilities request failed: ${response.code}",
                response.code,
                null,
            )
        }
        val body = runCatching(response::json).getOrElse { error ->
            throw SyncV3SnapshotInstaller.SnapshotException(
                "capabilities is not JSON",
                "ERROR008",
            ).apply { initCause(error) }
        }
        if (body.optString("softwareVersion") != EXPECTED_SOFTWARE_VERSION ||
            body.optInt("protocolVersion", -1) != 3 ||
            body.optInt("schemaVersion", -1) != 3
        ) {
            throw SyncV3SnapshotInstaller.SnapshotException(
                "server does not match the TPlanner 8.0.0 V3 contract",
                "ERROR008",
            )
        }
        body.optString("serverInstanceId").takeIf(String::isNotBlank)
            ?: throw SyncV3SnapshotInstaller.SnapshotException(
                "capabilities has no serverInstanceId",
                "ERROR008",
            )
        return body
    }

    /**
     * 下行统一入口(§9.3):canary 开关 + capability 都允许且本地已有 cursor 时走
     * delta;任何断链/410/未知 type 都 fail closed 到完整快照逃生舱。快照安装
     * 会用 manifest.cursor 重建 delta 起点,两条路径互不冲突。
     */
    private fun downlink(
        installer: SyncV3SnapshotInstaller,
        capabilities: JSONObject,
        base: String,
    ) {
        val meta = store.getSyncState()
        val deltaEligible = deltaEnabled &&
            capabilities.optJSONArray("downlinkModes")?.let { modes ->
                (0 until modes.length()).any { modes.optString(it) == "delta-v1" }
            } == true &&
            !meta?.cursor.isNullOrEmpty()
        if (deltaEligible) {
            val deltaInstaller = SyncV4DeltaInstaller(store, projection, onDeltaDisplayedInstalled)
            try {
                deltaInstaller.syncByCursor(http, base)
                return
            } catch (_: DeltaFallbackException) {
                // 已装 commit 不回滚;snapshot 覆盖权威状态并重建 cursor。
            }
        }
        installer.syncToLatest()
    }

    private fun requireNetwork() {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val network = manager.activeNetwork
            ?: throw IllegalStateException("NO_NETWORK")
        val capabilities = manager.getNetworkCapabilities(network)
            ?: throw IllegalStateException("NO_NETWORK")
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            throw IllegalStateException("NO_NETWORK")
        }
    }

    private fun update(phase: SyncV3Phase) {
        store.updateSyncStatus(phase.wire, null, System.currentTimeMillis())
    }

    companion object {
        private const val RECEIPT_PAGE_SIZE = 200
        private const val EXPECTED_SOFTWARE_VERSION = "8.0.0"
        private const val PUBLICATION_WAIT_MILLIS = 5_000
        private const val RETRY_BASE_MILLIS = 10_000L
        private val processMutex = Mutex()

        fun normalizeServerUrl(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isBlank()) return SettingsRepository.DEFAULT_SERVER_URL
            val withScheme = if (trimmed.startsWith("http://", true) ||
                trimmed.startsWith("https://", true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
            return withScheme.trimEnd('/')
        }
    }
}

private object UnusedRoomKv : SyncKeyValueStore {
    override fun get(key: String): String? = null
    override fun set(key: String, value: String) = Unit
}

private fun Throwable.toSyncV3ErrorCode(): String {
    val explicit = when (this) {
        is SyncV3RunException -> errorCode
        is SyncV3SnapshotInstaller.SnapshotException -> code
        is SyncV3Uploader.SyncException -> when {
            status == 401 || status == 403 -> "ERROR010"
            status == 422 -> "ERROR009"
            status == 202 -> null
            else -> errorCode?.takeIf { it.startsWith("ERROR") } ?: "ERROR003"
        }
        else -> null
    }
    if (explicit != null) return explicit
    if (message == "NO_NETWORK") return "ERROR001"
    return when (this) {
        is UnknownHostException,
        is NoRouteToHostException,
        is ConnectException,
        is SocketTimeoutException,
        -> "ERROR002"
        else -> cause?.takeIf { it !== this }?.toSyncV3ErrorCode() ?: "ERROR008"
    }
}
