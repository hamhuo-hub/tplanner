// 快照下载 / 校验 / 原子安装(见 docs/sync-v3.md §7/§8/§14)。
//
// 客户端流程:拿 manifest → 下载 gzip 载荷 → 校验 compressedHash → 解压 →
// 校验 canonical stateHash → 校验 schema/版本 → staging 全部通过后:
//   替换 Server Mirror → 重放仍未收到终态回执的本地命令 → 更新 installed 指针。
// 任一解码/校验步骤失败:旧镜像完全不动(§7)。hash 校验失败报 ERROR006。
import canonicalize from 'canonicalize';
import { loadSyncMeta, META_KEY } from './syncMeta';
import { listCommands, isTerminalReceipt } from './commandOutbox';
import { applyCommand } from './localReducer';
import { appendSnapshotInstall, HISTORY_KEY } from './history';
import {
    assertJsonResponse,
    getResponseHeader,
    protocolError,
    readJsonResponse,
} from './httpResponse';

export async function sha256Hex(input) {
    const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export async function canonicalStateHash(state) {
    const canonical = canonicalize(state);
    if (canonical === undefined) throw new Error('state is not canonicalizable');
    return `sha256:${await sha256Hex(new TextEncoder().encode(canonical))}`;
}

// 浏览器默认解压:DecompressionStream('gzip');测试注入 node:zlib 实现。
export async function decompressGzip(compressed) {
    const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream('gzip'));
    return new Uint8Array(await new Response(stream).arrayBuffer());
}

// 本地未确认(pending / uploaded)命令归约到新镜像上(§8 Displayed State)
export function reduceOverlay(mirrorState, pendingCommands) {
    let state = mirrorState;
    for (const command of pendingCommands) {
        const result = applyCommand(state, command, command.clientSequence);
        state = result.state;
    }
    return state;
}

const MIRROR_KEY = 'mirror';
const DISPLAY_KEY = 'display';
const SHA256_PATTERN = /^sha256:[0-9a-f]{64}$/i;

function normalizeSha256(value) {
    if (!value) return null;
    let normalized = String(value).trim().replace(/^W\//i, '');
    if (normalized.startsWith('"') && normalized.endsWith('"')) {
        normalized = normalized.slice(1, -1);
    }
    if (/^[0-9a-f]{64}$/i.test(normalized)) normalized = `sha256:${normalized}`;
    return SHA256_PATTERN.test(normalized) ? normalized.toLowerCase() : null;
}

function validateManifest(manifest, response) {
    if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
        throw protocolError('latest snapshot manifest is not an object', response);
    }
    if (!Number.isSafeInteger(manifest.snapshotVersion) || manifest.snapshotVersion < 1) {
        throw protocolError('latest snapshot manifest has an invalid version', response);
    }
    if (!normalizeSha256(manifest.stateHash) || !normalizeSha256(manifest.compressedHash)) {
        throw protocolError('latest snapshot manifest has invalid hash metadata', response);
    }
    return {
        ...manifest,
        stateHash: normalizeSha256(manifest.stateHash),
        compressedHash: normalizeSha256(manifest.compressedHash),
    };
}

function isGzip(bytes) {
    return bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b;
}

function isRecord(value) {
    return !!value && typeof value === 'object' && !Array.isArray(value);
}

function validateEnvelope(envelope, manifest) {
    const state = envelope?.state;
    const validState = isRecord(state)
        && ['tasks', 'customLists', 'journals', 'goals', 'insights'].every((key) => isRecord(state[key]));
    if (!isRecord(envelope)
        || envelope.snapshotSchemaVersion !== 3
        || envelope.snapshotVersion !== manifest.snapshotVersion
        || typeof envelope.serverInstanceId !== 'string'
        || envelope.serverInstanceId === ''
        || !Number.isSafeInteger(envelope.brokerToSequence)
        || envelope.brokerToSequence < 0
        || !validState) {
        const err = new Error('snapshot envelope does not match the V3 schema');
        err.code = 'ERROR006';
        throw err;
    }
}

function receiptCovered(receipt, proof) {
    if (!receipt || !isTerminalReceipt(receipt.status)) return false;
    const coveredBySnapshot = Number.isSafeInteger(receipt.snapshotVersion)
        && Number.isSafeInteger(proof.snapshotVersion)
        && receipt.snapshotVersion <= proof.snapshotVersion;
    const coveredByBroker = Number.isSafeInteger(receipt.brokerSequence)
        && Number.isSafeInteger(proof.brokerToSequence)
        && receipt.brokerSequence <= proof.brokerToSequence;
    return coveredBySnapshot || coveredByBroker;
}

