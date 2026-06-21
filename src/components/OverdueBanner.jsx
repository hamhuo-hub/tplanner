import { useState } from 'react';
import { AlertCircle, Clock, ChevronDown, ChevronUp } from 'lucide-react';
import { formatInTimeZone } from 'date-fns-tz';
import { differenceInCalendarDays, differenceInHours, addDays, endOfDay } from 'date-fns';
import { useTranslation } from 'react-i18next';

// deadline time: tasks use .end, reminders/events use .start
function deadline(ev) {
    return ev.type === 'task' ? ev.end : ev.start;
}

function daysLabel(ev, now, t) {
    const d = deadline(ev);
    const days = differenceInCalendarDays(d, now);
    const hours = differenceInHours(d, now);
    if (days === 0) return hours <= 0 ? t('task.dueNow', '到期') : t('task.dueToday', '今天到期');
    if (days === 1) return t('task.dueTomorrow', '明天到期');
    return t('task.dueInDays', { count: days, defaultValue: `还剩 ${days} 天` });
}

export default function OverdueBanner({ events, onHighlight, travelTimezone }) {
    const { t } = useTranslation();
    const [overdueExpanded, setOverdueExpanded] = useState(false);
    const [upcomingExpanded, setUpcomingExpanded] = useState(false);

    const now = new Date();
    const tz = travelTimezone || 'Asia/Shanghai';

    // Overdue: only tasks (reminders don't have a "completed" concept)
    const overdueTasks = events
        .filter(e => e.type === 'task' && !e.completed && e.end < now)
        .sort((a, b) => a.end - b.end);

    // Upcoming: tasks (not yet expired) + reminders/events (not yet started)，
    // 只看明后两天（今天剩余 + 明天），不应该把所有未来事项都列进来。
    const upcomingCutoff = endOfDay(addDays(now, 1));
    const upcomingTasks = events
        .filter(e => {
            if (e.type === 'status') return false;
            const d = deadline(e);
            if (d > upcomingCutoff) return false;
            if (e.type === 'task') return !e.completed && e.end >= now;
            // event/reminder: show if start is in the future
            return e.start >= now;
        })
        .sort((a, b) => deadline(a) - deadline(b))
        .slice(0, 20);

    if (overdueTasks.length === 0 && upcomingTasks.length === 0) return null;

    const displayOverdue = overdueExpanded ? overdueTasks : overdueTasks.slice(0, 3);
    const displayUpcoming = upcomingExpanded ? upcomingTasks : upcomingTasks.slice(0, 3);

    return (
        <>
            {/* ── Overdue section ── */}
            {overdueTasks.length > 0 && (
                <div className="banner banner--overdue overdue-banner">
                    <AlertCircle size={15} style={{ flexShrink: 0, marginTop: 2, color: 'var(--clr-gold)' }} />
                    <div style={{ flex: 1 }}>
                        <p className="banner-title">{t('task.overdueDetected', '已逾期任务')}</p>
                        <ul style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 3, listStyle: 'none' }}>
                            {displayOverdue.map(task => (
                                <li key={task.id}>
                                    <button
                                        onClick={() => onHighlight({ type: 'overdue', start: new Date(task.start), end: new Date(task.end) })}
                                        style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6, padding: 0, fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--clr-text)', textAlign: 'left' }}
                                    >
                                        <strong style={{ color: 'var(--clr-gold)' }}>{task.title}</strong>
                                        <span className="banner-tag">
                                            {`${formatInTimeZone(task.end, tz, 'MMM d HH:mm')}`}
                                        </span>
                                        <span className="banner-tag" style={{ color: 'var(--clr-red, #C0392B)' }}>
                                            {t('task.overdue', '已逾期')}
                                        </span>
                                    </button>
                                </li>
                            ))}
                        </ul>
                        {overdueTasks.length > 3 && (
                            <button onClick={() => setOverdueExpanded(!overdueExpanded)}
                                style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4, marginTop: 6, fontFamily: 'var(--font-display)', fontSize: '10px', letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--clr-gold-dim)' }}
                            >
                                {overdueExpanded
                                    ? <><ChevronUp size={12} /> {t('actions.showLess')}</>
                                    : <><ChevronDown size={12} /> {t('actions.showAll', { count: overdueTasks.length })}</>
                                }
                            </button>
                        )}
                    </div>
                </div>
            )}

            {/* ── Upcoming countdown section ── */}
            {upcomingTasks.length > 0 && (
                <div className="banner banner--upcoming upcoming-banner">
                    <Clock size={15} style={{ flexShrink: 0, marginTop: 2, color: 'var(--clr-teal, #4A9DA8)' }} />
                    <div style={{ flex: 1 }}>
                        <p className="banner-title">{t('task.upcoming', '即将截止')}</p>
                        <ul style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 3, listStyle: 'none' }}>
                            {displayUpcoming.map(task => (
                                <li key={task.id}>
                                    <button
                                        onClick={() => onHighlight({ type: 'overdue', start: new Date(task.start), end: new Date(task.end) })}
                                        style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6, padding: 0, fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--clr-text)', textAlign: 'left' }}
                                    >
                                        <strong style={{ color: 'var(--clr-teal, #4A9DA8)' }}>{task.title}</strong>
                                        <span className="banner-tag">
                                            {formatInTimeZone(deadline(task), tz, 'MMM d HH:mm')}
                                        </span>
                                        <span className="banner-tag" style={{ background: 'rgba(74,157,168,0.15)', color: 'var(--clr-teal, #4A9DA8)', border: '1px solid rgba(74,157,168,0.3)' }}>
                                            {daysLabel(task, now, t)}
                                        </span>
                                    </button>
                                </li>
                            ))}
                        </ul>
                        {upcomingTasks.length > 3 && (
                            <button onClick={() => setUpcomingExpanded(!upcomingExpanded)}
                                style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4, marginTop: 6, fontFamily: 'var(--font-display)', fontSize: '10px', letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--clr-teal, #4A9DA8)' }}
                            >
                                {upcomingExpanded
                                    ? <><ChevronUp size={12} /> {t('actions.showLess')}</>
                                    : <><ChevronDown size={12} /> {t('actions.showAll', { count: upcomingTasks.length })}</>
                                }
                            </button>
                        )}
                    </div>
                </div>
            )}
        </>
    );
}
