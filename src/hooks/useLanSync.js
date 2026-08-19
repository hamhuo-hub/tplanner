// V3 同步状态与流程(见 docs/sync-v3.md §16)。
//
// 客户端不再三方合并、不再人工裁决冲突:
//   本地改动 → diff 成语义命令 → 命令 outbox → 批次上传 → 中央整合
//   → 版本通知 → 下载并原子安装快照 → 写回展示数据。
// 权威永远在中央;本地 UI 状态只是 Server Mirror + Pending Overlay 的投影。
import { useState, useEffect, useRef, useCallback } from 'react';
import { DEFAULT_CONFIG, normalizeServerUrl } from '../utils/syncLogic';
import { createSyncEngine } from '../syncV3/createSyncEngine';
import { appendCommands } from '../syncV3/commandOutbox';
import { diffEventsToCommands, diffJournalsToCommands, toLegacyEvents, toLegacyJournals } from '../syncV3/commandsFromData';

export default function useLanSync(props = {}) {
    const adapters = props.adapters ?? [];

    const [open, setOpen] = useState(false);
    const [config, setConfig] = useState(DEFAULT_CONFIG);
    const [status, setStatus] = useState('idle');
    const [statusMsg, setStatusMsg] = useState('');
    const [lastVersion, setLastVersion] = useState(0);

    const isElectron = typeof window !== 'undefined' && !!window.electronAPI;
    const engineRef = useRef(null);
    const adaptersRef = useRef(adapters);
    useEffect(() => { adaptersRef.current = adapters; }, [adapters]);
    const configRef = useRef(config);
    useEffect(() => { configRef.current = config; }, [config]);

    const saveConfig = useCallback((next) => {
        setConfig(next);
        if (isElectron) window.electronAPI.saveLanConfig?.(next);
    }, [isElectron]);

    // 引擎单例:随 serverUrl 重建;启动即开始版本通知监听
    useEffect(() => {
        if (!isElectron) return;
        const base = normalizeServerUrl(configRef.current.serverUrl);
        if (!base) return;
        let disposed = false;

        createSyncEngine({ serverUrl: base }).then((engine) => {
            if (disposed) return;
            engineRef.current = engine;
            engine.notifications.start();
            engine.installer.syncToLatest()
                .then((r) => { if (r.installed) setLastVersion(r.version); })
                .catch(() => { /* 首次拉取失败由下次通知/手动同步补偿 */ });
        });

        return () => {
            disposed = true;
            engineRef.current?.notifications.stop();
            engineRef.current = null;
        };
    }, [isElectron, config.serverUrl]);

    // 手动/自动同步:本地差异 → 命令 → 上传 → 快照 → 写回展示数据
    const doSync = useCallback(async (serverUrl) => {
        const base = normalizeServerUrl(serverUrl);
        if (!base) { setStatus('error'); setStatusMsg('未配置服务器地址'); return; }
        const engine = engineRef.current;
        if (!engine) { setStatus('error'); setStatusMsg('同步引擎未就绪'); return; }

        setStatus('syncing'); setStatusMsg('');
        try {
            const mirror = (await engine.installer.getServerMirror())
                ?? { tasks: {}, customLists: {}, journals: {}, goals: {}, insights: {} };

            for (const a of adaptersRef.current) {
                const local = a._getLocal ? await a._getLocal() : [];
                const commands = a.type === 'journals'
                    ? diffJournalsToCommands(mirror, local)
                    : diffEventsToCommands(mirror, local);
                if (commands.length > 0) await appendCommands(engine.store, commands);
            }

            await engine.uploader.flush();
            const installed = await engine.installer.syncToLatest();
            const display = (await engine.installer.getDisplayState())
                ?? (await engine.installer.getServerMirror())
                ?? { tasks: {}, journals: {} };

            for (const a of adaptersRef.current) {
                if (!a._writeLocal) continue;
                if (a.type === 'journals') await a._writeLocal(toLegacyJournals(display));
                else await a._writeLocal(toLegacyEvents(display));
            }

            setLastVersion(installed.version);
            setStatus('success');
            setStatusMsg(`已同步至 v${installed.version}`);
        } catch (e) {
            setStatus('error');
            setStatusMsg(e?.message || String(e));
        }
    }, []);

    const doSyncRef = useRef(null);
    useEffect(() => { doSyncRef.current = doSync; }, [doSync]);

    // 本地操作完成后,只对受影响数据集排队一次同步
    const queueAutomaticSync = useCallback((serverUrl) => {
        engineRef.current || null;
        doSyncRef.current?.(serverUrl);
    }, []);

    useEffect(() => {
        if (!isElectron || !props.syncRequest?.sequence) return;
        const base = normalizeServerUrl(configRef.current.serverUrl);
        if (!base) return;
        queueAutomaticSync(base);
    }, [isElectron, props.syncRequest?.sequence, config.serverUrl, queueAutomaticSync]);

    const serverUrl = normalizeServerUrl(config.serverUrl);
    const statusColor = { idle: 'var(--clr-text-dim)', syncing: 'var(--clr-gold)', success: '#4A9DA8', error: 'var(--clr-red,#C0392B)' }[status];

    return {
        isElectron, open, setOpen, config, setConfig, saveConfig,
        status, statusMsg, statusColor, doSync, serverUrl, lastVersion,
    };
}