async function projectCoveredOutbox(store, mirror, proof) {
    const commands = await listCommands(store, {
        state: ['pending', 'uploaded'],
        limit: Number.MAX_SAFE_INTEGER,
    });
    const receipts = new Map(
        (await store.entries('receipt:')).map(([, receipt]) => [receipt.clientSequence, receipt]),
    );
    const surviving = [];
    const deleteKeys = [];
    for (const command of commands) {
        const receipt = receipts.get(command.clientSequence);
        if (receipt?.commandId === command.commandId && receiptCovered(receipt, proof)) {
            deleteKeys.push(`cmd:${command.clientSequence}`);
        } else {
            surviving.push(command);
        }
    }
    return { display: reduceOverlay(mirror, surviving), deleteKeys };
}

export function createSnapshotInstaller({ store, fetchFn, serverUrl, decompress = decompressGzip, ackInstalled = true } = {}) {
    async function fetchLatest(meta) {
        const headers = { 'cache-control': 'no-store' };
        const sentValidator = normalizeSha256(meta.installedSnapshotCompressedHash);
        if (sentValidator) {
            headers['if-none-match'] = `"${normalizeSha256(meta.installedSnapshotCompressedHash)}"`;
        }
        const res = await fetchFn(`${serverUrl}/tplanner/v3/snapshots/latest`, { headers });
        if (res.status === 304) {
            if (!sentValidator) throw protocolError('latest snapshot returned 304 without a local validator', res);
            return { notModified: true };
        }
        if (res.status === 404) {
            assertJsonResponse(res, 'latest snapshot request');
            return null; // 服务器还没有任何快照
        }
        if (!res.ok) throw new Error(`latest snapshot request failed: ${res.status}`);

        const contentType = getResponseHeader(res, 'content-type')?.toLowerCase() ?? '';
        const headerlessJsonStub = !contentType
            && typeof res.json === 'function'
            && typeof res.arrayBuffer !== 'function';
        if (contentType.includes('json') || headerlessJsonStub) {
            const manifest = validateManifest(
                await readJsonResponse(res, 'latest snapshot manifest request'),
                res,
            );
            return { manifest, payloadResponse: null };
        }

        if (contentType && !/(?:application\/(?:octet-stream|(?:x-)?gzip))/.test(contentType)) {
            const hint = contentType.includes('html')
                ? '; check that /tplanner API routes are proxied before the SPA fallback'
                : '';
            throw protocolError(
                `latest snapshot endpoint returned ${contentType}; expected JSON or snapshot bytes${hint}`,
                res,
            );
        }

        const manifest = validateManifest({
            snapshotVersion: Number(getResponseHeader(res, 'x-snapshot-version')),
            stateHash: normalizeSha256(getResponseHeader(res, 'x-state-hash')),
            compressedHash: normalizeSha256(getResponseHeader(res, 'etag')),
        }, res);
        return { manifest, payloadResponse: res };
    }

    async function install(envelope, stateHash, compressedHash) {
        const meta = await loadSyncMeta(store);
        if (meta.serverInstanceId && envelope.serverInstanceId !== meta.serverInstanceId) {
            const err = new Error('server instance changed; client must re-bootstrap');
            err.code = 'ERROR008';
            throw err;
        }
        if (meta.installedSnapshotVersion >= envelope.snapshotVersion) {
            return { installed: false, skipped: true, version: meta.installedSnapshotVersion };
        }

        // Receipt cleanup, mirror replacement, pending replay and pointer advance share one
        // IndexedDB transaction. A crash can expose neither an old mirror without its overlay
        // nor a new mirror with commands deleted before their proof snapshot.
        const proof = {
            snapshotVersion: envelope.snapshotVersion,
            brokerToSequence: envelope.brokerToSequence,
        };
        const { display, deleteKeys } = await projectCoveredOutbox(store, envelope.state, proof);
        const nextMeta = {
            ...meta,
            installedSnapshotVersion: envelope.snapshotVersion,
            installedSnapshotHash: stateHash,
            installedSnapshotCompressedHash: compressedHash,
            installedBrokerToSequence: envelope.brokerToSequence,
            serverInstanceId: envelope.serverInstanceId,
        };
        const history = appendSnapshotInstall(
            (await store.get(HISTORY_KEY)) ?? [],
            { version: envelope.snapshotVersion, stateHash },
        );
        if (typeof store.mutateMany !== 'function') {
            throw new Error('snapshot store does not support atomic mutateMany');
        }
        await store.mutateMany({
            deleteKeys,
            setEntries: [
                [MIRROR_KEY, envelope.state],
                [DISPLAY_KEY, display],
                [META_KEY, nextMeta],
                [HISTORY_KEY, history],
            ],
        });

        if (ackInstalled) {
            const meta2 = await loadSyncMeta(store);
            await fetchFn(
                `${serverUrl}/tplanner/v3/devices/${encodeURIComponent(meta2.deviceId)}/snapshot-acks`,
                {
                    method: 'POST',
                    headers: { 'content-type': 'application/json' },
                    body: JSON.stringify({ version: envelope.snapshotVersion, stateHash }),
                },
            ).catch(() => { /* ACK 可补发,失败不致命(§7) */ });
        }

        return { installed: true, skipped: false, version: envelope.snapshotVersion };
    }

    async function runSyncToLatest() {
        const meta = await loadSyncMeta(store);
        const latest = await fetchLatest(meta);
        if (!latest) return { installed: false, skipped: true, reason: 'no snapshot yet' };
        if (latest.notModified) {
            return { installed: false, skipped: true, version: meta.installedSnapshotVersion };
        }

        const { manifest } = latest;
        if (manifest.snapshotVersion < meta.installedSnapshotVersion) {
            throw protocolError('latest snapshot version regressed below the installed version');
        }
        if (manifest.snapshotVersion === meta.installedSnapshotVersion) {
            const sameState = manifest.stateHash === normalizeSha256(meta.installedSnapshotHash);
            const knownCompressed = normalizeSha256(meta.installedSnapshotCompressedHash);
            const sameCompressed = !knownCompressed || manifest.compressedHash === knownCompressed;
            latest.payloadResponse?.body?.cancel?.().catch(() => {});
            if (!sameState || !sameCompressed) {
                throw protocolError('latest snapshot metadata conflicts with the installed version');
            }
            return { installed: false, skipped: true, version: meta.installedSnapshotVersion };
        }

        const res = latest.payloadResponse
            ?? await fetchFn(`${serverUrl}/tplanner/v3/snapshots/${manifest.snapshotVersion}`);
        if (!res.ok) throw new Error(`snapshot download failed: ${res.status}`);
        const payloadType = getResponseHeader(res, 'content-type')?.toLowerCase() ?? '';
        if (payloadType.includes('html')) {
            throw protocolError(
                'snapshot download returned HTML; check that /tplanner API routes are proxied before the SPA fallback',
                res,
            );
        }

        const payload = new Uint8Array(await res.arrayBuffer());
        let bytes = payload;
        if (isGzip(payload)) {
            const compressedHash = `sha256:${await sha256Hex(payload)}`;
            if (compressedHash !== manifest.compressedHash) {
                const err = new Error(`compressed hash mismatch: ${compressedHash}`);
                err.code = 'ERROR006';
                throw err;
            }
            bytes = await decompress(payload);
        }

        let envelope;
        try {
            envelope = JSON.parse(new TextDecoder().decode(bytes));
        } catch (cause) {
            const err = new Error('snapshot payload is not a valid JSON envelope');
            err.code = 'ERROR006';
            err.cause = cause;
            throw err;
        }
        validateEnvelope(envelope, manifest);

        const stateHash = await canonicalStateHash(envelope.state);
        if (stateHash !== manifest.stateHash) {
            const err = new Error(`state hash mismatch: ${stateHash}`);
            err.code = 'ERROR006';
            throw err;
        }

        return install(envelope, stateHash, manifest.compressedHash);
    }

    let syncTail = Promise.resolve();

    return {
        async getServerMirror() {
            return store.get(MIRROR_KEY);
        },
        async getDisplayState() {
            return store.get(DISPLAY_KEY);
        },

        async rebaseDisplay() {
            const mirror = await store.get(MIRROR_KEY);
            if (!mirror) return null;
            const meta = await loadSyncMeta(store);
            const { display, deleteKeys } = await projectCoveredOutbox(store, mirror, {
                snapshotVersion: meta.installedSnapshotVersion,
                brokerToSequence: meta.installedBrokerToSequence ?? 0,
            });
            if (typeof store.mutateMany !== 'function') {
                throw new Error('snapshot store does not support atomic mutateMany');
            }
            await store.mutateMany({
                deleteKeys,
                setEntries: [[DISPLAY_KEY, display]],
            });
            return display;
        },

        /** 拉最新并安装;若版本相同返回 skipped。 */
        syncToLatest() {
            const run = syncTail.then(runSyncToLatest, runSyncToLatest);
            syncTail = run.catch(() => {});
            return run;
        },
    };
}
