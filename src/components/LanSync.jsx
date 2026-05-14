import { useState, useEffect, useRef, useCallback } from 'react';
import { Wifi, RefreshCw, Server, Search, AlertTriangle, CheckCircle, X, ChevronDown, ChevronUp } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { format } from 'date-fns';

const DEFAULT_CONFIG = { peerIp: '', port: 37401, serverEnabled: false, autoSync: false, interval: 60 };

// ── 冲突分析（tombstone 感知）────────────────────────────────────────────────
function isAlive(e) { return !e.deletedAt; }

function analyzeConflict(local, remote) {
    const localMap  = new Map(local.map(e  => [e.id, e]));
    const remoteMap = new Map(remote.map(e => [e.id, e]));

    const results = { added: [], removed: [], updated: [], deleted: [], synced: [], conflicted: [] };

    for (const [id, re] of remoteMap) {
        const le = localMap.get(id);
        if (!le) {
            if (isAlive(re)) results.added.push(re);
            // else: remote tombstone for unknown id — no-op
        } else if ((re.updatedAt || 0) > (le.updatedAt || 0)) {
            if (!isAlive(re) && isAlive(le)) {
                results.deleted.push({ local: le, remote: re }); // remote deleted it
            } else {
                results.updated.push({ local: le, remote: re });
            }
        } else if ((re.updatedAt || 0) < (le.updatedAt || 0)) {
            if (!isAlive(le) && isAlive(re)) {
                results.deleted.push({ local: le, remote: re }); // local deleted it (local wins)
            } else {
                results.conflicted.push({ local: le, remote: re });
            }
        } else {
            results.synced.push(le);
        }
    }
    for (const [id, le] of localMap) {
        if (!remoteMap.has(id) && isAlive(le)) results.removed.push(le);
    }
    return results;
}

function mergeEvents(local, remote) {
    const map = new Map();
    for (const e of local)  map.set(e.id, e);
    for (const e of remote) {
        const ex = map.get(e.id);
        if (!ex || (e.updatedAt || 0) > (ex.updatedAt || 0)) map.set(e.id, e);
    }
    return Array.from(map.values());
}

// ── 子组件：已发现的服务器卡片 ────────────────────────────────────────────────
function ServerCard({ server, selected, onSelect }) {
    return (
        <button
            onClick={() => onSelect(server)}
            style={{
                display: 'flex', alignItems: 'center', gap: 10,
                padding: '8px 10px', borderRadius: 6, width: '100%',
                background: selected ? 'rgba(91,143,204,0.15)' : 'rgba(255,255,255,0.03)',
                border: `1px solid ${selected ? 'rgba(91,143,204,0.5)' : 'var(--clr-border,#333)'}`,
                cursor: 'pointer', textAlign: 'left', transition: 'all 120ms',
            }}
        >
            <Server size={14} style={{ color: selected ? 'var(--clr-blue,#5B8FCC)' : 'var(--clr-text-dim)', flexShrink: 0 }} />
            <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 12, color: 'var(--clr-text)', fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {server.name}
                </div>
                <div style={{ fontSize: 10, color: 'var(--clr-text-dim)', fontFamily: 'var(--font-mono)' }}>
                    {server.ip}:{server.port}
                </div>
            </div>
            <span style={{ fontSize: 10, color: 'var(--clr-text-dim)', flexShrink: 0 }}>
                {server.events} 条
            </span>
        </button>
    );
}

