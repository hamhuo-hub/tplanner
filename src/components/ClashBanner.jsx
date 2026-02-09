import { AlertTriangle } from 'lucide-react';
import { format } from 'date-fns';

import { useTranslation } from 'react-i18next';

/**
 * @param {Object} props
 * @param {import('../utils/constants').Clash[]} props.clashes
 */
export default function ClashBanner({ clashes, events, onHighlight }) {
    const { t } = useTranslation();
    if (!clashes || clashes.length === 0) return null;

    // Helper to get event title
    const getTitle = (id) => events.find(e => e.id === id)?.title || 'Unknown Event';

    const uniqueClashes = [];
    const seenPairs = new Set();

    clashes.forEach(c => {
        const pair = [c.eventId, c.clashWithId].sort().join('-');
        if (!seenPairs.has(pair)) {
            seenPairs.add(pair);
            uniqueClashes.push({
                id: pair,
                eventId: c.eventId,
                eventA: getTitle(c.eventId),
                eventB: getTitle(c.clashWithId),
                start: c.start,
                end: c.end
            });
        }
    });

    return (
        <div className="bg-red-100 border-l-4 border-red-500 text-red-700 p-4 mb-4 shadow-md animate-in slide-in-from-top duration-300">
            <div className="flex items-start">
                <AlertTriangle className="w-6 h-6 mr-3 mt-1 flex-shrink-0" />
                <div>
                    <p className="font-bold">{t('clash.detected')}</p>
                    <ul className="mt-2 text-sm list-disc list-inside space-y-1">
                        {uniqueClashes.map(clash => (
                            <li key={clash.id}>
                                <button
                                    onClick={() => {
                                        // Delegate jump logic to parent (App.jsx)
                                        // App.jsx will handle view expansion and scrolling
                                        onHighlight({
                                            type: 'clash',
                                            start: new Date(clash.start),
                                            end: new Date(clash.end)
                                        });
                                    }}
                                    className="hover:underline text-left"
                                >
                                    <span className="font-medium">{clash.eventA}</span> - <span className="font-medium">{clash.eventB}</span>
                                    <span className="ml-2 text-xs text-red-800 bg-red-200 px-1 rounded">
                                        {format(clash.start, 'HH:mm')} - {format(clash.end, 'HH:mm')}
                                    </span>
                                </button>
                            </li>
                        ))}
                    </ul>
                </div>
            </div>
        </div>
    );
}
