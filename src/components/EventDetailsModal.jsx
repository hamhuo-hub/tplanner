import { useState, useEffect, useRef } from 'react';
import { format } from 'date-fns';
import { formatInTimeZone } from 'date-fns-tz';
import { useTranslation } from 'react-i18next';
import { categoryForId, TaskCheckbox, TaskProgress } from '../design-system';
import { X } from 'lucide-react';
import { getDateLocale } from '../utils/dateLocale';
import NoteEditor from './NoteEditor';
import { recurrenceOf } from '../domain/recurringTasks.mjs';
import { seriesIdOf } from '../domain/recurringTaskSelection.mjs';

export default function EventDetailsModal({ event, travelTimezone, onClose, onDelete, onEdit, onSave }) {
    const { t, i18n } = useTranslation();
    const locale = getDateLocale(i18n.language);
    const [confirmingDelete, setConfirmingDelete] = useState(false);
    const [saveError, setSaveError] = useState('');
    const [saving, setSaving] = useState(false);
    const detailSessionRef = useRef(0);

    useEffect(() => {
        detailSessionRef.current += 1;
        setConfirmingDelete(false);
        setSaveError('');
        setSaving(false);
    }, [event?.id]);

    if (!event) return null;

    const category = categoryForId(event.colorId);
    const toggleChecklistItem = async (index, completed) => {
        if (!onSave || saving) return;
        const newChecklist = event.checklist.map((item, itemIndex) =>
            itemIndex === index ? { ...item, completed } : item);
        // Preserve automatic parent completion when every subtask is complete.
        const allDone = newChecklist.every(item => item.completed);
        const anyUndone = newChecklist.some(item => !item.completed);
        const session = detailSessionRef.current;
        try {
            setSaving(true);
            setSaveError('');
            await onSave({ ...event, checklist: newChecklist, completed: allDone ? true : anyUndone ? false : event.completed }, { original: event });
        } catch (error) {
            if (session === detailSessionRef.current) setSaveError(error?.message || t('messages.saveError', '保存失败，请重试'));
        } finally { if (session === detailSessionRef.current) setSaving(false); }
    };
    const deleteOccurrence = async () => {
        if (saving) return;
        const session = detailSessionRef.current;
        setSaving(true);
        setSaveError('');
        try {
            await onDelete(event.id);
            if (session === detailSessionRef.current) onClose();
        } catch (error) {
            if (session === detailSessionRef.current) setSaveError(error?.message || t('messages.saveError', '保存失败，请重试'));
        } finally { if (session === detailSessionRef.current) setSaving(false); }
    };

    return (
        <div className="modal-overlay" onClick={onClose}>
            <div className="modal-panel" onClick={e => e.stopPropagation()} style={{ borderTopColor: category.border }}>

                {/* Header */}
                <div className="modal-header">
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10, flex: 1, overflow: 'hidden' }}>
                        <div aria-hidden="true" style={{ width: 10, height: 10, background: category.accent, borderRadius: 'var(--tp-semantic-radius-small)', flexShrink: 0 }} />
                        <h2 className="modal-event-title">{event.title}</h2>
                    </div>
                    <button onClick={onClose} className="btn btn--ghost" style={{ padding: '4px 8px', border: 'none' }} title={t('actions.close')} aria-label={t('actions.close')}>
                        <X size={15} />
                    </button>
                </div>

                {/* Content */}
                <div className="modal-content" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                    {saveError && <p role="alert">{saveError}</p>}
                    {/* Time */}
                    <div>
                        <span className="modal-label">{t('event.timeLabel')}</span>
                        <p className="modal-value">
                            {format(event.start, 'EEEE, d MMMM yyyy', { locale })}
                            <br />
                            <span style={{ color: 'var(--tp-semantic-color-accent-text)', fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
                                {format(event.start, 'HH:mm')} — {format(event.end, 'HH:mm')}
                            </span>
                        </p>
                        {/* Timezone display */}
                        <div style={{ marginTop: 6, display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                            <span className="modal-timezone-label">{t('event.originalTz')}</span>
                            {(() => {
                                const displayTz = event.timezone || 'Asia/Shanghai';
                                return (
                                    <>
                                        <span className="modal-timezone-time">
                                            {formatInTimeZone(event.start, displayTz, 'HH:mm')} — {formatInTimeZone(event.end, displayTz, 'HH:mm')}
                                        </span>
                                        <span className="modal-timezone-badge">
                                            {displayTz.split('/').pop().replace(/_/g, ' ')}
                                        </span>
                                    </>
                                );
                            })()}
                        </div>
                    </div>

                    {event.type === 'task' && <div>
                        <span className="modal-label">{t('event.recurrence', '重复')}</span>
                        <p className="modal-value">
                            {t(`recurrence.${recurrenceOf(event)?.frequency || 'none'}`)}
                            {recurrenceOf(event) && ` · ${recurrenceOf(event).count} ${t('recurrence.occurrences', '次')}`}
                            {seriesIdOf(event) && ` · ${t('recurrence.member', '第 {{index}} 次', { index: (event.recurrence?.occurrenceIndex ?? 0) + 1 })}`}
                        </p>
                        {onEdit && <button type="button" className="btn" onClick={() => { onEdit(event); onClose(); }}>
                            {t(seriesIdOf(event) ? 'recurrence.editSeries' : 'recurrence.setRepeat', seriesIdOf(event) ? '修改重复系列' : '设置重复')}
                        </button>}
                    </div>}

                    {/* Note */}
                    <div>
                        <span className="modal-label">{t('event.note')}</span>
                        <NoteEditor
                            value={event.note || ''}
                            readOnly
                        />
                    </div>

                    {/* Checklist */}
                    {event.checklist?.length > 0 && (
                        <div>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
                                <span className="modal-label" style={{ marginBottom: 0 }}>{t('event.checklist')}</span>
                                <TaskProgress className="modal-checklist-progress"
                                    done={event.checklist.filter(item => item.completed).length} total={event.checklist.length} />
                            </div>
                            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                                {event.checklist.map((item, idx) => (
                                    <div key={item.id || idx} className="modal-checklist-item"
                                        onClick={() => toggleChecklistItem(idx, !item.completed)}
                                    >
                                        <TaskCheckbox completed={item.completed} disabled={!onSave || saving} title={item.text}
                                            className="modal-checklist-checkbox" onToggle={completed => toggleChecklistItem(idx, completed)} />
                                        <span className={`modal-checklist-text${item.completed ? ' modal-checklist-text--completed' : ''}`}>
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
                            {!confirmingDelete ? (
                                <button id="btn-delete-event" className="btn btn--danger"
                                    onClick={() => setConfirmingDelete(true)}
                                >
                                    {t('actions.delete')}
                                </button>
                            ) : (
                                <>
                                    <span className="modal-delete-confirmation">
                                        {seriesIdOf(event) ? t('recurrence.deleteOccurrence', '仅删除此次任务？') : t('messages.deleteConfirmation')}
                                    </span>
                                    <button className="btn btn--danger" disabled={saving} onClick={deleteOccurrence}>
                                        {t('actions.confirm')}
                                    </button>
                                    <button className="btn" onClick={() => setConfirmingDelete(false)}>
                                        {t('actions.cancel')}
                                    </button>
                                </>
                            )}
                        </div>
                    )}
                    {!confirmingDelete && onEdit && (
                        <button id="btn-edit-event" className="btn" onClick={() => { onEdit(event); onClose(); }}>
                            {t('actions.edit')}
                        </button>
                    )}
                </div>
            </div>
        </div>
    );
}
