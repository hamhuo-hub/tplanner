import { MASSEY_COLORS } from '../utils/constants';
import { fromZonedTime, formatInTimeZone } from 'date-fns-tz';

export default function EventBlock({ event, onClick, isConflicting, displayTimezone, onToggleTaskComplete, onDragStart, style }) {
    const tz = displayTimezone || Intl.DateTimeFormat().resolvedOptions().timeZone;

    // dateStr = the calendar date of this row in the display timezone.
    // event.start has already been clamped to [dayStart, dayEnd] by EventRow,
    // so formatInTimeZone(event.start, tz) gives the correct date for this row.
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

    const durationMins  = endMins - startMins;
    const leftPercent   = (startMins / 1440) * 100;
    const widthPercent  = (durationMins / 1440) * 100;
    const isCompleted   = event.completed === true;
    const colorIdx = event.colorId ?? 0;
    // Use CSS variable so .tptheme packages can override event colors
    const colorVar = `var(--clr-event-${colorIdx}, ${MASSEY_COLORS[colorIdx] ?? MASSEY_COLORS[0]})`;

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
            {/* event-block-inner: CSS already has display:flex + justify-content:center
                 Do NOT override with paddingTop, it breaks vertical centering. */}
            <div className="event-block-inner">
                <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                    {/* Task checkbox */}
                    {event.type === 'task' && (
                        <div
                            className="task-checkbox"
                            onClick={e => { e.stopPropagation(); onToggleTaskComplete?.(event.id, !isCompleted); }}
                        >
                            {isCompleted && (
                                <svg style={{ width: 8, height: 8, color: 'var(--clr-gold)' }} fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                    <path strokeLinecap="square" strokeLinejoin="miter" strokeWidth={3} d="M5 13l4 4L19 7" />
                                </svg>
                            )}
                        </div>
                    )}
                    <div className={`event-block-title${isCompleted ? ' event-block-title--completed' : ''}`}>
                        {event.title}
                    </div>
                </div>
                {durationMins > 45 && event.note && (
                    <div className="event-block-note">{event.note}</div>
                )}
            </div>
        </div>
    );
}
