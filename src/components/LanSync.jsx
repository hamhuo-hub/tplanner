import { useState, useMemo } from 'react';
import { Wifi, RefreshCw, AlertTriangle, CheckCircle, X, ChevronDown, ChevronUp } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { format } from 'date-fns';
import useLanSync from '../hooks/useLanSync';
import { countSubtaskChanges } from '../utils/syncLogic';

// ── 子组件：冲突预览弹窗 ──────────────────────────────────────────────────────
const fmtTime = (ts) => ts ? format(new Date(ts), 'MM-dd HH:mm') : '';

// 通用冲突分组展示：事件/目标用 .title，日志用 .date + 文本片段
function titleLabel(item) { return item?.title ?? ''; }
function journalLabel(item) {
    const text = item?.text || '';
    const snippet = text.replace(/\s+/g, ' ').trim().slice(0, 20);
    return snippet ? `${item.date} · ${snippet}${text.length > 20 ? '…' : ''}` : item?.date ?? '';
}

function ConflictSection({ title, itemLabel, unit, analysis, extra }) {
    const [showDetail, setShowDetail] = useState(false);
    const { added, removed, updated, deleted, conflicted, synced } = analysis;
    const hasChanges = added.length + removed.length + updated.length + deleted.length > 0;

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, paddingTop: 12, borderTop: '1px solid var(--clr-border,#333)' }}>
            <span style={{ fontFamily: 'var(--font-display)', fontSize: 11, letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--clr-text-dim)' }}>
                {title}
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
                    {deleted.length > 0    && <StatRow icon="🗑" color="#A04040" label={`${deleted.length} ${unit}将被删除（已在其中一端删除）`} />}
                    {updated.length > 0    && <StatRow icon="↻" color="#C9A84C" label={`${updated.length} ${unit}将被对端较新版本覆盖`} />}
                    {conflicted.length > 0 && <StatRow icon="!" color="#C0392B" label={`${conflicted.length} ${unit}本地版本更新（保留本地）`} />}
                    {synced.length > 0     && <StatRow icon="✓" color="#4A7C59" label={`${synced.length} ${unit}已同步无变化`} />}
                </div>
            )}

            {extra}

            {hasChanges && (
                <>
                    <button onClick={() => setShowDetail(v => !v)}
                        style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4, color: 'var(--clr-text-dim)', fontSize: 11, padding: 0 }}>
                        {showDetail ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
                        {showDetail ? '收起详情' : '查看详情'}
                    </button>

                    {showDetail && (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxHeight: 220, overflow: 'auto' }}>
                            <EventGroup title="将从对端拉取" color="#5B8FCC" items={added} renderItem={itemLabel} />
                            <EventGroup title="将推送到对端" color="#4A9DA8" items={removed} renderItem={itemLabel} />
                            <EventGroup title="将被删除（tombstone 传播）" color="#A04040" items={deleted}
                                renderItem={({ local: l, remote: r }) => (
                                    <span style={{ textDecoration: 'line-through', color: 'var(--clr-text-dim)' }}>
                                        {itemLabel(l)}
                                        <span style={{ fontSize: 9, marginLeft: 6 }}>
                                            {fmtTime(r.deletedAt || l.deletedAt) && `${fmtTime(r.deletedAt || l.deletedAt)} 删除`}
                                        </span>
                                    </span>
                                )}
                            />
                            <EventGroup title="将被对端版本覆盖" color="#C9A84C" items={updated}
                                renderItem={({ local: l, remote: r }) => (
                                    <span>
                                        <span style={{ textDecoration: 'line-through', color: 'var(--clr-text-dim)', marginRight: 6 }}>{itemLabel(l)}</span>
                                        → {itemLabel(r)}
                                        <span style={{ fontSize: 9, color: 'var(--clr-text-dim)', marginLeft: 6 }}>{fmtTime(r.updatedAt)}</span>
                                    </span>
                                )}
                            />
                            <EventGroup title="本地版本更新（保留）" color="#C0392B" items={conflicted}
                                renderItem={({ local: l }) => itemLabel(l)}
                            />
                        </div>
                    )}
                </>
            )}
        </div>
    );
}

