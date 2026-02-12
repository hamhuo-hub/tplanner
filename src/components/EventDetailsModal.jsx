import { format } from 'date-fns';
import { useTranslation } from 'react-i18next';
import { MASSEY_COLORS } from '../utils/constants';
import { X, CheckCircle, Circle } from 'lucide-react';
import { getDateLocale } from '../utils/dateLocale';

/**
 * @param {Object} props
 * @param {import('../utils/constants').Event | null} props.event
 * @param {Function} props.onClose
 * @param {Function} [props.onDelete]
 * @param {Function} [props.onEdit]
 */
export default function EventDetailsModal({ event, onClose, onDelete, onEdit, onSave }) {
    const { t, i18n } = useTranslation();
    const locale = getDateLocale(i18n.language);

    if (!event) return null;

    const color = MASSEY_COLORS[event.colorId] || MASSEY_COLORS[0];

    return (
        <div className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-4" onClick={onClose}>
            <div
                className="bg-white rounded-lg shadow-xl w-full max-w-md overflow-hidden animate-in fade-in zoom-in duration-200"
                onClick={(e) => e.stopPropagation()}
            >
                {/* Header with color - reduced height to half of original (h-12) */}
                <div
                    className={`h-12 relative flex justify-end p-2`}
                    style={{ backgroundColor: color }}
                >
                    <button
                        onClick={onClose}
                        className="text-white/80 hover:text-white bg-black/10 hover:bg-black/20 rounded-full p-1"
                        title={t('actions.close')}
                    >
                        <X className="w-5 h-5" />
                    </button>
                </div>

                <div className="p-6 relative">
                    <h2 className="text-xl font-bold text-gray-800 mb-4 pr-8">{event.title}</h2>

                    <div className="space-y-4">
                        <div>
                            <p className="text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1">{t('event.timeLabel')}</p>
                            <p className="text-gray-700 text-sm">
                                {format(event.start, 'EEEE, d MMMM yyyy', { locale })}
                                <br />
                                <span className="font-medium">
                                    {format(event.start, 'HH:mm')} - {format(event.end, 'HH:mm')}
                                </span>
                            </p>
                        </div>

                        {event.note && (
                            <div>
                                <p className="text-xs font-semibold uppercase tracking-wider text-gray-500 mb-1">{t('event.note')}</p>
                                {/* Max height 300px as requested */}
                                <div className="text-gray-700 text-sm whitespace-pre-wrap max-h-[300px] overflow-y-auto border border-gray-100 rounded p-2 bg-gray-50">
                                    {event.note}
                                </div>
                            </div>
                        )}

                        {event.checklist && event.checklist.length > 0 && (
                            <div>
                                <div className="flex justify-between items-center mb-1">
                                    <p className="text-xs font-semibold uppercase tracking-wider text-gray-500">{t('event.checklist', 'Checklist')}</p>
                                    <span className="text-xs text-gray-400">
                                        {event.checklist.filter(i => i.completed).length}/{event.checklist.length}
                                    </span>
                                </div>
                                <div className="space-y-1">
                                    {event.checklist.map((item, idx) => (
                                        <div
                                            key={item.id || idx}
                                            className="flex items-start gap-2 p-2 rounded hover:bg-gray-50 cursor-pointer group"
                                            onClick={() => {
                                                if (onSave) {
                                                    const newChecklist = [...event.checklist];
                                                    newChecklist[idx] = { ...item, completed: !item.completed };
                                                    onSave({ ...event, checklist: newChecklist });
                                                }
                                            }}
                                        >
                                            <div className={`mt-0.5 ${item.completed ? 'text-green-500' : 'text-gray-300 group-hover:text-gray-400'}`}>
                                                {item.completed ? <CheckCircle size={18} /> : <Circle size={18} />}
                                            </div>
                                            <span className={`text-sm ${item.completed ? 'text-gray-400 line-through' : 'text-gray-700'}`}>
                                                {item.text}
                                            </span>
                                        </div>
                                    ))}
                                </div>
                            </div>
                        )}
                    </div>

                    <div className="mt-6 flex justify-end gap-3 border-t border-gray-100 pt-4">
                        {onDelete && (
                            <button
                                onClick={() => {
                                    if (confirm(t('messages.deleteConfirmation'))) {
                                        onDelete(event.id);
                                        onClose();
                                    }
                                }}
                                className="px-3 py-1.5 text-xs font-medium text-red-600 border border-red-200 rounded hover:bg-red-50 transition-colors"
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
                                className="px-3 py-1.5 text-xs font-medium text-blue-600 border border-blue-200 rounded hover:bg-blue-50 transition-colors"
                            >
                                {t('actions.edit')}
                            </button>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
}
