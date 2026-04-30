import { format, areIntervalsOverlapping, max, min, addMinutes } from 'date-fns';
import { fromZonedTime, formatInTimeZone } from 'date-fns-tz';
import EventBlock from './EventBlock';
import { useTranslation } from 'react-i18next';
import { getDateLocale } from '../utils/dateLocale';
import { MASSEY_COLORS } from '../utils/constants';

export default function EventRow({ date, events, onEventClick, onAddEvent, highlight, onDragStart, dragState, clashes, displayTimezone, onToggleTaskComplete }) {
    const { i18n } = useTranslation();
    const locale = getDateLocale(i18n.language);

    // ── Day boundaries in the DISPLAY timezone ──────────────────────────────
    // Using fromZonedTime so that "Apr 24" always means Apr 24 00:00–23:59
    // in the selected timezone, not in the browser's local timezone.
    const tz = displayTimezone || Intl.DateTimeFormat().resolvedOptions().timeZone;
    const dateStr = format(date, 'yyyy-MM-dd'); // always local-date label (Apr 24)
    const dayStart = fromZonedTime(`${dateStr}T00:00:00`, tz);
    const dayEnd   = fromZonedTime(`${dateStr}T23:59:59.999`, tz);

    const dayEventsRaw  = events.filter(e => areIntervalsOverlapping({ start: e.start, end: e.end }, { start: dayStart, end: dayEnd }));
    const statusEvents  = dayEventsRaw.filter(e => e.type === 'status');
    const regularEvents = dayEventsRaw.filter(e => e.type !== 'status');

    // Clamp & sort regular events
    const processedRegularEvents = regularEvents.map(e => ({
        ...e, originalStart: e.start, originalEnd: e.end,
        start: max([e.start, dayStart]), end: min([e.end, dayEnd])
    })).sort((a, b) => a.start - b.start);

    // Status events — row stacking
    const processedStatusEvents = statusEvents.map(e => ({
        ...e, start: max([e.start, dayStart]), end: min([e.end, dayEnd])
    })).sort((a, b) => a.start - b.start);

    const statusRows = [];
    const finalStatusEvents = processedStatusEvents.map(ev => {
        let rowIndex = 0;
        while (true) {
            const row = statusRows[rowIndex] || [];
            const collision = row.find(existing =>
                areIntervalsOverlapping({ start: existing.start, end: existing.end }, { start: ev.start, end: ev.end })
            );
            if (!collision) {
                if (!statusRows[rowIndex]) statusRows[rowIndex] = [];
                statusRows[rowIndex].push(ev);
                break;
            }
            rowIndex++;
        }
        return { ...ev, rowIndex };
    });

    // Regular events — cluster stacking
    const finalRegularEvents = [];
    if (processedRegularEvents.length > 0) {
        const clusters = [];
        let currentCluster = [], clusterEnd = null;
        processedRegularEvents.forEach(event => {
            if (!clusterEnd || event.start < clusterEnd) {
                currentCluster.push(event);
                clusterEnd = clusterEnd ? max([clusterEnd, event.end]) : event.end;
            } else {
                clusters.push(currentCluster);
                currentCluster = [event];
                clusterEnd = event.end;
            }
        });
        if (currentCluster.length) clusters.push(currentCluster);

        clusters.forEach(cluster => {
            cluster.forEach((ev, idx) => {
                const isConflicting = clashes ? clashes.some(c => c.eventId === ev.id) : false;
                const overlappingStatus = finalStatusEvents.filter(se =>
                    areIntervalsOverlapping({ start: se.start, end: se.end }, { start: ev.start, end: ev.end })
                );
                let titleOffsetPx = 0;
                if (overlappingStatus.length > 0) {
                    const maxStatusRow = Math.max(...overlappingStatus.map(se => se.rowIndex));
                    const statusBottomPx = maxStatusRow * 18 + 16;
                    const regularTopPx  = 80 * (0.15 + idx * 0.15);
                    if (statusBottomPx > regularTopPx) titleOffsetPx = (statusBottomPx - regularTopPx) + 4;
                }
                finalRegularEvents.push({ ...ev, isConflicting, titleOffsetPx, style: { top: `${idx * 15}%`, height: '80%', zIndex: 10 + idx } });
            });
        });
    }

    const isWeekend = date.getDay() === 0 || date.getDay() === 6;

    const handleGridClick = (e) => {
        if (e.target.closest('.event-block')) return;
        const rect = e.currentTarget.getBoundingClientRect();
        const clickMinutes = Math.round((24 * 60 * (e.clientX - rect.left) / rect.width) / 10) * 10;
        const clickDate = new Date(dayStart);
        clickDate.setMinutes(clickMinutes);
        onAddEvent?.(clickDate, addMinutes(clickDate, 60));
    };

    return (
        <div id={`row-${format(date, 'yyyy-MM-dd')}`} className={`event-row ${isWeekend ? 'event-row--weekend' : 'event-row--weekday'}`}>
            {/* Date Column */}
            <div className="event-row-date">
                <span className="event-row-date-dow">{format(date, 'EEE', { locale })}</span>
                <span className={`event-row-date-num${isWeekend ? ' event-row-date-num--weekend' : ''}`}>
                    {format(date, 'd')}
                    <span style={{ fontSize: '10px', display: 'block', fontWeight: 400, letterSpacing: '0.05em', color: 'var(--clr-text-dim)', marginTop: '-2px' }}>
                        {format(date, 'MMM', { locale })}
                    </span>
                </span>
            </div>

            {/* Grid */}
            <div className="event-row-grid" onClick={handleGridClick}>
                {/* Status events (top strip) */}
                {statusEvents.length > 0 && (
                    <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: '15%', zIndex: 30, pointerEvents: 'none' }}>
                        {finalStatusEvents.map(ev => {
                            const tz = displayTimezone || Intl.DateTimeFormat().resolvedOptions().timeZone;
                            const toMins = str => { const [h, m] = str.split(':').map(Number); return h * 60 + m; };
                            let startMins, endMins;
                            try {
                                startMins = toMins(formatInTimeZone(ev.start, tz, 'HH:mm'));
                                endMins   = toMins(formatInTimeZone(ev.end,   tz, 'HH:mm'));
                                if (endMins < startMins) endMins += 1440;
                                if (endMins - startMins < 15) endMins = startMins + 15;
                            } catch {
                                startMins = ev.start.getHours() * 60 + ev.start.getMinutes();
                                endMins   = ev.end.getHours()   * 60 + ev.end.getMinutes();
                            }
                            const left  = (startMins / 1440) * 100;
                            const width = ((endMins - startMins) / 1440) * 100;
                            return (
                                <div key={ev.id}
                                    style={{
                                        position: 'absolute',
                                        backgroundColor: ev.colorId !== undefined ? MASSEY_COLORS[ev.colorId] : '#4A4A4A',
                                        left: `${left}%`, width: `${width}%`,
                                        top: `${ev.rowIndex * 18}px`, height: '16px',
                                        borderRadius: 2, borderLeft: '3px solid rgba(255,255,255,0.3)',
                                        overflow: 'hidden', paddingLeft: 4,
                                        pointerEvents: 'auto', cursor: 'pointer',
                                    }}
                                    onClick={e => { e.stopPropagation(); onEventClick(events.find(o => o.id === ev.id) || ev); }}
                                >
                                    <span style={{ fontFamily: 'var(--font-display)', fontSize: '9px', letterSpacing: '0.06em', textTransform: 'uppercase', color: '#fff', whiteSpace: 'nowrap' }}>
                                        {ev.title}
                                    </span>
                                </div>
                            );
                        })}
                    </div>
                )}

                {/* Hour lines */}
                {Array.from({ length: 24 }).map((_, i) => (
                    <div key={i} className="hour-line" style={{ left: `${(i / 24) * 100}%` }} />
                ))}

                {/* Clash highlight */}
                {highlight && highlight.type !== 'today' && areIntervalsOverlapping(
                    { start: highlight.start, end: highlight.end }, { start: dayStart, end: dayEnd }
                ) && (() => {
                    const hStart = max([highlight.start, dayStart]);
                    const hEnd   = min([highlight.end,   dayEnd]);
                    if (hEnd <= hStart) return null;
                    const startMins = hStart.getHours() * 60 + hStart.getMinutes();
                    const endMins   = hEnd.getTime() === dayEnd.getTime() + 1 ? 1440 : hEnd.getHours() * 60 + hEnd.getMinutes();
                    return (
                        <div className="highlight-clash"
                            style={{ position: 'absolute', left: `${(startMins / 1440) * 100}%`, width: `${((endMins - startMins) / 1440) * 100}%`, top: '15%', bottom: 0, background: 'rgba(192,57,43,0.12)', zIndex: 10, pointerEvents: 'none' }}
                        />
                    );
                })()}

                {/* Today highlight */}
                {highlight?.type === 'today' && areIntervalsOverlapping(
                    { start: highlight.start, end: highlight.end }, { start: dayStart, end: dayEnd }
                ) && (
                    <div className="highlight-today" style={{ position: 'absolute', inset: 0, zIndex: 10, pointerEvents: 'none', borderRadius: 2 }} />
                )}

                {/* Regular events */}
                {finalRegularEvents.map(event => {
                    const isDragging = dragState?.event?.id === event.id;
                    return (
                        <EventBlock
                            key={event.id}
                            event={event}
                            isConflicting={event.isConflicting}
                            displayTimezone={displayTimezone}
                            onClick={() => onEventClick(events.find(ev => ev.id === event.id) || event)}
                            onToggleTaskComplete={onToggleTaskComplete}
                            onDragStart={e => onDragStart(event, e.clientX, e.clientY, date)}
                            style={{
                                ...event.style,
                                top: `calc(15% + ${event.style.top})`,
                                height: '70%',
                                opacity: isDragging ? 0 : 1,
                                pointerEvents: isDragging ? 'none' : 'auto',
                            }}
                        />
                    );
                })}

                {/* Drag ghost */}
                {dragState?.snapStart && dragState?.snapEnd && (() => {
                    if (!areIntervalsOverlapping({ start: dayStart, end: dayEnd }, { start: dragState.snapStart, end: dragState.snapEnd })) return null;
                    const rangeStart = max([dayStart, dragState.snapStart]);
                    const rangeEnd   = min([dayEnd,   dragState.snapEnd]);
                    const startMins  = rangeStart.getHours() * 60 + rangeStart.getMinutes();
                    let   endMins    = rangeEnd.getHours()   * 60 + rangeEnd.getMinutes();
                    if (endMins === 0 && rangeEnd > rangeStart) endMins = 1440;
                    return (
                        <EventBlock
                            event={{ ...dragState.event, start: dragState.snapStart, end: dragState.snapEnd }}
                            isConflicting={false}
                            displayTimezone={displayTimezone}
                            onClick={() => {}}
                            style={{
                                left: `${(startMins / 1440) * 100}%`,
                                width: `${((endMins - startMins) / 1440) * 100}%`,
                                top: '15%', height: '70%', zIndex: 50, position: 'absolute',
                                opacity: 0.75, border: '2px dashed rgba(201,168,76,0.8)',
                            }}
                        />
                    );
                })()}
            </div>
        </div>
    );
}