function ConflictModal({ analysis, journalAnalysis, goalAnalysis, serverUrl, onConfirm, onCancel }) {
    const { added, removed, updated, deleted } = analysis;
    const hasEventChanges = added.length + removed.length + updated.length + deleted.length > 0;
    const hasJournalChanges = ['added', 'removed', 'updated', 'deleted'].some(k => journalAnalysis[k].length > 0);
    const hasGoalChanges    = ['added', 'removed', 'updated', 'deleted'].some(k => goalAnalysis[k].length > 0);
    const hasChanges = hasEventChanges || hasJournalChanges || hasGoalChanges;
    const subtaskStats = useMemo(() => countSubtaskChanges(updated), [updated]);

    return (
        <div style={{
            position: 'fixed', inset: 0, zIndex: 1000,
            background: 'rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
            <div style={{
                width: 440, maxHeight: '85vh', overflow: 'auto',
                background: 'var(--clr-surface,#1e1e1e)',
                border: '1px solid var(--clr-border,#333)', borderRadius: 10,
                padding: 20, display: 'flex', flexDirection: 'column', gap: 12,
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

                {/* Server info */}
                <div style={{ fontSize: 11, color: 'var(--clr-text-dim)', fontFamily: 'var(--font-mono)', padding: '6px 10px', background: 'rgba(255,255,255,0.04)', borderRadius: 4 }}>
                    {serverUrl}
                </div>

                {!hasChanges && (
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#4A9DA8' }}>
                        <CheckCircle size={15} />
                        <span style={{ fontSize: 13 }}>数据完全一致，无需合并</span>
                    </div>
                )}

                <ConflictSection
                    title="事件"
                    unit="条"
                    itemLabel={titleLabel}
                    analysis={analysis}
                    extra={subtaskStats.events > 0 && (
                        <StatRow icon="☑" color="#9B7EBD" label={`其中 ${subtaskStats.events} 条事件的子任务有变化（共 ${subtaskStats.items} 项）`} />
                    )}
                />
                <ConflictSection title="日志" unit="篇" itemLabel={journalLabel} analysis={journalAnalysis} />
                <ConflictSection title="目标" unit="个" itemLabel={titleLabel} analysis={goalAnalysis} />

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
export default function LanSync({ events, onMergeEvents, journals, onMergeJournals, goals, onMergeGoals }) {
    const { t } = useTranslation();
    const sync = useLanSync({ events, onMergeEvents, journals, onMergeJournals, goals, onMergeGoals });
    const {
        isElectron,
        open, setOpen,
        config, setConfig, saveConfig,
        status, statusMsg, statusColor,
        preview, setPreview,
        doSync, executeMerge,
        serverUrl,
    } = sync;

    return (
        <>
            {/* 工具栏按钮 */}
            <div style={{ position: 'relative' }}>
                <button
                    className="btn btn--ghost"
                    onClick={() => setOpen(v => !v)}
                    title="同步"
                    style={{ color: status === 'success' ? '#4A9DA8' : status === 'error' ? 'var(--clr-red,#C0392B)' : undefined }}
                >
                    <Wifi size={13} />
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
                                同步服务器
                            </span>
                        </div>

                        {/* 同步服务器地址 */}
                        <input
                            type="text"
                            placeholder="https://sync.hamhuo.top"
                            value={config.serverUrl}
                            onChange={e => setConfig(c => ({ ...c, serverUrl: e.target.value }))}
                            onBlur={() => saveConfig(config)}
                            style={inputStyle}
                        />

                        {/* 开关 */}
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
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
                            onClick={() => doSync(serverUrl)}
                            disabled={status === 'syncing' || !serverUrl}
                            style={{ display: 'flex', alignItems: 'center', gap: 6, justifyContent: 'center' }}
                        >
                            <RefreshCw size={12} style={status === 'syncing' ? { animation: 'spin 1s linear infinite' } : {}} />
                            立即同步
                        </button>
                    </div>
                )}
            </div>

            {/* 冲突预览弹窗 */}
            {preview && (
                <ConflictModal
                    analysis={preview.analysis}
                    journalAnalysis={preview.journalAnalysis}
                    goalAnalysis={preview.goalAnalysis}
                    serverUrl={preview.serverUrl}
                    onConfirm={() => executeMerge(preview.serverUrl, preview.remoteEvents)}
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
