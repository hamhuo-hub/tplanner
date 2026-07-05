import { useState } from 'react';
import { Wifi, RefreshCw, CheckCircle, X, ChevronDown, ChevronUp } from 'lucide-react';
import useLanSync from '../hooks/useLanSync';

// ── 子组件：冲突预览弹窗 ──────────────────────────────────────────────────────

const TYPE_LABELS = { events: '事件', goals: '目标', journals: '日志', insights: '洞察' };
function adapterTitle(a) { return TYPE_LABELS[a.type] || a.type; }

function ConflictSection({ adapter, analysis }) {
    const [showDetail, setShowDetail] = useState(false);
    const { added, removed, updated, deleted, conflicted, synced } = analysis;
    const hasChanges = added.length + removed.length + updated.length + deleted.length + conflicted.length > 0;
    const labelFn = adapter.itemLabel || (item => item?.title ?? '');
    const unit = adapter.unitName || '条';

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, paddingTop: 12, borderTop: '1px solid var(--clr-border,#333)' }}>
            <span style={{ fontFamily: 'var(--font-display)', fontSize: 11, letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--clr-text-dim)' }}>
                {adapterTitle(adapter)}
            </span>

            {!hasChanges ? (
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#4A9DA8' }}>
                    <CheckCircle size={14} />
                    <span style={{ fontSize: 12 }}>{synced.length > 0 ? `${synced.length} ${unit}已同步，无需合并` : '数据完全一致，无需合并'}</span>
                </div>
            ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    {added.length > 0      && <StatRow icon="↓" color="#5B8FCC" label={`从对端拉取 ${added.length} ${unit}`} />}
                    {removed.length > 0    && <StatRow icon="↑" color="#4A9DA8" label={`推送本地独有 ${removed.length} ${unit}`} />}
                    {deleted.length > 0    && <StatRow icon="🗑" color="#A04040" label={`${deleted.length} ${unit}将被删除（一端已删除）`} />}
                    {conflicted.length > 0 && <StatRow icon="⚡" color="#C0392B" label={`${conflicted.length} ${unit}内容冲突，需手动选择`} />}
                    {synced.length > 0     && <StatRow icon="✓" color="#4A7C59" label={`${synced.length} ${unit}已同步`} />}
                </div>
            )}

            {hasChanges && (
                <>
                    <button onClick={() => setShowDetail(v => !v)}
                        style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4, color: 'var(--clr-text-dim)', fontSize: 11, padding: 0 }}>
                        {showDetail ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
                        {showDetail ? '收起详情' : '查看详情'}
                    </button>
                    {showDetail && (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxHeight: 220, overflow: 'auto' }}>
                            <EventGroup title="将从对端拉取" color="#5B8FCC" items={added} renderItem={labelFn} />
                            <EventGroup title="将推送到对端" color="#4A9DA8" items={removed} renderItem={labelFn} />
                            <EventGroup title="将被删除（一端已删除）" color="#A04040" items={deleted}
                                renderItem={({ local: l }) => (
                                    <span style={{ textDecoration: 'line-through', color: 'var(--clr-text-dim)' }}>{labelFn(l)}</span>
                                )} />
                            <EventGroup title="内容冲突（需手动选择保留哪个版本）" color="#C0392B" items={conflicted}
                                renderItem={({ local: l, remote: r }) => (
                                    <span>
                                        <span style={{ textDecoration: 'line-through', color: 'var(--clr-text-dim)', marginRight: 6 }}>{labelFn(l)}</span>
                                        → {labelFn(r)}
                                    </span>
                                )} />
                        </div>
                    )}
                </>
            )}
        </div>
    );
}

