import { useTranslation } from 'react-i18next';
import { fromZonedTime, formatInTimeZone } from 'date-fns-tz';
import { eventColors, TaskUnit } from '../design-system';

export default function EventBlock({ event, onClick, isConflicting, displayTimezone, onToggleTaskComplete, onDragStart, onContextMenu, isShadow, isSelected, style }) {
    const { t } = useTranslation();
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
    const colorVar = `var(--clr-event-${colorIdx}, ${eventColors[colorIdx] ?? eventColors[0]})`;

    // Checklist progress
    const checklist = event.checklist ?? [];
    const doneCount = checklist.filter(i => i.completed).length;

    let blockClass = 'event-block';
    if (isConflicting) blockClass += ' event-block--conflicting';
    if (isCompleted)   blockClass += ' event-block--completed';
    if (isShadow)      blockClass += ' event-block--shadow';
    if (isSelected)    blockClass += ' event-block--selected';

    return (
        <div
            data-event-id={event.id}
            onClick={e => { e.stopPropagation(); onClick(event); }}
            onMouseDown={e => { if (e.button !== 0) return; e.stopPropagation(); onDragStart?.(e); }}
            onContextMenu={e => { e.preventDefault(); e.stopPropagation(); onContextMenu?.(e, event); }}
            className={blockClass}
            style={{
                // Use the event palette for the whole unit, matching the colored
                // timeline cells instead of reducing color to a narrow accent.
                backgroundColor: `color-mix(in srgb, ${colorVar} 50%, var(--clr-raised))`,
                borderColor: `color-mix(in srgb, ${colorVar} 75%, rgba(255,255,255,0.18))`,
                opacity: isShadow ? 0.25 : isCompleted ? 0.45 : 1,
                left:  `${leftPercent}%`,
                width: `${widthPercent}%`,
                zIndex: 10,
                ...(style || { top: '4px', bottom: '4px' }),
                ...(isSelected ? { outline: '2px solid var(--clr-gold, #C9A84C)', outlineOffset: '1px' } : null),
            }}
            title={`${event.title} (${formatInTimeZone(event.start, tz, 'HH:mm')} – ${formatInTimeZone(event.end, tz, 'HH:mm')})`}
        >
            <TaskUnit
                title={event.title}
                type={event.type}
                accentColor={colorVar}
                completed={isCompleted}
                checklist={checklist}
                blockedTitle={t('event.subtaskBlocked', { done: doneCount, total: checklist.length })}
                note={durationMins > 45 ? event.note : undefined}
                onToggle={(nextCompleted) => onToggleTaskComplete?.(event.id, nextCompleted)}
            />
        </div>
    );
}
