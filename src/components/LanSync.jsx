import { useState, useMemo } from 'react';
import { Wifi, RefreshCw, CheckCircle, X, ChevronDown, ChevronUp } from 'lucide-react';
import useLanSync from '../hooks/useLanSync';

// ── 子组件：冲突预览弹窗 ──────────────────────────────────────────────────────

const TYPE_LABELS = { events: '事件', goals: '目标', journals: '日志', insights: '洞察' };
function adapterTitle(a) { return TYPE_LABELS[a.type] || a.type; }

function fmtTs(ts) {
    if (!ts) return '—';
    const d = new Date(ts);
    return `${d.getMonth() + 1}-${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

// 人工裁决行：两边各一个可点卡片，选中高亮
function ManualConflictRow({ pair, labelFn, choice, onChoose }) {
    const side = (key, item, title) => {
        const selected = choice === key;
        return (
            <button onClick={() => onChoose(key)}
                style={{
                    flex: 1, textAlign: 'left', cursor: 'pointer', borderRadius: 4, padding: '6px 8px',
                    background: selected ? 'rgba(91,143,204,0.18)' : 'rgba(255,255,255,0.03)',
                    border: `1px solid ${selected ? 'var(--clr-blue,#5B8FCC)' : 'var(--clr-border,#333)'}`,
                    display: 'flex', flexDirection: 'column', gap: 2,
                }}>
                <span style={{ fontSize: 9, letterSpacing: '0.08em', color: selected ? 'var(--clr-blue,#5B8FCC)' : 'var(--clr-text-dim)' }}>
                    {title} · {fmtTs(item?.updatedAt)}{item?.deletedAt ? ' · 已删除' : ''}
                </span>
                <span style={{ fontSize: 11, color: 'var(--clr-text)', fontFamily: 'var(--font-mono)',
                    textDecoration: item?.deletedAt ? 'line-through' : 'none',
                    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {labelFn(item) || '(空)'}
                </span>
            </button>
        );
    };
    return (
        <div style={{ display: 'flex', gap: 6, alignItems: 'stretch' }}>
            {side('local', pair.local, '本机')}
            {side('remote', pair.remote, '服务器')}
        </div>
    );
}

function ConflictSection({ adapter, analysis, choices, onChoose }) {
    const [showDetail, setShowDetail] = useState(false);
    const { added, removed, updated, deleted, conflicted, synced, manual } = analysis;
    const hasChanges = added.length + removed.length + updated.length + deleted.length + conflicted.length + manual.length > 0;
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
                    {added.length > 0      && <StatRow icon="↓" color="#5B8FCC" label={`从服务器拉取 ${added.length} ${unit}`} />}
                    {removed.length > 0    && <StatRow icon="↑" color="#4A9DA8" label={`推送本地独有 ${removed.length} ${unit}`} />}
                    {updated.length > 0    && <StatRow icon="↓" color="#C9A84C" label={`服务器已修改，采用 ${updated.length} ${unit}`} />}
                    {conflicted.length > 0 && <StatRow icon="↑" color="#4A9DA8" label={`本机已修改，推送 ${conflicted.length} ${unit}`} />}
                    {deleted.length > 0    && <StatRow icon="🗑" color="#A04040" label={`${deleted.length} ${unit}将被删除（一端已删除）`} />}
                    {synced.length > 0     && <StatRow icon="✓" color="#4A7C59" label={`${synced.length} ${unit}已同步`} />}
                </div>
            )}

            {/* 人工裁决区：两边都改过的记录，逐条选择保留哪边 */}
            {manual.length > 0 && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <StatRow icon="⚡" color="#C0392B" label={`${manual.length} ${unit}两边都有修改，请逐条选择保留哪边`} />
                    {manual.map(pair => (
                        <ManualConflictRow key={pair.id} pair={pair} labelFn={labelFn}
                            choice={choices[`${adapter.type}::${pair.id}`]}
                            onChoose={c => onChoose(adapter.type, pair.id, c)} />
                    ))}
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
                            <EventGroup title="将从服务器拉取" color="#5B8FCC" items={added} renderItem={labelFn} />
                            <EventGroup title="将推送到服务器" color="#4A9DA8" items={removed} renderItem={labelFn} />
                            <EventGroup title="服务器已修改（采用服务器版）" color="#C9A84C" items={updated}
                                renderItem={({ local: l, remote: r }) => (
                                    <span>
                                        <span style={{ textDecoration: 'line-through', color: 'var(--clr-text-dim)', marginRight: 6 }}>{labelFn(l)}</span>
                                        → {labelFn(r)}
                                    </span>
                                )} />
                            <EventGroup title="本机已修改（推送本机版）" color="#4A9DA8" items={conflicted}
                                renderItem={({ local: l }) => labelFn(l)} />
                            <EventGroup title="将被删除（一端已删除）" color="#A04040" items={deleted}
                                renderItem={({ local: l }) => (
                                    <span style={{ textDecoration: 'line-through', color: 'var(--clr-text-dim)' }}>{labelFn(l)}</span>
                                )} />
                        </div>
                    )}
                </>
            )}
        </div>
    );
}

function ConflictModal({ results, serverUrl, onConfirm, onCancel }) {
    // 人工裁决选择：{ `${type}::${id}` → 'local' | 'remote' }
    const [choices, setChoices] = useState({});
    const onChoose = (type, id, c) => setChoices(prev => ({ ...prev, [`${type}::${id}`]: c }));

    const manualTotal = useMemo(
        () => results.reduce((n, r) => n + r.analysis.manual.length, 0), [results]);
    const chosenCount = useMemo(
        () => results.reduce((n, r) => n + r.analysis.manual.filter(p => choices[`${r.adapter.type}::${p.id}`]).length, 0),
        [results, choices]);
    const remaining = manualTotal - chosenCount;

    const hasAnyChanges = results.some(r => {
        const a = r.analysis;
        return a.added.length + a.removed.length + a.updated.length + a.deleted.length + a.conflicted.length + a.manual.length > 0;
    });

    const confirm = () => {
        // 组装 resolutions: { [type]: { [id]: 'local'|'remote' } }
        const resolutions = {};
        for (const [key, choice] of Object.entries(choices)) {
            const idx = key.indexOf('::');
            const type = key.slice(0, idx), id = key.slice(idx + 2);
            (resolutions[type] ||= {})[id] = choice;
        }
        onConfirm(resolutions);
    };

    return (
        <div style={{ position: 'fixed', inset: 0, zIndex: 1000, background: 'rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <div style={{ width: 460, maxHeight: '85vh', overflow: 'auto', background: 'var(--clr-surface,#1e1e1e)', border: '1px solid var(--clr-border,#333)', borderRadius: 10, padding: 20, display: 'flex', flexDirection: 'column', gap: 12, boxShadow: '0 16px 48px rgba(0,0,0,0.6)' }}>
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
                    <ConflictSection key={r.adapter.type} adapter={r.adapter} analysis={r.analysis}
                        choices={choices} onChoose={onChoose} />
                ))}

                <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', alignItems: 'center', paddingTop: 4 }}>
                    {remaining > 0 && (
                        <span style={{ fontSize: 10, color: '#C0392B', marginRight: 'auto' }}>
                            还有 {remaining} 条冲突未选择
                        </span>
                    )}
                    <button onClick={onCancel} style={{ padding: '6px 14px', borderRadius: 4, background: 'none', border: '1px solid var(--clr-border,#333)', color: 'var(--clr-text-dim)', cursor: 'pointer', fontSize: 12 }}>取消</button>
                    <button onClick={confirm} disabled={remaining > 0}
                        style={{ padding: '6px 14px', borderRadius: 4,
                            background: remaining > 0 ? 'var(--clr-border,#333)' : 'var(--clr-blue,#5B8FCC)',
                            border: 'none', color: remaining > 0 ? 'var(--clr-text-dim)' : '#fff',
                            cursor: remaining > 0 ? 'not-allowed' : 'pointer', fontSize: 12, fontWeight: 600 }}>
                        {hasAnyChanges ? '确认合并' : '完成'}
                    </button>
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
    const { isElectron, open, setOpen, config, setConfig, saveConfig, status, statusMsg, statusColor, preview, setPreview, doSync, executeMerge, serverUrl, pendingConflicts } = sync;

    return (
        <>
            <div style={{ position: 'relative' }}>
                <button className="btn btn--ghost" onClick={() => setOpen(v => !v)} title="同步"
                    style={{ color: pendingConflicts > 0 ? 'var(--clr-red,#C0392B)' : status === 'success' ? '#4A9DA8' : status === 'error' ? 'var(--clr-red,#C0392B)' : undefined }}>
                    <Wifi size={13} />
                    {pendingConflicts > 0 && (
                        <span style={{ position: 'absolute', top: -2, right: -2, width: 6, height: 6, borderRadius: '50%', background: 'var(--clr-red,#C0392B)' }} />
                    )}
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
                            {pendingConflicts > 0 ? `立即同步（${pendingConflicts} 条冲突待处理）` : '立即同步'}
                        </button>
                    </div>
                )}
            </div>

            {preview && (
                <ConflictModal results={preview.results} serverUrl={preview.serverUrl}
                    onConfirm={(resolutions) => executeMerge(preview.serverUrl, resolutions)}
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