function ConflictModal({ results, serverUrl, onConfirm, onCancel }) {
    const hasAnyChanges = results.some(r => {
        const a = r.analysis;
        return a.added.length + a.removed.length + a.deleted.length + a.conflicted.length > 0;
    });

    return (
        <div style={{ position: 'fixed', inset: 0, zIndex: 1000, background: 'rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <div style={{ width: 440, maxHeight: '85vh', overflow: 'auto', background: 'var(--clr-surface,#1e1e1e)', border: '1px solid var(--clr-border,#333)', borderRadius: 10, padding: 20, display: 'flex', flexDirection: 'column', gap: 12, boxShadow: '0 16px 48px rgba(0,0,0,0.6)' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontFamily: 'var(--font-display)', fontSize: 13, letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--clr-text)' }}>同步预览</span>
                    <button onClick={onCancel} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--clr-text-dim)' }}><X size={16} /></button>
                </div>
                <div style={{ fontSize: 11, color: 'var(--clr-text-dim)', fontFamily: 'var(--font-mono)', padding: '6px 10px', background: 'rgba(255,255,255,0.04)', borderRadius: 4 }}>{serverUrl}</div>

                {!hasAnyChanges && (
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#4A9DA8' }}>
                        <CheckCircle size={15} /><span style={{ fontSize: 13 }}>数据完全一致，无需合并</span>
                    </div>
                )}

                {results.map(r => (
                    <ConflictSection key={r.adapter.type} adapter={r.adapter} analysis={r.analysis} />
                ))}

                <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', paddingTop: 4 }}>
                    <button onClick={onCancel} style={{ padding: '6px 14px', borderRadius: 4, background: 'none', border: '1px solid var(--clr-border,#333)', color: 'var(--clr-text-dim)', cursor: 'pointer', fontSize: 12 }}>取消</button>
                    <button onClick={onConfirm} style={{ padding: '6px 14px', borderRadius: 4, background: 'var(--clr-blue,#5B8FCC)', border: 'none', color: '#fff', cursor: 'pointer', fontSize: 12, fontWeight: 600 }}>{hasAnyChanges ? '确认合并' : '完成'}</button>
                </div>
            </div>
        </div>
    );
}

function StatRow({ icon, color, label }) {
    return (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--clr-text)' }}>
            <span style={{ width: 18, height: 18, borderRadius: 3, background: `${color}22`, color, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 10, fontWeight: 700, flexShrink: 0 }}>{icon}</span>
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
                    <div key={i} style={{ fontSize: 11, color: 'var(--clr-text)', fontFamily: 'var(--font-mono)', padding: '2px 6px', background: 'rgba(255,255,255,0.04)', borderRadius: 3 }}>{renderItem(item)}</div>
                ))}
            </div>
        </div>
    );
}

// ── 主组件 ────────────────────────────────────────────────────────────────────
export default function LanSync(props) {
    const sync = useLanSync(props);
    const { isElectron, open, setOpen, config, setConfig, saveConfig, status, statusMsg, statusColor, preview, setPreview, doSync, executeMerge, serverUrl } = sync;

    return (
        <>
            <div style={{ position: 'relative' }}>
                <button className="btn btn--ghost" onClick={() => setOpen(v => !v)} title="同步"
                    style={{ color: status === 'success' ? '#4A9DA8' : status === 'error' ? 'var(--clr-red,#C0392B)' : undefined }}>
                    <Wifi size={13} />
                </button>

                {open && (
                    <div style={{ position: 'absolute', top: '100%', right: 0, zIndex: 300, width: 300, background: 'var(--clr-surface,#1e1e1e)', border: '1px solid var(--clr-border,#333)', borderRadius: 8, padding: 14, boxShadow: '0 8px 24px rgba(0,0,0,0.5)', display: 'flex', flexDirection: 'column', gap: 12 }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                            <span style={{ fontFamily: 'var(--font-display)', fontSize: 11, letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--clr-text-dim)' }}>同步服务器</span>
                        </div>
                        <input type="text" placeholder="https://sync.hamhuo.top" value={config.serverUrl}
                            onChange={e => setConfig(c => ({ ...c, serverUrl: e.target.value }))}
                            onBlur={() => saveConfig(config)} style={inputStyle} />
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                            <Toggle label="自动同步" checked={config.autoSync} onChange={v => saveConfig({ ...config, autoSync: v })} />
                            {config.autoSync && (
                                <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, color: 'var(--clr-text-dim)', paddingLeft: 4 }}>
                                    间隔 <input type="number" value={config.interval} min={10}
                                        onChange={e => setConfig(c => ({ ...c, interval: Number(e.target.value) }))}
                                        onBlur={() => saveConfig(config)} style={{ ...inputStyle, width: 56 }} /> 秒
                                </div>
                            )}
                        </div>
                        {statusMsg && <span style={{ fontSize: 10, color: statusColor, fontFamily: 'var(--font-mono)' }}>{statusMsg}</span>}
                        <button className="btn btn--primary" onClick={() => doSync(serverUrl)} disabled={status === 'syncing' || !serverUrl}
                            style={{ display: 'flex', alignItems: 'center', gap: 6, justifyContent: 'center' }}>
                            <RefreshCw size={12} style={status === 'syncing' ? { animation: 'spin 1s linear infinite' } : {}} />
                            立即同步
                        </button>
                    </div>
                )}
            </div>

            {preview && (
                <ConflictModal results={preview.results} serverUrl={preview.serverUrl}
                    onConfirm={() => executeMerge(preview.serverUrl)}
                    onCancel={() => setPreview(null)} />
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
