// 同步的状态与流程：适配器驱动的同步引擎 + base 快照三方对比 + 人工冲突裁决。
//
// 每种数据类型通过 SyncAdapter 注册，引擎只迭代 adapters[]。每次成功同步后
// 把「合并结果的内容键」存为 base 快照（localStorage），下次同步据此区分
// "只有一边改了"（自动）与"两边都改了"（人工裁决）。未裁决的冲突两边各保
// 各的，不会被自动同步冲掉。
//
// 旧 API（{ events, onMergeEvents, ... }）仍被支持，内部自动转为 adapters。
import { useState, useEffect, useRef, useCallback } from 'react';
import {
    DEFAULT_CONFIG, DEFAULT_SERVER_URL, normalizeServerUrl,
    syncClockOffset, fetchAndAnalyze, syncAndPush,
    BUILTIN_ADAPTERS,
} from '../utils/syncLogic';
import { waitForRemoteChanges } from '../utils/syncClient';

// ── base 快照持久化 ──────────────────────────────────────────────────────────
// 按数据类型存 { id → contentKey }。假设单一同步服务器（固定地址），
// 不按服务器区分；若未来支持多服务器，key 需带上服务器标识。
function loadBaseKeys(type) {
    try { return JSON.parse(localStorage.getItem(`tplanner_sync_base::${type}`) || 'null'); }
    catch { return null; }
}
function saveBaseKeys(type, keys) {
    try { localStorage.setItem(`tplanner_sync_base::${type}`, JSON.stringify(keys)); }
    catch { /* 存不下就下次退化为 LWW，不致命 */ }
}