// ── 子组件：冲突预览弹窗 ──────────────────────────────────────────────────────
function ConflictModal({ analysis, peer, onConfirm, onCancel }) {
    const [showDetail, setShowDetail] = useState(false);
    const { added, removed, updated, deleted, conflicted, synced } = analysis;
    const hasChanges = added.length + removed.length + updated.length + deleted.length > 0;

    return (
        <div style={{
            position: 'fixed', inset: 0, zIndex: 1000,
            background: 'rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
            <div style={{
                width: 420, maxHeight: '80vh', overflow: 'auto',
                background: 'var(--clr-surface,#1e1e1e)',
                border: '1px solid var(--clr-border,#333)', borderRadius: 10,
                padding: 20, display: 'flex', flexDirection: 'column', gap: 16,
                boxShadow: '0 16px 48px rgba(0,0,0,0.6)',
            }}>
                {/* Header */}
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontFamily: 'var(--font-display)', fontSize: 13, letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--clr-text)' }}>
                        同步预览
                    </span>
                    <button onClick={onCancel} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--clr-text-dim)' }}>
                        <X size={16} />
                    </button>
                </div>

                {/* Peer info */}
                <div style={{ fontSize: 11, color: 'var(--clr-text-dim)', fontFamily: 'var(--font-mono)', padding: '6px 10px', background: 'rgba(255,255,255,0.04)', borderRadius: 4 }}>
                    {peer.name} · {peer.ip}:{peer.port} · {peer.events} 条事件
                </div>

                {/* Summary */}
                {!hasChanges ? (
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#4A9DA8' }}>
                        <CheckCircle size={15} />
                        <span style={{ fontSize: 13 }}>数据完全一致，无需合并</span>
                    </div>
                ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                        {added.length > 0      && <StatRow icon="↓" color="#5B8FCC" label={`从对端拉取 ${added.length} 条新事件`} />}
                        {removed.length > 0    && <StatRow icon="↑" color="#4A9DA8" label={`推送本地独有 ${removed.length} 条事件`} />}
                        {deleted.length > 0    && <StatRow icon="🗑" color="#A04040" label={`${deleted.length} 条事件将被删除（已在其中一端删除）`} />}
                        {updated.length > 0    && <StatRow icon="↻" color="#C9A84C" label={`${updated.length} 条事件将被对端较新版本覆盖`} />}
                        {conflicted.length > 0 && <StatRow icon="!" color="#C0392B" label={`${conflicted.length} 条事件本地版本更新（保留本地）`} />}
                        {synced.length > 0     && <StatRow icon="✓" color="#4A7C59" label={`${synced.length} 条已同步无变化`} />}
                    </div>
                )}

                {/* Detail toggle */}
                {hasChanges && (
                    <>
                        <button onClick={() => setShowDetail(v => !v)}
                            style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4, color: 'var(--clr-text-dim)', fontSize: 11, padding: 0 }}>
                            {showDetail ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
                            {showDetail ? '收起详情' : '查看详情'}
                        </button>

                        {showDetail && (
                            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxHeight: 260, overflow: 'auto' }}>
                                <EventGroup title="将从对端拉取" color="#5B8FCC" items={added} renderItem={e => e.title} />
                                <EventGroup title="将推送到对端" color="#4A9DA8" items={removed} renderItem={e => e.title} />
                                <EventGroup title="将被删除（tombstone 传播）" color="#A04040" items={deleted}
                                    renderItem={({ local: l, remote: r }) => (
                                        <span style={{ textDecoration: 'line-through', color: 'var(--clr-text-dim)' }}>
                                            {l.title}
                                            <span style={{ fontSize: 9, marginLeft: 6 }}>
                                                {(r.deletedAt || l.deletedAt) ? format(new Date(r.deletedAt || l.deletedAt), 'MM-dd HH:mm') + ' 删除' : ''}
                                            </span>
                                        </span>
                                    )}
                                />
                                <EventGroup title="将被对端版本覆盖" color="#C9A84C" items={updated}
                                    renderItem={({ local: l, remote: r }) => (
                                        <span>
                                            <span style={{ textDecoration: 'line-through', color: 'var(--clr-text-dim)', marginRight: 6 }}>{l.title}</span>
                                            → {r.title}
                                            <span style={{ fontSize: 9, color: 'var(--clr-text-dim)', marginLeft: 6 }}>
                                                {r.updatedAt ? format(new Date(r.updatedAt), 'MM-dd HH:mm') : ''}
                                            </span>
                                        </span>
                                    )}
                                />
                                <EventGroup title="本地版本更新（保留）" color="#C0392B" items={conflicted}
                                    renderItem={({ local: l }) => l.title}
                                />
                            </div>
                        )}
                    </>
                )}

                {/* Actions */}
                <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', paddingTop: 4 }}>
                    <button onClick={onCancel}
                        style={{ padding: '6px 14px', borderRadius: 4, background: 'none', border: '1px solid var(--clr-border,#333)', color: 'var(--clr-text-dim)', cursor: 'pointer', fontSize: 12 }}>
                        取消
                    </button>
                    <button onClick={onConfirm}
                        style={{ padding: '6px 14px', borderRadius: 4, background: 'var(--clr-blue,#5B8FCC)', border: 'none', color: '#fff', cursor: 'pointer', fontSize: 12, fontWeight: 600 }}>
                        {hasChanges ? '确认合并' : '完成'}
                    </button>
                </div>
            </div>
        </div>
    );
}

function StatRow({ icon, color, label }) {
    return (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--clr-text)' }}>
            <span style={{ width: 18, height: 18, borderRadius: 3, background: `${color}22`, color, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 10, fontWeight: 700, flexShrink: 0 }}>
                {icon}
            </span>
            {label}
        </div>
    );
}

