// 同步的状态与流程：连接固定服务器、冲突预览、合并执行、自动同步、配置持久化。
// 抽离自 components/LanSync.jsx，使 UI 组件可以只关心渲染。
// 同步目标是固定地址的服务器（Cloudflare Tunnel），不再做局域网扫描/发现。
import { useState, useEffect, useRef, useCallback } from 'react';
import {
    DEFAULT_CONFIG, DEFAULT_SERVER_URL, normalizeServerUrl,
    analyzeConflict, analyzeGoalConflict, analyzeJournalConflict,
    mergeEvents, mergeGoals, mergeJournals,
    syncClockOffset,
} from '../utils/syncLogic';

export default function useLanSync({ events, onMergeEvents, journals, onMergeJournals, goals, onMergeGoals }) {
    const [open, setOpen]           = useState(false);
    const [config, setConfig]       = useState(DEFAULT_CONFIG);

    // Sync state
    const [status, setStatus]       = useState('idle');  // idle|syncing|success|error
    const [statusMsg, setStatusMsg] = useState('');

    // Conflict preview
    const [preview, setPreview]     = useState(null);    // { analysis, journalAnalysis, goalAnalysis, remoteEvents, serverUrl }

    const autoTimerRef = useRef(null);
    const isElectron = typeof window !== 'undefined' && !!window.electronAPI;

    // Always-current refs — avoids stale closures in async callbacks and timers
    const eventsRef   = useRef(events);
    const journalsRef = useRef(journals);
    const goalsRef    = useRef(goals);
    useEffect(() => { eventsRef.current   = events;   }, [events]);
    useEffect(() => { journalsRef.current = journals; }, [journals]);
    useEffect(() => { goalsRef.current    = goals;    }, [goals]);

    // Load config, then sync with the configured server once on startup
    useEffect(() => {
        if (!isElectron) return;
        window.electronAPI.getLanConfig?.().then(cfg => {
            // 旧版配置只有 peerIp/port（IPv6 直连时代），统一迁移到固定服务器地址
            const serverUrl = normalizeServerUrl(cfg?.serverUrl) || DEFAULT_SERVER_URL;
            setConfig(c => ({ ...c, ...cfg, serverUrl }));
            doSyncRef.current?.(serverUrl, true);
        });
    }, [isElectron]);

    // Auto-sync timer — uses ref so it always sees the latest doSync/events
    const doSyncRef = useRef(null);
    useEffect(() => {
        clearInterval(autoTimerRef.current);
        const serverUrl = normalizeServerUrl(config.serverUrl);
        if (config.autoSync && serverUrl) {
            autoTimerRef.current = setInterval(() => doSyncRef.current?.(serverUrl, true), (config.interval || 60) * 1000);
        }
        return () => clearInterval(autoTimerRef.current);
    }, [config.autoSync, config.interval, config.serverUrl]);

    const saveConfig = useCallback((next) => {
        setConfig(next);
        if (isElectron) window.electronAPI.saveLanConfig?.(next);
    }, [isElectron]);

    // ── 同步（含冲突预览） ────────────────────────────────────────────────────
    const doSync = useCallback(async (serverUrl, skipPreview = false) => {
        const base = normalizeServerUrl(serverUrl);
        if (!base) {
            setStatus('error'); setStatusMsg('未配置服务器地址'); return;
        }
        setStatus('syncing'); setStatusMsg('');
        try {
            await syncClockOffset(base);

            const res = await fetch(`${base}/tplanner/events`, { method: 'GET', signal: AbortSignal.timeout(10000) });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const remoteEvents = await res.json();

            if (!skipPreview) {
                // 同时拉取 journals/goals，让预览能展示三类数据各自的变更
                let remoteJournals = {};
                let remoteGoals = [];
                try {
                    const jRes = await fetch(`${base}/tplanner/journals`, { method: 'GET', signal: AbortSignal.timeout(10000) });
                    if (jRes.ok) remoteJournals = await jRes.json();
                } catch (_) { /* journals preview is best-effort */ }
                try {
                    const gRes = await fetch(`${base}/tplanner/goals`, { method: 'GET', signal: AbortSignal.timeout(10000) });
                    if (gRes.ok) remoteGoals = await gRes.json();
                } catch (_) { /* goals preview is best-effort */ }

                // Use refs for latest local data to avoid stale analysis
                const analysis        = analyzeConflict(eventsRef.current, remoteEvents);
                const journalAnalysis = analyzeJournalConflict(journalsRef.current, remoteJournals);
                const goalAnalysis    = analyzeGoalConflict(goalsRef.current, remoteGoals);
                setPreview({ analysis, journalAnalysis, goalAnalysis, remoteEvents, serverUrl: base });
                setStatus('idle');
                return;
            }

            await executeMerge(base, remoteEvents);
        } catch (e) {
            setStatus('error'); setStatusMsg(e.message);
        }
    }, []);  // no deps — always reads from refs

    // Keep doSyncRef current so the auto-sync timer always uses the latest version
    useEffect(() => { doSyncRef.current = doSync; }, [doSync]);

    const executeMerge = useCallback(async (serverUrl, remoteEvents) => {
        const base = normalizeServerUrl(serverUrl);
        // Read from refs to always get the latest data, even if called from a stale closure
        const localEvents   = eventsRef.current;
        const localJournals = journalsRef.current;
        const localGoals    = goalsRef.current;

        // ── Events ──────────────────────────────────────────────────────────
        const mergedEvents = mergeEvents(localEvents, remoteEvents);
        await fetch(`${base}/tplanner/events`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(mergedEvents),
            signal: AbortSignal.timeout(10000),
        });
        onMergeEvents?.(mergedEvents);

        // ── Journals ─────────────────────────────────────────────────────────
        try {
            const jRes = await fetch(`${base}/tplanner/journals`, { method: 'GET', signal: AbortSignal.timeout(10000) });
            if (jRes.ok) {
                const remoteJournals = await jRes.json();
                const mergedJournals = mergeJournals(localJournals, remoteJournals);
                await fetch(`${base}/tplanner/journals`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(mergedJournals),
                    signal: AbortSignal.timeout(10000),
                });
                onMergeJournals?.(mergedJournals);
            }
        } catch (_) { /* journals sync failure is non-fatal */ }

        // ── Goals ────────────────────────────────────────────────────────────
        try {
            const gRes = await fetch(`${base}/tplanner/goals`, { method: 'GET', signal: AbortSignal.timeout(10000) });
            if (gRes.ok) {
                const remoteGoals  = await gRes.json();
                const mergedGoals  = mergeGoals(localGoals, remoteGoals);
                await fetch(`${base}/tplanner/goals`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(mergedGoals),
                    signal: AbortSignal.timeout(10000),
                });
                onMergeGoals?.(mergedGoals);
            }
        } catch (_) { /* goals sync failure is non-fatal */ }

        setStatus('success');
        setStatusMsg(`已同步 ${mergedEvents.length} 条事件`);
        setPreview(null);
    }, [onMergeEvents, onMergeJournals, onMergeGoals]);  // refs are stable, no need as deps

    const serverUrl = normalizeServerUrl(config.serverUrl);
    const statusColor = { idle: 'var(--clr-text-dim)', syncing: 'var(--clr-gold)', success: '#4A9DA8', error: 'var(--clr-red,#C0392B)' }[status];

    return {
        isElectron,
        open, setOpen,
        config, setConfig, saveConfig,
        status, statusMsg, statusColor,
        preview, setPreview,
        doSync, executeMerge,
        serverUrl,
    };
}
