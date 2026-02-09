import { format } from 'date-fns';
import { useTranslation } from 'react-i18next';
import { MASSEY_COLORS } from '../utils/constants';
import { X } from 'lucide-react';
import { getDateLocale } from '../utils/dateLocale';

/**
 * @param {Object} props
 * @param {import('../utils/constants').Event | null} props.event
 * @param {Function} props.onClose
 * @param {Function} [props.onDelete]
 */
export default function EventDetailsModal({ event, onClose, onDelete, onEdit }) {
    const { t, i18n } = useTranslation();
    const locale = getDateLocale(i18n.language);

    if (!event) return null;

    const colorClass = MASSEY_COLORS[event.colorId] || MASSEY_COLORS[0];

    return (
        <div className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-4">
            <div className="bg-white rounded-lg shadow-xl w-full max-w-md overflow-hidden animate-in fade-in zoom-in duration-200">
                {/* Header with color */}
                <div className={`h-24 ${colorClass} relative flex justify-end p-2`}>
                    <button
                        onClick={onClose}
                        className="text-white/80 hover:text-white bg-black/10 hover:bg-black/20 rounded-full p-1"
                    >
                        <X className="w-5 h-5" />
                    </button>
                </div>

                <div className="p-6">
                    <h2 className="text-2xl font-bold text-gray-800 mb-2">{event.title}</h2>

                    <div className="space-y-4">
                        <div>
                            <p className="text-sm font-medium text-gray-500">Time</p>
                            <p className="text-gray-700">
                                {format(event.start, 'EEEE, d MMMM yyyy', { locale })}
                                {format(event.start, 'yyyy-MM-dd') !== format(event.end, 'yyyy-MM-dd') && (
                                    <> - {format(event.end, 'EEEE, d MMMM yyyy', { locale })}</>
                                )}
                                <br />
                                {format(event.start, 'HH:mm')} - {format(event.end, 'HH:mm')}
                            </p>
                        </div>

                        {event.note && (
                            <div>
                                <p className="text-sm font-medium text-gray-500">{t('event.note')}</p>
                                <p className="text-gray-700 whitespace-pre-wrap">{event.note}</p>
                            </div>
                        )}
                    </div>

                    <div className="mt-8 flex justify-end gap-3">
                        {onDelete && (
                            <button
                                onClick={() => {
                                    if (confirm('Are you sure you want to delete this event?')) {
                                        onDelete(event.id);
                                        onClose();
                                    }
                                }}
                                className="px-4 py-2 text-sm font-medium text-red-600 border border-red-200 rounded-md hover:bg-red-50"
                            >
                                {t('actions.delete')}
                            </button>
                        )}
                        {onEdit && (
                            <button
                                onClick={() => {
                                    onEdit(event);
                                    onClose();
                                }}
                                className="px-4 py-2 text-sm font-medium text-blue-600 border border-blue-200 rounded-md hover:bg-blue-50"
                            >
                                {t('actions.edit')}
                            </button>
                        )}
                        <button
                            onClick={onClose}
                            className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-md hover:bg-blue-700 shadow-sm"
                        >
                            Close
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}
