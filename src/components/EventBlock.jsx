import { MASSEY_COLORS } from '../utils/constants';
import { fromZonedTime, formatInTimeZone } from 'date-fns-tz';

export default function EventBlock({ event, onClick, isConflicting, displayTimezone, onToggleTaskComplete, onDragStart, style }) {
    const tz = displayTimezone || Intl.DateTimeFormat().resolvedOptions().timeZone;

    let dayStartMs;
    try {
        const dateStr = formatInTimeZone(event.start, tz, 'yyyy-MM-dd');
        dayStartMs = fromZonedTime(`${dateStr}T00:00:00`, tz).getTime();
    } catch {
        dayStartMs = new Date(event.start).setHours(0, 0, 0, 0);
    }
    const DAY_MS     = 24 * 60 * 60 * 1000;
    const startMsOff = Math.max(0, event.start.getTime() - dayStartMs);
    const endMsOff   = Math.min(DAY_MS, event.end.getTime() - dayStartMs);
    const startMins  = startMsOff / 60000;
    const endMins    = Math.max(startMins + 15, endMsOff / 60000);
    const durationMins = endMins - startMins;

    const leftPercent  = (startMins / 1440) * 100;
    const widthPercent = (durationMins / 1440) * 100;
    const isCompleted  = event.completed === true;
    const colorIdx = event.colorId ?? 0;
    const colorVar = `var(--clr-event-${colorIdx}, ${MASSEY_COLORS[colorIdx] ?? MASSEY_COLORS[0]})`;

    // Checklist progress
    const checklist = event.checklist ?? [];
    const hasChecklist = checklist.length > 0;
    const doneCount = checklist.filter(i => i.completed).length;
    const allDone = hasChecklist ? doneCount === checklist.length : true;

    const handleCheckboxClick = (e) => {
        e.stopPropagation();
        // Block main toggle if checklist exists and not all items done
        if (hasChecklist && !allDone && !isCompleted) return;
        onToggleTaskComplete?.(event.id, !isCompleted);
    };

    let blockClass = 'event-block';
    if (isConflicting) blockClass += ' event-block--conflicting';
    if (isCompleted)   blockClass += ' event-block--completed';

    return (
        <div
            onClick={e => { e.stopPropagation(); onClick(event); }}
            onMouseDown={e => { if (e.button !== 0) return; e.stopPropagation(); onDragStart?.(e); }}
            className={blockClass}
            style={{
                backgroundColor: colorVar,
                opacity: isCompleted ? 0.45 : 1,
                left:  `${leftPercent}%`,
                width: `${widthPercent}%`,
                zIndex: 10,
                ...(style || { top: '4px', bottom: '4px' }),
            }}
            title={`${event.title} (${formatInTimeZone(event.start, tz, 'HH:mm')} – ${formatInTimeZone(event.end, tz, 'HH:mm')})`}
        >
            <div className="event-block-inner">
                <div style={{ display: 'flex', alignItems: 'center', gap: 4, minWidth: 0 }}>
                    {/* Task checkbox — greyed out when subtasks pending */}
                    {event.type === 'task' && (
                        <div
                            className="task-checkbox"
                            onClick={handleCheckboxClick}
                            title={hasChecklist && !allDone ? `请先完成子任务 (${doneCount}/${checklist.length})` : undefined}
                            style={{ opacity: hasChecklist && !allDone && !isCompleted ? 0.4 : 1, cursor: hasChecklist && !allDone && !isCompleted ? 'not-allowed' : 'pointer' }}
                        >
                            {isCompleted && (
                                <svg style={{ width: 8, height: 8, color: 'var(--clr-gold)' }} fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                    <path strokeLinecap="square" strokeLinejoin="miter" strokeWidth={3} d="M5 13l4 4L19 7" />
                                </svg>
                            )}
                        </div>
                    )}
                    <div className={`event-block-title${isCompleted ? ' event-block-title--completed' : ''}`} style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {event.title}
                    </div>
                    {/* Subtask progress badge */}
                    {hasChecklist && (
                        <span style={{
                            flexShrink: 0,
                            fontSize: '9px',
                            fontFamily: 'var(--font-mono)',
                            background: allDone ? 'rgba(74,124,89,0.35)' : 'rgba(0,0,0,0.25)',
                            color: allDone ? '#7EC897' : 'rgba(255,255,255,0.7)',
                            padding: '1px 4px',
                            borderRadius: 3,
                            letterSpacing: '0.02em',
                        }}>
                            {doneCount}/{checklist.length}
                        </span>
                    )}
                </div>
                {durationMins > 45 && event.note && (
                    <div className="event-block-note">{event.note}</div>
                )}
            </div>
        </div>
    );
}
