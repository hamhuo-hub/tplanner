// 快照下载 / 校验 / 原子安装(见 docs/sync-v3.md §7/§8/§14)。
//
// 客户端流程:拿 manifest → 下载 gzip 载荷 → 校验 compressedHash → 解压 →
// 校验 canonical stateHash → 校验 schema/版本 → staging 全部通过后:
//   替换 Server Mirror → 重放仍 pending 的本地命令 → 原子更新 installed 指针。
// 任一步失败:旧镜像完全不动(§7)。hash 校验失败报 ERROR006。
import canonicalize from 'canonicalize';
import { loadSyncMeta, updateSyncMeta } from './syncMeta';
import { listCommands } from './commandOutbox';
import { applyCommand } from './localReducer';

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

// 本地 pending 命令归约到新镜像上(§8 Displayed State)
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

export function createSnapshotInstaller({ store, fetchFn, serverUrl, decompress = decompressGzip, ackInstalled = true } = {}) {
    async function fetchLatestMeta() {
        const res = await fetchFn(`${serverUrl}/tplanner/v3/snapshots/latest`, { headers: { 'cache-control': 'no-store' } });
        if (res.status === 404) return null; // 服务器还没有任何快照
        if (!res.ok) throw new Error(`latest snapshot request failed: ${res.status}`);
        return res.json();
    }

    async function install(envelope, manifest, stateHash) {
        const meta = await loadSyncMeta(store);
        if (meta.installedSnapshotVersion >= envelope.snapshotVersion) {
            return { installed: false, skipped: true, version: meta.installedSnapshotVersion };
        }
        if (meta.serverInstanceId && envelope.serverInstanceId !== meta.serverInstanceId) {
            const err = new Error('server instance changed; client must re-bootstrap');
            err.code = 'ERROR008';
            throw err;
        }

        // staging:镜像先在内存完整校验(本函数入口已完成),此处原子落库
        await store.set(MIRROR_KEY, envelope.state);
        const pending = await listCommands(store, { state: 'pending' });
        await store.set(DISPLAY_KEY, reduceOverlay(envelope.state, pending));

        await updateSyncMeta(store, {
            installedSnapshotVersion: envelope.snapshotVersion,
            installedSnapshotHash: stateHash,
            serverInstanceId: envelope.serverInstanceId,
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

    return {
        async getServerMirror() {
            return store.get(MIRROR_KEY);
        },
        async getDisplayState() {
            return store.get(DISPLAY_KEY);
        },

        /** 拉最新并安装;若版本相同返回 skipped。 */
        async syncToLatest() {
            const manifest = await fetchLatestMeta();
            if (!manifest) return { installed: false, skipped: true, reason: 'no snapshot yet' };

            const meta = await loadSyncMeta(store);
            if (manifest.snapshotVersion <= meta.installedSnapshotVersion) {
                return { installed: false, skipped: true, version: meta.installedSnapshotVersion };
            }

            const res = await fetchFn(`${serverUrl}/tplanner/v3/snapshots/${manifest.snapshotVersion}`, {
                headers: { 'if-none-match': `"${manifest.compressedHash}"` },
            });
            if (!res.ok) throw new Error(`snapshot download failed: ${res.status}`);

            const compressed = new Uint8Array(await res.arrayBuffer());
            const compressedHash = `sha256:${await sha256Hex(compressed)}`;
            if (compressedHash !== manifest.compressedHash) {
                const err = new Error(`compressed hash mismatch: ${compressedHash}`);
                err.code = 'ERROR006';
                throw err;
            }

            const bytes = await decompress(compressed);
            const envelope = JSON.parse(new TextDecoder().decode(bytes));
            if (envelope.snapshotSchemaVersion !== 3 || envelope.snapshotVersion !== manifest.snapshotVersion) {
                const err = new Error('envelope does not match manifest');
                err.code = 'ERROR006';
                throw err;
            }

            const stateHash = await canonicalStateHash(envelope.state);
            if (stateHash !== manifest.stateHash) {
                const err = new Error(`state hash mismatch: ${stateHash}`);
                err.code = 'ERROR006';
                throw err;
            }

            return install(envelope, manifest, stateHash);
        },
    };
}
