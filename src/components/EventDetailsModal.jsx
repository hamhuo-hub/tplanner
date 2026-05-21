import { useState } from 'react';
import { format } from 'date-fns';
import { formatInTimeZone } from 'date-fns-tz';
import { useTranslation } from 'react-i18next';
import { MASSEY_COLORS } from '../utils/constants';
import { X, CheckCircle2, Circle } from 'lucide-react';
import { getDateLocale } from '../utils/dateLocale';

export default function EventDetailsModal({ event, travelTimezone, onClose, onDelete, onEdit, onSave }) {
    const { t, i18n } = useTranslation();
    const locale = getDateLocale(i18n.language);
    const [deleteScope, setDeleteScope] = useState('single');

    if (!event) return null;

    const color = MASSEY_COLORS[event.colorId] ?? MASSEY_COLORS[0];

    return (
        <div className="modal-overlay" onClick={onClose}>
            <div className="modal-panel" onClick={e => e.stopPropagation()} style={{ borderTopColor: color }}>

                {/* Header */}
                <div className="modal-header">
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10, flex: 1, overflow: 'hidden' }}>
                        <div style={{ width: 10, height: 10, background: color, borderRadius: 1, flexShrink: 0 }} />
                        <h2 className="modal-event-title">{event.title}</h2>
                    </div>
                    <button onClick={onClose} className="btn btn--ghost" style={{ padding: '4px 8px', border: 'none' }} title={t('actions.close')}>
                        <X size={15} />
                    </button>
                </div>

                {/* Content */}
                <div className="modal-content" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                    {/* Time */}
                    <div>
                        <span className="modal-label">{t('event.timeLabel')}</span>
                        <p className="modal-value">
                            {format(event.start, 'EEEE, d MMMM yyyy', { locale })}
                            <br />
                            <span style={{ color: 'var(--clr-gold)', fontWeight: 600 }}>
                                {format(event.start, 'HH:mm')} — {format(event.end, 'HH:mm')}
                            </span>
                        </p>
                        {/* Timezone display */}
                        <div style={{ marginTop: 6, display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                            <span style={{ fontSize: '10px', color: 'var(--clr-text-dim)', letterSpacing: '0.1em', textTransform: 'uppercase' }}>Original:</span>
                            {(() => {
                                const displayTz = event.timezone || 'Asia/Shanghai';
                                return (
                                    <>
                                        <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--clr-text)' }}>
                                            {formatInTimeZone(event.start, displayTz, 'HH:mm')} — {formatInTimeZone(event.end, displayTz, 'HH:mm')}
                                        </span>
                                        <span style={{ fontSize: '9px', background: 'var(--clr-gold-ghost)', color: 'var(--clr-gold)', border: '1px solid var(--clr-gold-dim)', padding: '1px 6px', borderRadius: 2, letterSpacing: '0.12em', textTransform: 'uppercase' }}>
                                            {displayTz.split('/').pop().replace(/_/g, ' ')}
                                        </span>
                                    </>
                                );
                            })()}
                        </div>
                    </div>

                    {/* Note */}
                    {event.note && (
                        <div>
                            <span className="modal-label">{t('event.note')}</span>
                            <div className="modal-note-box">{event.note}</div>
                        </div>
                    )}

                    {/* Checklist */}
                    {event.checklist?.length > 0 && (
                        <div>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
                                <span className="modal-label" style={{ marginBottom: 0 }}>{t('event.checklist', '子任务')}</span>
                                <span style={{ fontFamily: 'var(--font-mono)', fontSize: '10px', color: 'var(--clr-gold-dim)' }}>
                                    {event.checklist.filter(i => i.completed).length}/{event.checklist.length}
                                </span>
                            </div>
                            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                                {event.checklist.map((item, idx) => (
                                    <div key={item.id || idx} className="modal-checklist-item"
                                        onClick={() => {
                                            if (!onSave) return;
                                            const newChecklist = [...event.checklist];
                                            newChecklist[idx] = { ...item, completed: !item.completed };
                                            // Auto-complete or auto-uncomplete main task based on subtask state
                                            const allDone = newChecklist.every(i => i.completed);
                                            const anyUndone = newChecklist.some(i => !i.completed);
                                            const newCompleted = allDone ? true : anyUndone ? false : event.completed;
                                            onSave({ ...event, checklist: newChecklist, completed: newCompleted });
                                        }}
                                    >
                                        <div style={{ color: item.completed ? 'var(--clr-gold)' : 'var(--clr-border-bright)', flexShrink: 0 }}>
                                            {item.completed ? <CheckCircle2 size={15} /> : <Circle size={15} />}
                                        </div>
                                        <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', color: item.completed ? 'var(--clr-text-dim)' : 'var(--clr-text)', textDecoration: item.completed ? 'line-through' : 'none' }}>
                                            {item.text}
                                        </span>
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}
                </div>

                {/* Actions */}
                <div className="modal-actions">
                    {onDelete && (
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginRight: 'auto' }}>
                            {event.groupId && (
                                <select value={deleteScope} onChange={e => setDeleteScope(e.target.value)}
                                    style={{ background: 'var(--clr-void)', border: '1px solid var(--clr-border)', color: 'var(--clr-text-dim)', fontFamily: 'var(--font-mono)', fontSize: '10px', padding: '4px 6px', borderRadius: 2, outline: 'none', cursor: 'pointer' }}
                                >
                                    <option value="single">{t('recurrence.scopeSingle')}</option>
                                    <option value="future">{t('recurrence.scopeFuture')}</option>
                                    <option value="all">{t('recurrence.scopeAll')}</option>
                                </select>
                            )}
                            <button id="btn-delete-event" className="btn btn--danger"
                                onClick={() => { if (confirm(t('messages.deleteConfirmation'))) { onDelete(event.id, deleteScope, event); onClose(); } }}
                            >
                                {t('actions.delete')}
                            </button>
                        </div>
                    )}
                    {onEdit && (
                        <button id="btn-edit-event" className="btn" onClick={() => { onEdit(event); onClose(); }}>
                            {t('actions.edit')}
                        </button>
                    )}
                </div>
            </div>
        </div>
    );
}
