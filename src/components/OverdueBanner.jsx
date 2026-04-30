import { useState } from 'react';
import { AlertCircle, ChevronDown, ChevronUp } from 'lucide-react';
import { formatInTimeZone } from 'date-fns-tz';
import { useTranslation } from 'react-i18next';

export default function OverdueBanner({ events, onHighlight, travelTimezone }) {
    const { t } = useTranslation();
    const [isExpanded, setIsExpanded] = useState(false);

    const now = new Date();
    const overdueTasks = events.filter(e => e.type === 'task' && !e.completed && e.end < now);

    if (overdueTasks.length === 0) return null;

    const displayTasks = isExpanded ? overdueTasks : overdueTasks.slice(0, 3);
    const hasMore = overdueTasks.length > 3;

    return (
        <div className="banner banner--overdue overdue-banner">
            <AlertCircle size={15} style={{ flexShrink: 0, marginTop: 2, color: 'var(--clr-gold)' }} />
            <div style={{ flex: 1 }}>
                <p className="banner-title">{t('task.overdueDetected', 'Overdue Tasks')}</p>
                <ul style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 3, listStyle: 'none' }}>
                    {displayTasks.map(task => (
                        <li key={task.id}>
                            <button onClick={() => onHighlight({ type: 'overdue', start: new Date(task.start), end: new Date(task.end) })}
                                style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6, padding: 0, fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--clr-text)', textAlign: 'left' }}
                            >
                                <strong style={{ color: 'var(--clr-gold)' }}>{task.title}</strong>
                                <span className="banner-tag">
                                    {(() => {
                                        const tz = travelTimezone || 'Asia/Shanghai';
                                        return `${formatInTimeZone(task.start, tz, 'MMM d HH:mm')}–${formatInTimeZone(task.end, tz, 'HH:mm')}`;
                                    })()}
                                </span>
                            </button>
                        </li>
                    ))}
                </ul>
                {hasMore && (
                    <button onClick={() => setIsExpanded(!isExpanded)}
                        style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4, marginTop: 6, fontFamily: 'var(--font-display)', fontSize: '10px', letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--clr-gold-dim)' }}
                    >
                        {isExpanded
                            ? <><ChevronUp size={12} /> {t('actions.showLess', 'Show less')}</>
                            : <><ChevronDown size={12} /> {t('actions.showAll', { count: overdueTasks.length, defaultValue: `Show all (${overdueTasks.length})` })}</>
                        }
                    </button>
                )}
            </div>
        </div>
    );
}