export default function useLanSync(props = {}) {
    const adapters = resolveAdapters(props);

    const [open, setOpen]           = useState(false);
    const [config, setConfig]       = useState(DEFAULT_CONFIG);

    const [status, setStatus]       = useState('idle');
    const [statusMsg, setStatusMsg] = useState('');
    // 自动同步遇到的未裁决冲突数（跨 adapter 合计），面板给用户挂徽章
    const [pendingConflicts, setPendingConflicts] = useState(0);

    // Conflict preview: { results: [{ adapter, analysis, remoteData }], serverUrl }
    const [preview, setPreview]     = useState(null);

    const isElectron = typeof window !== 'undefined' && !!window.electronAPI;

    const adaptersRef = useRef(adapters);
    useEffect(() => { adaptersRef.current = adapters; }, [adapters]);

    // Load config, then sync once on startup
    useEffect(() => {
        if (!isElectron) return;
        window.electronAPI.getLanConfig?.().then(cfg => {
            const serverUrl = normalizeServerUrl(cfg?.serverUrl) || DEFAULT_SERVER_URL;
            setConfig(c => ({ ...c, ...cfg, serverUrl }));
            doSyncRef.current?.(serverUrl, true);
        });
    }, [isElectron]);

    const doSyncRef = useRef(null);
    const automaticSyncChainRef = useRef(Promise.resolve());

    const saveConfig = useCallback((next) => {
        setConfig(next);
        if (isElectron) window.electronAPI.saveLanConfig?.(next);
    }, [isElectron]);

    // ── 同步（含冲突预览） ────────────────────────────────────────────────
    const doSync = useCallback(async (serverUrl, skipPreview = false, datasetTypes = null) => {
        const base = normalizeServerUrl(serverUrl);
        if (!base) { setStatus('error'); setStatusMsg('未配置服务器地址'); return; }
        setStatus('syncing'); setStatusMsg('');
        try {
            await syncClockOffset(base);
            const requestedTypes = datasetTypes ? new Set(datasetTypes) : null;
            const ads = requestedTypes
                ? adaptersRef.current.filter(adapter => requestedTypes.has(adapter.type))
                : adaptersRef.current;

            if (!skipPreview) {
                const results = [];
                for (const a of ads) {
                    const localData = a._getLocal ? await a._getLocal() : [];
                    const r = await fetchAndAnalyze(a, base, localData, loadBaseKeys(a.type));
                    if (r) results.push(r);
                    else if (a.isRequired) throw new Error(`${a.type} 拉取失败`);
                }

                const hasChanges = results.some(r => {
                    const an = r.analysis;
                    return an.added.length + an.removed.length + an.updated.length +
                           an.deleted.length + an.conflicted.length + an.manual.length > 0;
                });
                if (!hasChanges) {
                    setStatus('success'); setStatusMsg('数据完全一致，无需合并');
                    setPendingConflicts(0);
                    return;
                }
                setPreview({ results, serverUrl: base });
                setStatus('idle');
                return;
            }

            // 自动同步：无人工裁决，真冲突挂起（两边互不覆盖）
            await executeMerge(base, {}, ads);
        } catch (e) { setStatus('error'); setStatusMsg(e.message); }
    }, []);

    useEffect(() => { doSyncRef.current = doSync; }, [doSync]);

    const queueAutomaticSync = useCallback((serverUrl, datasetTypes = null) => {
        automaticSyncChainRef.current = automaticSyncChainRef.current
            .catch(() => undefined)
            .then(() => doSyncRef.current?.(serverUrl, true, datasetTypes));
        return automaticSyncChainRef.current;
    }, []);

    // A completed local operation immediately queues exactly the affected dataset. Local
    // adapters read the committed source of truth, so no timer/debounce is needed here.
    useEffect(() => {
        if (!isElectron || !props.syncRequest?.sequence) return;
        const base = normalizeServerUrl(config.serverUrl);
        if (!base) return;
        const dataset = props.syncRequest.dataset;
        queueAutomaticSync(base, [dataset]);
    }, [isElectron, props.syncRequest?.sequence, props.syncRequest?.dataset, config.serverUrl, queueAutomaticSync]);

    // Keep one long-poll open while the desktop app is running. A notification contains only
    // dataset names; the client actively pulls and merges the authoritative payload itself.
    useEffect(() => {
        if (!isElectron) return;
        const base = normalizeServerUrl(config.serverUrl);
        if (!base) return;

        const controller = new AbortController();
        let revision = 0;
        let retryDelay = 1000;
        let retryTimer = null;

        const waitBeforeRetry = () => new Promise(resolve => {
            retryTimer = setTimeout(resolve, retryDelay);
            retryDelay = Math.min(retryDelay * 2, 30000);
        });

        const listen = async () => {
            while (!controller.signal.aborted) {
                try {
                    const notice = await waitForRemoteChanges(base, revision, controller.signal);
                    revision = Number(notice.revision) || revision;
                    retryDelay = 1000;
                    const datasets = Array.isArray(notice.datasets) ? notice.datasets : [];
                    if (datasets.length > 0) await queueAutomaticSync(base, datasets);
                } catch (error) {
                    if (controller.signal.aborted || error?.name === 'AbortError') return;
                    await waitBeforeRetry();
                }
            }
        };

        listen();
        return () => {
            controller.abort();
            clearTimeout(retryTimer);
        };
    }, [isElectron, config.serverUrl, queueAutomaticSync]);

    // resolutions: { [adapterType]: { [id]: 'local' | 'remote' } }
    const executeMerge = useCallback(async (serverUrl, resolutions = {}, currentAdapters) => {
        const base = normalizeServerUrl(serverUrl);
        const ads = currentAdapters || adaptersRef.current;
        let totalMerged = 0, totalUnresolved = 0;
        const conflictResults = [];

        for (const a of ads) {
            const localData = a._getLocal ? await a._getLocal() : [];
            const r = await syncAndPush(a, base, localData, loadBaseKeys(a.type), resolutions[a.type] || {});
            if (r !== null) {
                if (a._writeLocal) await a._writeLocal(r.merged);
                saveBaseKeys(a.type, r.newBaseKeys);
                totalUnresolved += r.unresolved;
                if (r.unresolved > 0) {
                    conflictResults.push({ adapter: a, analysis: r.analysis });
                }
                if (Array.isArray(r.merged)) totalMerged += r.merged.length;
            } else if (a.isRequired) {
                setStatus('error'); setStatusMsg(`${a.type} 同步失败`); return;
            }
        }

        setPendingConflicts(totalUnresolved);
        if (totalUnresolved > 0) {
            setStatus('error');
            setStatusMsg(`发现 ${totalUnresolved} 条同步冲突，请在弹窗中选择保留版本`);
            setPreview({ results: conflictResults, serverUrl: base, automatic: true });
        } else {
            setStatus('success');
            setStatusMsg(totalMerged > 0 ? `已同步 ${totalMerged} 条记录` : '同步完成');
            setPreview(null);
        }
    }, []);

    const serverUrl = normalizeServerUrl(config.serverUrl);
    const statusColor = { idle: 'var(--clr-text-dim)', syncing: 'var(--clr-gold)', success: '#4A9DA8', error: 'var(--clr-red,#C0392B)' }[status];

    return {
        isElectron, open, setOpen, config, setConfig, saveConfig,
        status, statusMsg, statusColor, preview, setPreview,
        doSync, executeMerge, serverUrl, pendingConflicts,
    };
}

// ── 向后兼容：旧 API → 新 adapters[] ──────────────────────────────────────
function resolveAdapters(props) {
    if (props.adapters && Array.isArray(props.adapters)) return props.adapters;

    const ads = [];

    if (props.events !== undefined || props.onMergeEvents) {
        const a = { ...BUILTIN_ADAPTERS.events };
        a._getLocal  = () => props.events || [];
        a._writeLocal = (m) => props.onMergeEvents?.(m);
        ads.push(a);
    }

    if (props.journals !== undefined || props.onMergeJournals) {
        const a = { ...BUILTIN_ADAPTERS.journals };
        a._getLocal  = () => props.journals || {};
        a._writeLocal = (m) => props.onMergeJournals?.(m);
        ads.push(a);
    }

    return ads;
}
