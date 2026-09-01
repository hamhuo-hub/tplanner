import { useTranslation } from 'react-i18next';
import { fromZonedTime, formatInTimeZone } from 'date-fns-tz';
import { event as eventTokens, eventColors, semantic, TaskUnit } from '../design-system';

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
    const timeLabel = `${formatInTimeZone(event.start, tz, 'HH:mm')} – ${formatInTimeZone(event.end, tz, 'HH:mm')}`;

    // TYPE provides the accent hue above; STATE owns every emphasis decision
    // (surface mix, opacity, outline, conflict border) via design tokens.
    const state = isShadow ? 'shadow' : isCompleted ? 'completed' : isSelected ? 'selected' : 'normal';

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
                backgroundColor: eventTokens.surfaceFor(colorVar, state),
                borderColor: isConflicting ? semantic.border.conflict : eventTokens.borderFor(colorVar, state),
                opacity: eventTokens.opacityFor(state),
                filter: state === 'completed' ? eventTokens.completedFilter : undefined,
                left:  `${leftPercent}%`,
                width: `${widthPercent}%`,
                zIndex: 10,
                ...(style || { top: '4px', bottom: '4px' }),
                ...(isSelected ? { outline: `${eventTokens.outline.selectedWidth} solid ${eventTokens.outline.selected}`, outlineOffset: eventTokens.outline.selectedOffset } : null),
            }}
            title={`${event.title} (${timeLabel})`}
        >
            <TaskUnit
                title={event.title}
                type={event.type}
                accentColor={colorVar}
                completed={isCompleted}
                checklist={checklist}
                blockedTitle={t('event.subtaskBlocked', { done: doneCount, total: checklist.length })}
                time={timeLabel}
                onToggle={(nextCompleted) => onToggleTaskComplete?.(event.id, nextCompleted)}
            />
        </div>
    );
}
