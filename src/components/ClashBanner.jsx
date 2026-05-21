import { useState } from 'react';
import { AlertTriangle, ChevronDown, ChevronUp } from 'lucide-react';
import { formatInTimeZone } from 'date-fns-tz';
import { useTranslation } from 'react-i18next';

export default function ClashBanner({ clashes, events, onHighlight, travelTimezone }) {
    const { t } = useTranslation();
    const [isExpanded, setIsExpanded] = useState(false);

    if (!clashes || clashes.length === 0) return null;

    const getTitle = id => events.find(e => e.id === id)?.title || 'Unknown';
    const seen = new Set();
    const uniqueClashes = clashes.reduce((acc, c) => {
        const pair = [c.eventId, c.clashWithId].sort().join('-');
        if (!seen.has(pair)) {
            seen.add(pair);
            acc.push({ id: pair, eventId: c.eventId, eventA: getTitle(c.eventId), eventB: getTitle(c.clashWithId), start: c.start, end: c.end });
        }
        return acc;
    }, []);

    const displayClashes = isExpanded ? uniqueClashes : uniqueClashes.slice(0, 3);
    const hasMore = uniqueClashes.length > 3;

    return (
        <div className="banner banner--clash clash-banner">
            <AlertTriangle size={15} style={{ flexShrink: 0, marginTop: 2, color: 'var(--clr-red)' }} />
            <div style={{ flex: 1 }}>
                <p className="banner-title">{t('clash.detected')}</p>
                <ul style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 3, listStyle: 'none' }}>
                    {displayClashes.map(clash => (
                        <li key={clash.id}>
                            <button onClick={() => onHighlight({ type: 'clash', start: new Date(clash.start), end: new Date(clash.end) })}
                                style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6, padding: 0, fontFamily: 'var(--font-mono)', fontSize: '11px', color: '#E8C8C4', textAlign: 'left' }}
                            >
                                <span>
                                    <strong>{clash.eventA}</strong>
                                    <span style={{ color: 'var(--clr-red)', margin: '0 6px' }}>×</span>
                                    <strong>{clash.eventB}</strong>
                                </span>
                                <span className="banner-tag">
                                    {(() => {
                                        const tz = travelTimezone || 'Asia/Shanghai';
                                        return `${formatInTimeZone(clash.start, tz, 'HH:mm')}–${formatInTimeZone(clash.end, tz, 'HH:mm')}`;
                                    })()}
                                </span>
                            </button>
                        </li>
                    ))}
                </ul>
                {hasMore && (
                    <button onClick={() => setIsExpanded(!isExpanded)}
                        style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4, marginTop: 6, fontFamily: 'var(--font-display)', fontSize: '10px', letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--clr-red)' }}
                    >
                        {isExpanded
                            ? <><ChevronUp size={12} /> {t('actions.showLess', 'Show less')}</>
                            : <><ChevronDown size={12} /> {t('actions.showAll', { count: uniqueClashes.length, defaultValue: `Show all (${uniqueClashes.length})` })}</>
                        }
                    </button>
                )}
            </div>
        </div>
    );
}
