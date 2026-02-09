import { useState, useMemo, useEffect } from 'react'
import { format } from 'date-fns'
import { useTranslation } from 'react-i18next'
import Timeline from './components/Timeline'
import AddEventModal from './components/AddEventModal'
import EventDetailsModal from './components/EventDetailsModal'
import ClashBanner from './components/ClashBanner'
import { calculateTimelineRange, checkForClashes } from './utils/dateUtils'
import { EVENTS_STORAGE_KEY } from './utils/constants'
import { Plus, Languages } from 'lucide-react'

function App() {
    const { t, i18n } = useTranslation();
    const [events, setEvents] = useState([]);
    const [highlight, setHighlight] = useState(null); // { type: 'clash' | 'today', start: Date, end: Date }

    const [isAddModalOpen, setIsAddModalOpen] = useState(false);
    const [selectedEvent, setSelectedEvent] = useState(null);
    const [modalDefaultDate, setModalDefaultDate] = useState(null);
    const [editingEvent, setEditingEvent] = useState(null);

    // Fetch initial data
    useEffect(() => {
        fetch('http://localhost:3001/api/events')
            .then(res => res.json())
            .then(data => {
                const hydrated = data.map(e => ({
                    ...e,
                    start: new Date(e.start),
                    end: new Date(e.end)
                }));
                setEvents(hydrated);
            })
            .catch(err => console.error('Failed to fetch events', err));
    }, []);

    // Save data on change (debounced)
    useEffect(() => {
        if (events.length === 0) return; // Don't save empty if initial load hasn't happened

        const timer = setTimeout(() => {
            fetch('http://localhost:3001/api/events', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(events)
            }).catch(err => console.error('Failed to save events', err));
        }, 1000);

        return () => clearTimeout(timer);
    }, [events]);

    const [referenceDate, setReferenceDate] = useState(null);

    const { startDate, endDate } = useMemo(() => calculateTimelineRange(events, referenceDate), [events, referenceDate]);
    const clashes = useMemo(() => checkForClashes(events), [events]);

    // Pagination handlers
    const handlePrevPage = () => {
        const newDate = new Date(startDate);
        newDate.setMonth(newDate.getMonth() - 2);
        setReferenceDate(newDate);
    };

    const handleNextPage = () => {
        const newDate = new Date(startDate);
        newDate.setMonth(newDate.getMonth() + 2);
        setReferenceDate(newDate);
    };

    const handleToday = () => {
        const now = new Date();
        setReferenceDate(now);
        // Wait for render
        setTimeout(() => {
            const dateStr = format(now, 'yyyy-MM-dd');
            const element = document.getElementById(`row-${dateStr}`);
            if (element) {
                element.scrollIntoView({ behavior: 'smooth', block: 'center' });
                // Highlight Today
                const startOfDay = new Date(now); startOfDay.setHours(0, 0, 0, 0);
                const endOfDay = new Date(now); endOfDay.setHours(23, 59, 59, 999);
                setHighlight({
                    type: 'today',
                    start: startOfDay,
                    end: endOfDay
                });
                setTimeout(() => setHighlight(null), 3000);
            }
        }, 100);
    };

    const handleSaveEvent = (eventData) => {
        if (editingEvent) {
            // Update existing
            setEvents(prev => prev.map(e => e.id === editingEvent.id ? { ...eventData, id: editingEvent.id } : e));
            setEditingEvent(null);
        } else {
            // Create new
            setEvents(prev => [...prev, eventData]);
        }
        setIsAddModalOpen(false);
    };

    const handleDeleteEvent = (id) => {
        setEvents(prev => prev.filter(e => e.id !== id));
        setSelectedEvent(null);
    }

    // Triggered by clicking on the timeline grid
    const handleTimelineClick = (start) => {
        setModalDefaultDate(start);
        setEditingEvent(null);
        setIsAddModalOpen(true);
    };

    const openAddModal = () => {
        setModalDefaultDate(new Date());
        setEditingEvent(null);
        setIsAddModalOpen(true);
    };

    const handleEditEvent = (event) => {
        setEditingEvent(event);
        setIsAddModalOpen(true);
    };

    const toggleLanguage = () => {
        const newLang = i18n.language === 'en' ? 'zh' : 'en';
        i18n.changeLanguage(newLang);
    };

    return (
        <div className="h-screen flex flex-col bg-gray-100 overflow-hidden">
            <header className="bg-white shadow-sm px-6 py-4 flex justify-between items-center z-50">
                <h1 className="text-xl font-bold text-gray-800">{t('app.title')}</h1>
                <div className="flex items-center gap-2">
                    <div className="flex bg-gray-100 rounded-md p-1 mr-4">
                        <button onClick={handlePrevPage} className="px-3 py-1 text-sm font-medium text-gray-600 hover:bg-white hover:shadow-sm rounded transition-all">
                            &lt; {t('nav.prev')}
                        </button>
                        <button onClick={handleToday} className="px-3 py-1 text-sm font-medium text-gray-600 hover:bg-white hover:shadow-sm rounded transition-all">
                            {t('nav.today')}
                        </button>
                        <button onClick={handleNextPage} className="px-3 py-1 text-sm font-medium text-gray-600 hover:bg-white hover:shadow-sm rounded transition-all">
                            {t('nav.next')} &gt;
                        </button>
                    </div>

                    <button
                        onClick={toggleLanguage}
                        className="flex items-center gap-2 text-gray-600 hover:text-gray-900 font-medium px-3 py-2 text-sm"
                        title="Switch Language"
                    >
                        <Languages className="w-4 h-4" />
                        {i18n.language === 'en' ? '中文' : 'English'}
                    </button>

                    <button
                        onClick={() => {
                            const dataStr = JSON.stringify(events, null, 2);
                            const blob = new Blob([dataStr], { type: "application/json" });
                            const url = URL.createObjectURL(blob);
                            const link = document.createElement("a");
                            link.href = url;
                            link.download = "tplanner-data.json";
                            document.body.appendChild(link);
                            link.click();
                            document.body.removeChild(link);
                        }}
                        className="text-gray-600 hover:text-gray-900 font-medium px-3 py-2 text-sm"
                    >
                        {t('actions.export')}
                    </button>
                    <label className="text-gray-600 hover:text-gray-900 font-medium px-3 py-2 text-sm cursor-pointer">
                        {t('actions.import')}
                        <input
                            type="file"
                            accept=".json"
                            className="hidden"
                            onChange={(e) => {
                                const file = e.target.files[0];
                                if (!file) return;
                                const reader = new FileReader();
                                reader.onload = (ev) => {
                                    try {
                                        const parsed = JSON.parse(ev.target.result);
                                        // Basic validation
                                        if (Array.isArray(parsed)) {
                                            const hydrated = parsed.map(ev => ({
                                                ...ev,
                                                start: new Date(ev.start),
                                                end: new Date(ev.end)
                                            }));
                                            setEvents(hydrated);
                                            // Reset view to start of first event
                                            if (hydrated.length > 0) {
                                                const sorted = [...hydrated].sort((a, b) => a.start - b.start);
                                                setReferenceDate(sorted[0].start);
                                            }
                                            alert(t('messages.importSuccess'));
                                        } else {
                                            alert(t('messages.importError'));
                                        }
                                    } catch (err) {
                                        console.error(err);
                                        alert(t('messages.parseError'));
                                    }
                                };
                                reader.readAsText(file);
                            }}
                        />
                    </label>
                    <button
                        onClick={openAddModal}
                        className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-4 py-2 rounded-md shadow-sm transition-colors"
                    >
                        <Plus className="w-4 h-4" />
                        {t('actions.addEvent')}
                    </button>
                </div>
            </header>

            <main className="flex-grow flex flex-col p-4 overflow-hidden">
                <ClashBanner
                    clashes={clashes}
                    events={events}
                    onHighlight={(h) => {
                        setHighlight(h);
                        // Clear highlight after animation
                        setTimeout(() => setHighlight(null), 3000);
                    }}
                />
                <Timeline
                    startDate={startDate}
                    endDate={endDate}
                    events={events}
                    onEventClick={setSelectedEvent}
                    onAddEvent={handleTimelineClick}
                    highlight={highlight}
                />
            </main>

            <AddEventModal
                isOpen={isAddModalOpen}
                onClose={() => setIsAddModalOpen(false)}
                onSave={handleSaveEvent}
                defaultDate={modalDefaultDate}
                initialEvent={editingEvent}
            />

            <EventDetailsModal
                event={selectedEvent}
                onClose={() => setSelectedEvent(null)}
                onDelete={handleDeleteEvent}
                onEdit={handleEditEvent}
            />
        </div>
    )
}

export default App
