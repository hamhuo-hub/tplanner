import { useState } from 'react';
import { AlertCircle, ChevronDown, ChevronUp } from 'lucide-react';
import { formatInTimeZone } from 'date-fns-tz';

import { useTranslation } from 'react-i18next';

/**
 * @param {Object} props
 * @param {import('../utils/constants').Event[]} props.events
 */
export default function OverdueBanner({ events, onHighlight, travelTimezone }) {
    const { t } = useTranslation();
    const [isExpanded, setIsExpanded] = useState(false);

    // Calculate currently overdue tasks.
    const now = new Date();
    const overdueTasks = events.filter(e => e.type === 'task' && !e.completed && e.end < now);

    if (overdueTasks.length === 0) return null;

    const displayTasks = isExpanded ? overdueTasks : overdueTasks.slice(0, 3);
    const hasMore = overdueTasks.length > 3;

    return (
        <div className="bg-orange-100 border-l-4 border-orange-500 text-orange-800 p-4 mb-4 shadow-md animate-in slide-in-from-top duration-300">
            <div className="flex items-start">
                <AlertCircle className="w-6 h-6 mr-3 mt-1 flex-shrink-0" />
                <div className="flex-grow">
                    <p className="font-bold">{t('task.overdueDetected', 'Overdue Tasks Detected')}</p>
                    <ul className="mt-2 text-sm list-disc list-inside space-y-1">
                        {displayTasks.map(task => (
                            <li key={task.id}>
                                <button
                                    onClick={() => {
                                        onHighlight({
                                            type: 'overdue',
                                            start: new Date(task.start),
                                            end: new Date(task.end)
                                        });
                                    }}
                                    className="hover:underline text-left inline-flex items-center"
                                >
                                    <span className="font-medium mr-2">{task.title}</span>
                                    <span className="text-xs text-orange-900 bg-orange-200 px-1 rounded whitespace-nowrap">
                                        {(() => {
                                            const displayTz = travelTimezone || 'Asia/Shanghai';
                                            return `${formatInTimeZone(task.start, displayTz, 'MMM d HH:mm')} - ${formatInTimeZone(task.end, displayTz, 'HH:mm')}`;
                                        })()}
                                    </span>
                                </button>
                            </li>
                        ))}
                    </ul>
                    {hasMore && (
                        <button
                            onClick={() => setIsExpanded(!isExpanded)}
                            className="mt-2 flex items-center text-sm font-medium hover:text-orange-900 transition-colors"
                        >
                            {isExpanded ? (
                                <><ChevronUp className="w-4 h-4 mr-1" /> {t('actions.showLess', 'Show less')}</>
                            ) : (
                                <><ChevronDown className="w-4 h-4 mr-1" /> {t('actions.showAll', { count: overdueTasks.length, defaultValue: `Show all (${overdueTasks.length})` })}</>
                            )}
                        </button>
                    )}
                </div>
            </div>
        </div>
    );
}
