import { AlertTriangle } from 'lucide-react';
import { format } from 'date-fns';

/**
 * @param {Object} props
 * @param {import('../utils/constants').Clash[]} props.clashes
 */
export default function ClashBanner({ clashes, events, onHighlight }) {
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
                    <p className="font-bold">Schedule Conflict Detected</p>
                    <ul className="mt-2 text-sm list-disc list-inside space-y-1">
                        {uniqueClashes.map(clash => (
                            <li key={clash.id}>
                                <button
                                    onClick={() => {
                                        // Scroll to the start date of the clash
                                        // We need the date string yyyy-MM-dd
                                        const clashDate = new Date(events.find(e => e.id === clash.eventId)?.start);
                                        const dateStr = clashDate.toISOString().split('T')[0];
                                        const element = document.getElementById(`row-${dateStr}`);
                                        if (element) {
                                            element.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                            // Trigger highlight
                                            onHighlight({
                                                type: 'clash',
                                                start: new Date(clash.start),
                                                end: new Date(clash.end)
                                            });
                                        } else {
                                            alert('Conflict is outside current view. Please navigate to it.');
                                        }
                                    }}
                                    className="hover:underline text-left"
                                >
                                    <span className="font-medium">{clash.eventA}</span> conflicts with <span className="font-medium">{clash.eventB}</span>
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