function EventGroup({ title, color, items, renderItem }) {
    if (!items.length) return null;
    return (
        <div>
            <div style={{ fontSize: 10, color, letterSpacing: '0.08em', textTransform: 'uppercase', marginBottom: 4 }}>{title}</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                {items.map((item, i) => (
                    <div key={i} style={{ fontSize: 11, color: 'var(--clr-text)', fontFamily: 'var(--font-mono)', padding: '2px 6px', background: 'rgba(255,255,255,0.04)', borderRadius: 3 }}>
                        {renderItem(item)}
                    </div>
                ))}
            </div>
        </div>
    );
}

// ── 主组件 ────────────────────────────────────────────────────────────────────
export default function LanSync({ events, onMergeEvents }) {
    const { t } = useTranslation();
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
    const [preview, setPreview]     = useState(null);    // { analysis, remoteEvents }

    const autoTimerRef = useRef(null);
    const isElectron = typeof window !== 'undefined' && !!window.electronAPI;

    // Load config + local IP
    useEffect(() => {
        if (!isElectron) return;
        window.electronAPI.getLanConfig?.().then(cfg => { if (cfg) setConfig(c => ({ ...c, ...cfg })); });
        window.electronAPI.getLocalIp?.().then(ip => setLocalIp(ip || ''));
        const off1 = window.electronAPI.onLanEventsUpdated?.(raw => { onMergeEvents?.(raw); });
        const off2 = window.electronAPI.onLanServerError?.(msg => { setStatus('error'); setStatusMsg(msg); });
        return () => { off1?.(); off2?.(); };
    }, [isElectron]);

    // Auto-sync timer
    useEffect(() => {
        clearInterval(autoTimerRef.current);
        const peer = selected || (config.peerIp ? { ip: config.peerIp, port: config.port } : null);
        if (config.autoSync && peer) {
            autoTimerRef.current = setInterval(() => doSync(peer, true), (config.interval || 60) * 1000);
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
        const url = `http://${peer.ip}:${peer.port}/tplanner/events`;
        try {
            const res = await fetch(url, { method: 'GET', signal: AbortSignal.timeout(5000) });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const remoteEvents = await res.json();

            if (!skipPreview) {
                // 显示冲突预览让用户确认
                const analysis = analyzeConflict(events, remoteEvents);
                setPreview({ analysis, remoteEvents, peer });
                setStatus('idle');
                return;
            }

            await executeMerge(peer, remoteEvents);
        } catch (e) {
            setStatus('error'); setStatusMsg(e.message);
        }
    }, [events]);

    const executeMerge = useCallback(async (peer, remoteEvents) => {
        const url = `http://${peer.ip}:${peer.port}/tplanner/events`;
        const merged = mergeEvents(events, remoteEvents);
        await fetch(url, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(merged),
            signal: AbortSignal.timeout(5000),
        });
        onMergeEvents?.(merged);
        setStatus('success'); setStatusMsg(`已同步 ${merged.length} 条事件`);
        setPreview(null);
    }, [events, onMergeEvents]);

    const activePeer = selected ?? (config.peerIp ? { ip: config.peerIp, port: config.port, name: config.peerIp } : null);
    const statusColor = { idle: 'var(--clr-text-dim)', syncing: 'var(--clr-gold)', success: '#4A9DA8', error: 'var(--clr-red,#C0392B)' }[status];

    return (
        <>
            {/* 工具栏按钮 */}
            <div style={{ position: 'relative' }}>
                <button
                    className="btn btn--ghost"
                    onClick={() => setOpen(v => !v)}
                    title="局域网同步"
                    style={{ color: status === 'success' ? '#4A9DA8' : status === 'error' ? 'var(--clr-red,#C0392B)' : undefined }}
                >
                    {config.serverEnabled ? <Server size={13} /> : <Wifi size={13} />}
                </button>

                {open && (
                    <div style={{
                        position: 'absolute', top: '100%', right: 0, zIndex: 300,
                        width: 300, background: 'var(--clr-surface,#1e1e1e)',
                        border: '1px solid var(--clr-border,#333)', borderRadius: 8,
                        padding: 14, boxShadow: '0 8px 24px rgba(0,0,0,0.5)',
                        display: 'flex', flexDirection: 'column', gap: 12,
                    }}>
                        {/* 标题行 */}
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                            <span style={{ fontFamily: 'var(--font-display)', fontSize: 11, letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--clr-text-dim)' }}>
                                局域网同步
                            </span>
                            {localIp && (
                                <span style={{ fontSize: 10, color: 'var(--clr-text-dim)', fontFamily: 'var(--font-mono)' }}>
                                    本机 {localIp}
                                </span>
                            )}
                        </div>

                        {/* 扫描区域 */}
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                            <button
                                className="btn btn--ghost"
                                onClick={scan}
                                disabled={scanning}
                                style={{ display: 'flex', alignItems: 'center', gap: 6, justifyContent: 'center', width: '100%' }}
                            >
                                {scanning
                                    ? <><RefreshCw size={12} style={{ animation: 'spin 1s linear infinite' }} /> 扫描中…</>
                                    : <><Search size={12} /> 扫描局域网</>
                                }
                            </button>

                            {peers.length > 0 && (
                                <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                                    {peers.map(p => (
                                        <ServerCard
                                            key={`${p.ip}:${p.port}`}
                                            server={p}
                                            selected={selected?.ip === p.ip && selected?.port === p.port}
                                            onSelect={setSelected}
                                        />
                                    ))}
                                </div>
                            )}

                            {!scanning && peers.length === 0 && (
                                <div style={{ fontSize: 11, color: 'var(--clr-text-dim)', textAlign: 'center', padding: '4px 0' }}>
                                    未发现设备，可手动填写 IP
                                </div>
                            )}
                        </div>

                        {/* 手动 IP（备用） */}
                        <div style={{ display: 'flex', gap: 6 }}>
                            <input
                                type="text"
                                placeholder="192.168.x.x"
                                value={config.peerIp}
                                onChange={e => setConfig(c => ({ ...c, peerIp: e.target.value }))}
                                onBlur={() => saveConfig(config)}
                                style={{ ...inputStyle, flex: 1 }}
                            />
                            <input
                                type="number"
                                value={config.port}
                                onChange={e => setConfig(c => ({ ...c, port: Number(e.target.value) }))}
                                onBlur={() => saveConfig(config)}
                                style={{ ...inputStyle, width: 70 }}
                            />
                        </div>

                        {/* 开关 */}
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                            <Toggle label="开启本机服务" checked={config.serverEnabled} onChange={v => saveConfig({ ...config, serverEnabled: v })} />
                            <Toggle label="自动同步" checked={config.autoSync} onChange={v => saveConfig({ ...config, autoSync: v })} />
                            {config.autoSync && (
                                <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, color: 'var(--clr-text-dim)', paddingLeft: 4 }}>
                                    间隔
                                    <input type="number" value={config.interval} min={10}
                                        onChange={e => setConfig(c => ({ ...c, interval: Number(e.target.value) }))}
                                        onBlur={() => saveConfig(config)}
                                        style={{ ...inputStyle, width: 56 }}
                                    />
                                    秒
                                </div>
                            )}
                        </div>

                        {/* 状态 */}
                        {statusMsg && (
                            <span style={{ fontSize: 10, color: statusColor, fontFamily: 'var(--font-mono)' }}>
                                {statusMsg}
                            </span>
                        )}

                        {/* 同步按钮 */}
                        <button
                            className="btn btn--primary"
                            onClick={() => doSync(activePeer)}
                            disabled={status === 'syncing' || !activePeer}
                            style={{ display: 'flex', alignItems: 'center', gap: 6, justifyContent: 'center' }}
                        >
                            <RefreshCw size={12} style={status === 'syncing' ? { animation: 'spin 1s linear infinite' } : {}} />
                            {selected ? `同步 · ${selected.name}` : '立即同步'}
                        </button>
                    </div>
                )}
            </div>

            {/* 冲突预览弹窗 */}
            {preview && (
                <ConflictModal
                    analysis={preview.analysis}
                    peer={preview.peer}
                    onConfirm={() => executeMerge(preview.peer, preview.remoteEvents)}
                    onCancel={() => setPreview(null)}
                />
            )}
        </>
    );
}

function Toggle({ label, checked, onChange }) {
    return (
        <label style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', cursor: 'pointer', fontSize: 11, color: 'var(--clr-text)' }}>
            <span>{label}</span>
            <div onClick={() => onChange(!checked)}
                style={{ width: 32, height: 18, borderRadius: 9, background: checked ? 'var(--clr-blue,#5B8FCC)' : 'var(--clr-border,#333)', position: 'relative', transition: 'background 150ms', cursor: 'pointer' }}>
                <div style={{ position: 'absolute', top: 2, left: checked ? 16 : 2, width: 14, height: 14, borderRadius: '50%', background: '#fff', transition: 'left 150ms' }} />
            </div>
        </label>
    );
}

const inputStyle = {
    background: 'var(--clr-bg,#111)', border: '1px solid var(--clr-border,#333)',
    borderRadius: 4, color: 'var(--clr-text,#e0e0e0)', fontSize: 12,
    padding: '4px 8px', outline: 'none', width: '100%', fontFamily: 'var(--font-mono)',
};
