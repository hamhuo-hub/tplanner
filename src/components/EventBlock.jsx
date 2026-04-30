import { MASSEY_COLORS } from '../utils/constants';
import { formatInTimeZone } from 'date-fns-tz';

export default function EventBlock({ event, onClick, isConflicting, displayTimezone, onToggleTaskComplete, onDragStart, style }) {
    const tz = displayTimezone || Intl.DateTimeFormat().resolvedOptions().timeZone;

    const toMins = str => { const [h, m] = str.split(':').map(Number); return h * 60 + m; };
    let startMins, endMins;
    try {
        startMins = toMins(formatInTimeZone(event.start, tz, 'HH:mm'));
        endMins   = toMins(formatInTimeZone(event.end,   tz, 'HH:mm'));
        if (endMins < startMins) endMins += 1440;
        if (endMins - startMins < 15) endMins = startMins + 15;
    } catch {
        startMins = event.start.getHours() * 60 + event.start.getMinutes();
        endMins   = event.end.getHours()   * 60 + event.end.getMinutes();
        if (endMins < startMins) endMins += 1440;
    }

    const durationMins  = endMins - startMins;
    const leftPercent   = (startMins / 1440) * 100;
    const widthPercent  = (durationMins / 1440) * 100;
    const isCompleted   = event.completed === true;
    const color         = isCompleted ? '#3A342A' : (MASSEY_COLORS[event.colorId] ?? MASSEY_COLORS[0]);
    const titleOffsetPx = event.titleOffsetPx || 0;

    let blockClass = 'event-block';
    if (isConflicting) blockClass += ' event-block--conflicting';
    if (isCompleted)   blockClass += ' event-block--completed';

    return (
        <div
            onClick={e => { e.stopPropagation(); onClick(event); }}
            onMouseDown={e => { if (e.button !== 0) return; e.stopPropagation(); onDragStart?.(e); }}
            className={blockClass}
            style={{
                backgroundColor: color,
                left:  `${leftPercent}%`,
                width: `${widthPercent}%`,
                zIndex: 10,
                ...(style || { top: '4px', bottom: '4px' }),
            }}
            title={`${event.title} (${formatInTimeZone(event.start, tz, 'HH:mm')} – ${formatInTimeZone(event.end, tz, 'HH:mm')})`}
        >
            <div className="event-block-inner" style={{ paddingTop: `calc(0.25rem + ${titleOffsetPx}px)` }}>
                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 3 }}>
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
