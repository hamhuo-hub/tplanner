import { useState, useEffect } from 'react';
import { format } from 'date-fns';
import { MAX_LENGTH_TITLE, MASSEY_COLORS } from '../utils/constants';

/**
 * @param {Object} props
 * @param {boolean} props.isOpen
 * @param {Function} props.onClose
 * @param {Function} props.onSave - (event) => void
 * @param {Date} [props.defaultDate]
 */
export default function AddEventModal({ isOpen, onClose, onSave, defaultDate, initialEvent }) {
    const [title, setTitle] = useState('');
    const [start, setStart] = useState('');
    const [end, setEnd] = useState('');
    const [note, setNote] = useState('');
    const [colorId, setColorId] = useState(0);

    useEffect(() => {
        if (isOpen) {
            if (initialEvent) {
                // Edit Mode
                setTitle(initialEvent.title);
                setStart(format(initialEvent.start, "yyyy-MM-dd'T'HH:mm"));
                setEnd(format(initialEvent.end, "yyyy-MM-dd'T'HH:mm"));
                setNote(initialEvent.note || '');
                setColorId(initialEvent.colorId);
            } else {
                // Create Mode
                const now = defaultDate || new Date();
                // Round up for nice defaults
                if (!defaultDate) {
                    now.setMinutes(0, 0, 0);
                    now.setHours(now.getHours() + 1);
                } else {
                    // If defaultDate given (click on grid), it might be exact.
                    // But usually grid click passes a start time.
                }

                const startTime = format(now, "yyyy-MM-dd'T'HH:mm");
                // Default 1 hour duration
                const endTime = format(new Date(now.getTime() + 60 * 60 * 1000), "yyyy-MM-dd'T'HH:mm");

                setStart(startTime);
                setEnd(endTime);
                setTitle('');
                setNote('');
                setColorId(0);
            }
        }
    }, [isOpen, defaultDate, initialEvent]);

    if (!isOpen) return null;

    const handleSubmit = (e) => {
        e.preventDefault();
        if (!title || !start || !end) return;

        onSave({
            id: crypto.randomUUID(),
            title,
            start: new Date(start),
            end: new Date(end),
            note,
            colorId
        });
        onClose();
    };

    return (
        <div className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center">
            <div className="bg-white rounded-lg shadow-xl p-6 w-full max-w-md">
                <h2 className="text-xl font-bold mb-4">{initialEvent ? 'Edit Event' : 'Add New Event'}</h2>

                <form onSubmit={handleSubmit} className="space-y-4">
                    <div>
                        <label className="block text-sm font-medium text-gray-700">Title</label>
                        <input
                            type="text"
                            required
                            value={title}
                            onChange={e => setTitle(e.target.value)}
                            maxLength={MAX_LENGTH_TITLE}
                            className="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 border p-2"
                        />
                    </div>

                    <div className="grid grid-cols-2 gap-4">
                        <div>
                            <label className="block text-sm font-medium text-gray-700">Start</label>
                            <input
                                type="datetime-local"
                                required
                                value={start}
                                onChange={e => setStart(e.target.value)}
                                className="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 border p-2"
                            />
                        </div>
                        <div>
                            <label className="block text-sm font-medium text-gray-700">End</label>
                            <input
                                type="datetime-local"
                                required
                                value={end}
                                onChange={e => setEnd(e.target.value)}
                                className="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 border p-2"
                            />
                        </div>
                    </div>

                    <div>
                        <label className="block text-sm font-medium text-gray-700">Note</label>
                        <textarea
                            value={note}
                            onChange={e => setNote(e.target.value)}
                            className="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 border p-2"
                        />
                    </div>

                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">Color</label>
                        <div className="flex gap-2">
                            {MASSEY_COLORS.map((c, i) => (
                                <button
                                    key={i}
                                    type="button"
                                    onClick={() => setColorId(i)}
                                    className={`w-8 h-8 rounded-full ${c} ${colorId === i ? 'ring-2 ring-offset-2 ring-gray-400' : ''}`}
                                />
                            ))}
                        </div>
                    </div>

                    <div className="flex justify-end gap-2 mt-6">
                        <button
                            type="button"
                            onClick={onClose}
                            className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50"
                        >
                            Cancel
                        </button>
                        <button
                            type="submit"
                            className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-md hover:bg-blue-700"
                        >
                            Save
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}
