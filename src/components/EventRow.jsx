import { format, areIntervalsOverlapping, max, min, addMinutes } from 'date-fns';
import { fromZonedTime, formatInTimeZone } from 'date-fns-tz';
import EventBlock from './EventBlock';
import { useTranslation } from 'react-i18next';
import { getDateLocale } from '../utils/dateLocale';
import { MASSEY_COLORS } from '../utils/constants';
import { useState, useRef, useCallback, useEffect } from 'react';

export default function EventRow({ date, events, onEventClick, onAddEvent, highlight, onDragStart, dragState, clashes, displayTimezone, onToggleTaskComplete, journalText, onSaveJournal }) {
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

    // ── Lane assignment for regular events ────────────────────────────────
    // Greedy algorithm: each event goes into the first available lane where
    // it doesn't overlap any existing event. Result: no visual overlap.
    const lanes = []; // lanes[i] = array of events already placed in lane i

    const processedWithLane = processedRegularEvents.map(ev => {
        let laneIdx = 0;
        while (true) {
            const lane = lanes[laneIdx];
            if (!lane) {
                lanes[laneIdx] = [ev];
                break;
            }
            const hasConflict = lane.some(existing =>
                areIntervalsOverlapping(
                    { start: existing.start, end: existing.end },
                    { start: ev.start, end: ev.end },
                    { inclusive: false }
                )
            );
            if (!hasConflict) {
                lane.push(ev);
                break;
            }
            laneIdx++;
        }
        return { ...ev, laneIdx };
    });

    const totalLanes = lanes.length || 1;

    const finalRegularEvents = processedWithLane.map(ev => ({
        ...ev,
        isConflicting: clashes ? clashes.some(c => c.eventId === ev.id) : false,
        // Lane height: divide the event area (below status strip) equally
        laneTopPct:    15 + (ev.laneIdx / totalLanes) * 85,
        laneHeightPct: (1 / totalLanes) * 85,
    }));

    const isWeekend = date.getDay() === 0 || date.getDay() === 6;

    // ── Journal hover popup ──────────────────────────────────────────────
    const [journalOpen, setJournalOpen] = useState(false);
    const [localJournal, setLocalJournal] = useState(journalText || '');
    const dateColRef = useRef(null);
    const popupRef = useRef(null);
    const saveTimerRef = useRef(null);

    // Keep local text in sync when parent prop changes (e.g. cross-window update)
    useEffect(() => { setLocalJournal(journalText || ''); }, [journalText]);

    const handleJournalChange = useCallback((e) => {
        const text = e.target.value;
        setLocalJournal(text);
        clearTimeout(saveTimerRef.current);
        saveTimerRef.current = setTimeout(() => {
            onSaveJournal?.(text);
        }, 500);
    }, [onSaveJournal]);

    // Close popup when clicking outside
    useEffect(() => {
        if (!journalOpen) return;
        const handler = (e) => {
            if (!popupRef.current?.contains(e.target) && !dateColRef.current?.contains(e.target)) {
                setJournalOpen(false);
            }
        };
        document.addEventListener('mousedown', handler);
        return () => document.removeEventListener('mousedown', handler);
    }, [journalOpen]);
    // Row height grows with lane count so events never overlap
    const ROW_BASE_PX   = 52;
    const LANE_HEIGHT_PX = 36;
    const rowHeightPx   = ROW_BASE_PX + (totalLanes - 1) * LANE_HEIGHT_PX;

    const handleGridClick = (e) => {
        if (e.target.closest('.event-block')) return;
        const rect = e.currentTarget.getBoundingClientRect();
        const clickMinutes = Math.round((24 * 60 * (e.clientX - rect.left) / rect.width) / 10) * 10;
        const clickDate = new Date(dayStart);
        clickDate.setMinutes(clickMinutes);
        onAddEvent?.(clickDate, addMinutes(clickDate, 60));
    };

    return (
        <div id={`row-${format(date, 'yyyy-MM-dd')}`}
             className={`event-row ${isWeekend ? 'event-row--weekend' : 'event-row--weekday'}`}
             style={{ minHeight: rowHeightPx }}
        >
            {/* Date Column */}
            <div
                className="event-row-date"
                ref={dateColRef}
                style={{ position: 'relative', cursor: 'pointer' }}
                onClick={() => setJournalOpen(v => !v)}
                title="随笔记录"
            >
                <span className="event-row-date-dow">{format(date, 'EEE', { locale })}</span>
                <span className={`event-row-date-num${isWeekend ? ' event-row-date-num--weekend' : ''}`}>
                    {format(date, 'd')}
                    <span style={{ fontSize: '10px', display: 'block', fontWeight: 400, letterSpacing: '0.05em', color: 'var(--clr-text-dim)', marginTop: '-2px' }}>
                        {format(date, 'MMM', { locale })}
                    </span>
                </span>
                {/* Dot indicator when journal has content */}
                {localJournal && (
                    <span style={{ display: 'block', width: 5, height: 5, borderRadius: '50%', background: 'var(--clr-gold, #C9A84C)', margin: '2px auto 0' }} />
                )}

                {/* Journal popup — same visual language as the widget */}
                {journalOpen && (
                    <div
                        ref={popupRef}
                        onClick={e => e.stopPropagation()}
                        style={{
                            position: 'absolute',
                            left: '100%',
                            top: 0,
                            zIndex: 200,
                            width: 240,
                            background: 'rgba(24,24,24,0.97)',
                            border: '1px solid #383838',
                            borderTop: '3px solid var(--clr-gold)',
                            borderRadius: 6,
                            padding: '8px 10px',
                            boxShadow: '0 6px 24px rgba(0,0,0,0.6)',
                            display: 'flex',
                            flexDirection: 'column',
                            gap: 6,
                        }}
                    >
                        <span style={{ fontSize: '9px', color: '#6B6355', letterSpacing: '0.14em', textTransform: 'uppercase' }}>
                            {format(date, 'M月d日', { locale })} · 随笔
                        </span>
                        <textarea
                            autoFocus
                            value={localJournal}
                            onChange={handleJournalChange}
                            placeholder="记录今天的想法…"
                            style={{
                                width: '100%',
                                minHeight: 80,
                                maxHeight: 160,
                                background: 'rgba(34,34,34,0.96)',
                                border: '1px solid #2D2D2D',
                                borderRadius: 4,
                                color: '#E8E0D0',
                                fontSize: '12px',
                                lineHeight: 1.5,
                                padding: '6px 8px',
                                resize: 'vertical',
                                fontFamily: 'inherit',
                                outline: 'none',
                                transition: 'border-color 120ms',
                            }}
                            onFocus={e => { e.target.style.borderColor = 'var(--clr-gold)'; }}
                            onBlur={e => { e.target.style.borderColor = '#2D2D2D'; }}
                        />
                    </div>
                )}
            </div>

            {/* Grid */}
            <div className="event-row-grid" onClick={handleGridClick}>
                {/* Status events (top strip) */}
                {statusEvents.length > 0 && (
                    <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: '15%', zIndex: 30, pointerEvents: 'none' }}>
                        {finalStatusEvents.map(ev => {
                            const tzInner = displayTimezone || Intl.DateTimeFormat().resolvedOptions().timeZone;
                            const toMins = str => { const [h, m] = str.split(':').map(Number); return h * 60 + m; };
                            let startMins, endMins;
                            try {
                                startMins = toMins(formatInTimeZone(ev.start, tzInner, 'HH:mm'));
                                endMins   = toMins(formatInTimeZone(ev.end,   tzInner, 'HH:mm'));
                                if (endMins < startMins) endMins += 1440;
                                if (endMins - startMins < 15) endMins = startMins + 15;
                            } catch {
                                startMins = ev.start.getHours() * 60 + ev.start.getMinutes();
                                endMins   = ev.end.getHours()   * 60 + ev.end.getMinutes();
                            }
                            const left  = (startMins / 1440) * 100;
                            const width = ((endMins - startMins) / 1440) * 100;
                            const colorIdx = ev.colorId ?? 0;
                            const colorVar = `var(--clr-event-${colorIdx}, ${MASSEY_COLORS[colorIdx] ?? MASSEY_COLORS[0]})`;
                            return (
                                <div key={ev.id}
                                    style={{
                                        position:        'absolute',
                                        backgroundColor: colorVar,
                                        left: `${left}%`, width: `${width}%`,
                                        top: `${ev.rowIndex * 18}px`, height: '16px',
                                        borderRadius:    2,
                                        borderLeft:      '3px solid rgba(255,255,255,0.3)',
                                        overflow:        'hidden',
                                        paddingLeft:     5,
                                        paddingRight:    4,
                                        // Vertically center the text inside the 16px strip
                                        display:         'flex',
                                        alignItems:      'center',
                                        pointerEvents:   'auto',
                                        cursor:          'pointer',
                                    }}
                                    onClick={e => { e.stopPropagation(); onEventClick(events.find(o => o.id === ev.id) || ev); }}
                                >
                                    <span style={{
                                        fontFamily:    'var(--font-display)',
                                        fontSize:      '9px',
                                        fontWeight:    600,
                                        letterSpacing: '0.06em',
                                        textTransform: 'uppercase',
                                        color:         '#fff',
                                        whiteSpace:    'nowrap',
                                        overflow:      'hidden',
                                        textOverflow:  'ellipsis',
                                    }}>
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

                {/* Lane separator lines (subtle) — only when >1 lane */}
                {totalLanes > 1 && Array.from({ length: totalLanes - 1 }).map((_, i) => {
                    const topPct = 15 + ((i + 1) / totalLanes) * 85;
                    return (
                        <div key={`lane-sep-${i}`} style={{
                            position: 'absolute', left: 0, right: 0,
                            top: `${topPct}%`, height: '1px',
                            background: 'rgba(255,255,255,0.04)',
                            pointerEvents: 'none', zIndex: 5,
                        }} />
                    );
                })}

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

                {/* Regular events — lane-positioned, no overlap */}
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
                                position: 'absolute',
                                top:    `calc(${event.laneTopPct}% + 2px)`,
                                height: `calc(${event.laneHeightPct}% - 4px)`,
                                zIndex: 10 + event.laneIdx,
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
