// LAN 同步的状态与流程：发现、连接、冲突预览、合并执行、自动同步、配置持久化。
// 抽离自 components/LanSync.jsx，使 UI 组件可以只关心渲染。
import { useState, useEffect, useRef, useCallback } from 'react';
import {
    DEFAULT_CONFIG,
    analyzeConflict, analyzeJournalConflict,
    mergeEvents, mergeJournals,
    syncClockOffset,
    getHistory, saveHistory,
} from '../utils/syncLogic';

export default function useLanSync({ events, onMergeEvents, journals, onMergeJournals, goals, onMergeGoals }) {
    const [open, setOpen]           = useState(false);
    const [config, setConfig]       = useState(DEFAULT_CONFIG);
    const [localIp, setLocalIp]     = useState('');

    // Discovery
    const [scanning, setScanning]   = useState(false);
    const [peers, setPeers]         = useState([]);      // discovered servers
    const [selected, setSelected]   = useState(null);    // chosen server

    // Sync state
    const [status, setStatus]       = useState('idle');  // idle|syncing|success|error
    const [statusMsg, setStatusMsg] = useState('');

    // Conflict preview
    const [preview, setPreview]     = useState(null);    // { analysis, journalAnalysis, goalAnalysis, remoteEvents, peer }

    const autoTimerRef = useRef(null);
    const isElectron = typeof window !== 'undefined' && !!window.electronAPI;

    // Always-current refs — avoids stale closures in async callbacks and timers
    const eventsRef   = useRef(events);
    const journalsRef = useRef(journals);
    const goalsRef    = useRef(goals);
    useEffect(() => { eventsRef.current   = events;   }, [events]);
    useEffect(() => { journalsRef.current = journals; }, [journals]);
    useEffect(() => { goalsRef.current    = goals;    }, [goals]);

    // Load config + local IP
    useEffect(() => {
        if (!isElectron) return;
        window.electronAPI.getLanConfig?.().then(cfg => { if (cfg) setConfig(c => ({ ...c, ...cfg })); });
        window.electronAPI.getLocalIp?.().then(ip => setLocalIp(ip || ''));
        const off1 = window.electronAPI.onLanEventsUpdated?.(raw => { onMergeEvents?.(raw); });
        const off2 = window.electronAPI.onLanServerError?.(msg => { setStatus('error'); setStatusMsg(msg); });
        return () => { off1?.(); off2?.(); };
    }, [isElectron]);

    // Auto-sync timer — uses ref so it always sees the latest doSync/events
    const doSyncRef = useRef(null);
    useEffect(() => {
        clearInterval(autoTimerRef.current);
        const peer = selected || (config.peerIp ? { ip: config.peerIp, port: config.port } : null);
        if (config.autoSync && peer) {
            autoTimerRef.current = setInterval(() => doSyncRef.current?.(peer, true), (config.interval || 60) * 1000);
        }
        return () => clearInterval(autoTimerRef.current);
    }, [config.autoSync, config.interval, selected]);

    const saveConfig = useCallback((next) => {
        setConfig(next);
        if (isElectron) window.electronAPI.saveLanConfig?.(next);
    }, [isElectron]);

    // ── 局域网扫描 ───────────────────────────────────────────────────────────
    const scan = useCallback(async () => {
        if (!isElectron) return;
        setScanning(true);
        setPeers([]);
        setSelected(null);
        try {
            const found = await window.electronAPI.discoverLan?.() ?? [];
            setPeers(found);
            if (found.length === 1) setSelected(found[0]); // 只有一个时自动选中
        } catch (e) {
            setStatusMsg(e.message);
        } finally {
            setScanning(false);
        }
    }, [isElectron]);

    // ── 同步（含冲突预览） ────────────────────────────────────────────────────
    const doSync = useCallback(async (peer, skipPreview = false) => {
        if (!peer?.ip || !peer?.port) {
            setStatus('error'); setStatusMsg('未选择同步目标'); return;
        }
        setStatus('syncing'); setStatusMsg('');
        const base = `http://${peer.ip}:${peer.port}`;
        try {
            await syncClockOffset(base);

            const res = await fetch(`${base}/tplanner/events`, { method: 'GET', signal: AbortSignal.timeout(5000) });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const remoteEvents = await res.json();

            if (!skipPreview) {
                // 同时拉取 journals/goals，让预览能展示三类数据各自的变更
                let remoteJournals = {};
                let remoteGoals = [];
                try {
                    const jRes = await fetch(`${base}/tplanner/journals`, { method: 'GET', signal: AbortSignal.timeout(5000) });
                    if (jRes.ok) remoteJournals = await jRes.json();
                } catch (_) { /* journals preview is best-effort */ }
                try {
                    const gRes = await fetch(`${base}/tplanner/goals`, { method: 'GET', signal: AbortSignal.timeout(5000) });
                    if (gRes.ok) remoteGoals = await gRes.json();
                } catch (_) { /* goals preview is best-effort */ }

                // Use refs for latest local data to avoid stale analysis
                const analysis        = analyzeConflict(eventsRef.current, remoteEvents);
                const journalAnalysis = analyzeJournalConflict(journalsRef.current, remoteJournals);
                const goalAnalysis    = analyzeConflict(goalsRef.current, remoteGoals);
                setPreview({ analysis, journalAnalysis, goalAnalysis, remoteEvents, peer });
                setStatus('idle');
                return;
            }

            await executeMerge(peer, remoteEvents);
        } catch (e) {
            setStatus('error'); setStatusMsg(e.message);
        }
    }, []);  // no deps — always reads from refs

    // Keep doSyncRef current so the auto-sync timer always uses the latest version
    useEffect(() => { doSyncRef.current = doSync; }, [doSync]);

    // 启动时后台自动连接历史服务器
    useEffect(() => {
        if (!isElectron) return;
        const historyKeys = new Set(getHistory().map(h => `${h.ip}:${h.port}`));
        window.electronAPI.discoverLan?.().then(found => {
            if (!found?.length) return;
            const target = found.find(p => historyKeys.size === 0 || historyKeys.has(`${p.ip}:${p.port}`));
            if (!target) return;
            setSelected(target);
            doSyncRef.current?.(target, true);
        }).catch(() => {});
    }, [isElectron]);

    const executeMerge = useCallback(async (peer, remoteEvents) => {
        const base = `http://${peer.ip}:${peer.port}`;
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
            signal: AbortSignal.timeout(5000),
        });
        onMergeEvents?.(mergedEvents);

        // ── Journals ─────────────────────────────────────────────────────────
        try {
            const jRes = await fetch(`${base}/tplanner/journals`, { method: 'GET', signal: AbortSignal.timeout(5000) });
            if (jRes.ok) {
                const remoteJournals = await jRes.json();
                const mergedJournals = mergeJournals(localJournals, remoteJournals);
                await fetch(`${base}/tplanner/journals`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(mergedJournals),
                    signal: AbortSignal.timeout(5000),
                });
                onMergeJournals?.(mergedJournals);
            }
        } catch (_) { /* journals sync failure is non-fatal */ }

        // ── Goals ────────────────────────────────────────────────────────────
        try {
            const gRes = await fetch(`${base}/tplanner/goals`, { method: 'GET', signal: AbortSignal.timeout(5000) });
            if (gRes.ok) {
                const remoteGoals  = await gRes.json();
                const mergedGoals  = mergeEvents(localGoals, remoteGoals); // same updatedAt-wins logic
                await fetch(`${base}/tplanner/goals`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(mergedGoals),
                    signal: AbortSignal.timeout(5000),
                });
                onMergeGoals?.(mergedGoals);
            }
        } catch (_) { /* goals sync failure is non-fatal */ }

        saveHistory(peer);
        setStatus('success');
        setStatusMsg(`已同步 ${mergedEvents.length} 条事件`);
        setPreview(null);
    }, [onMergeEvents, onMergeJournals, onMergeGoals]);  // refs are stable, no need as deps

    const activePeer = selected ?? (config.peerIp ? { ip: config.peerIp, port: config.port, name: config.peerIp } : null);
    const statusColor = { idle: 'var(--clr-text-dim)', syncing: 'var(--clr-gold)', success: '#4A9DA8', error: 'var(--clr-red,#C0392B)' }[status];

    return {
        isElectron,
        open, setOpen,
        config, setConfig, saveConfig,
        localIp,
        scanning, peers, selected, setSelected, scan,
        status, statusMsg, statusColor,
        preview, setPreview,
        doSync, executeMerge,
        activePeer,
    };
}
