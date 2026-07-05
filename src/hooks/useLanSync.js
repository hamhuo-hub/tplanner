// 同步的状态与流程：适配器驱动的同步引擎。
// 不再硬编码 events/goals/journals 三类数据——每种数据类型通过 SyncAdapter 注册，
// sync 引擎只迭代 adapters[]，不知道也不关心后面是什么数据。
//
// 旧 API（{ events, onMergeEvents, ... }）仍被支持，内部自动转为 adapters。
import { useState, useEffect, useRef, useCallback } from 'react';
import {
    DEFAULT_CONFIG, DEFAULT_SERVER_URL, normalizeServerUrl,
    syncClockOffset, fetchAndAnalyze, syncAndPush,
    BUILTIN_ADAPTERS,
} from '../utils/syncLogic';

export default function useLanSync(props = {}) {
    // ── resolve adapters ──────────────────────────────────────────────────
    // 新 API: props.adapters = SyncAdapter[]
    // 旧 API: props = { events, onMergeEvents, journals, onMergeJournals, goals, onMergeGoals }
    const adapters = resolveAdapters(props);

    const [open, setOpen]           = useState(false);
    const [config, setConfig]       = useState(DEFAULT_CONFIG);

    const [status, setStatus]       = useState('idle');
    const [statusMsg, setStatusMsg] = useState('');

    // Conflict preview: { results: [{ adapter, analysis, remoteData }], serverUrl }
    const [preview, setPreview]     = useState(null);

    const autoTimerRef = useRef(null);
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

    // Auto-sync timer
    const doSyncRef = useRef(null);
    useEffect(() => {
        clearInterval(autoTimerRef.current);
        const serverUrl = normalizeServerUrl(config.serverUrl);
        if (config.autoSync && serverUrl) {
            autoTimerRef.current = setInterval(
                () => doSyncRef.current?.(serverUrl, true),
                (config.interval || 60) * 1000);
        }
        return () => clearInterval(autoTimerRef.current);
    }, [config.autoSync, config.interval, config.serverUrl]);

    const saveConfig = useCallback((next) => {
        setConfig(next);
        if (isElectron) window.electronAPI.saveLanConfig?.(next);
    }, [isElectron]);

    // ── 同步（含冲突预览） ────────────────────────────────────────────────
    const doSync = useCallback(async (serverUrl, skipPreview = false) => {
        const base = normalizeServerUrl(serverUrl);
        if (!base) { setStatus('error'); setStatusMsg('未配置服务器地址'); return; }
        setStatus('syncing'); setStatusMsg('');
        try {
            await syncClockOffset(base);
            const ads = adaptersRef.current;

            if (!skipPreview) {
                // 预览模式：拉取所有 adapter 的远端数据并做冲突分析
                const results = [];
                for (const a of ads) {
                    const localData = a._getLocal ? a._getLocal() : [];
                    const r = await fetchAndAnalyze(a, base, localData);
                    if (r) results.push(r);
                    else if (a.isRequired) throw new Error(`${a.type} 拉取失败`);
                }

                const hasChanges = results.some(r => {
                    const an = r.analysis;
                    return an.added.length + an.removed.length + an.updated.length +
                           an.deleted.length + an.conflicted.length > 0;
                });
                if (!hasChanges) {
                    setStatus('success'); setStatusMsg('数据完全一致，无需合并'); return;
                }
                setPreview({ results, serverUrl: base });
                setStatus('idle');
                return;
            }

            // 自动同步：直接合并
            await executeMerge(base, ads);
        } catch (e) { setStatus('error'); setStatusMsg(e.message); }
    }, []);

    useEffect(() => { doSyncRef.current = doSync; }, [doSync]);

    const executeMerge = useCallback(async (serverUrl, currentAdapters) => {
        const base = normalizeServerUrl(serverUrl);
        const ads = currentAdapters || adaptersRef.current;
        let totalMerged = 0;

        for (const a of ads) {
            const localData = a._getLocal ? a._getLocal() : [];
            const merged = await syncAndPush(a, base, localData);
            if (merged !== null) {
                if (a._writeLocal) a._writeLocal(merged);
                if (Array.isArray(merged)) totalMerged += merged.length;
            } else if (a.isRequired) {
                setStatus('error'); setStatusMsg(`${a.type} 同步失败`); return;
            }
        }

        setStatus('success');
        setStatusMsg(totalMerged > 0 ? `已同步 ${totalMerged} 条记录` : '同步完成');
        setPreview(null);
    }, []);

    const serverUrl = normalizeServerUrl(config.serverUrl);
    const statusColor = { idle: 'var(--clr-text-dim)', syncing: 'var(--clr-gold)', success: '#4A9DA8', error: 'var(--clr-red,#C0392B)' }[status];

    return {
        isElectron, open, setOpen, config, setConfig, saveConfig,
        status, statusMsg, statusColor, preview, setPreview,
        doSync, executeMerge, serverUrl,
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

    if (props.goals !== undefined || props.onMergeGoals) {
        const a = { ...BUILTIN_ADAPTERS.goals };
        a._getLocal  = () => props.goals || [];
        a._writeLocal = (m) => props.onMergeGoals?.(m);
        ads.push(a);
    }

    return ads;
}
