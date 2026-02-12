import { useState, useMemo, useEffect } from 'react'
import { format } from 'date-fns'
import { useTranslation } from 'react-i18next'
import Timeline from './components/Timeline'
import AddEventModal from './components/AddEventModal'
import EventDetailsModal from './components/EventDetailsModal'
import ClashBanner from './components/ClashBanner'
import { checkForClashes } from './utils/dateUtils'
import { Plus, Languages, Printer } from 'lucide-react'

function App() {
    const { t, i18n } = useTranslation();
    const [events, setEvents] = useState([]);
    const [highlight, setHighlight] = useState(null); // { type: 'clash' | 'today', start: Date, end: Date }

    const [isAddModalOpen, setIsAddModalOpen] = useState(false);
    const [selectedEvent, setSelectedEvent] = useState(null);
    const [modalDefaultDate, setModalDefaultDate] = useState(null);
    const [editingEvent, setEditingEvent] = useState(null);
    const [isLoaded, setIsLoaded] = useState(false);

    // Fetch initial data
    useEffect(() => {
        // Add timestamp to prevent caching
        fetch(`http://localhost:3001/api/events?t=${new Date().getTime()}`)
            .then(res => res.json())
            .then(data => {
                const hydrated = data.map(e => ({
                    ...e,
                    start: new Date(e.start),
                    end: new Date(e.end)
                }));
                setEvents(hydrated);
                setIsLoaded(true);
            })
            .catch(err => console.error('Failed to fetch events', err));
    }, []);

    // Save data on change (debounced)
    useEffect(() => {
        if (!isLoaded) return; // Don't save if not yet loaded (prevents overwriting with empty array on init)

        const timer = setTimeout(() => {
            fetch('http://localhost:3001/api/events', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(events)
            }).catch(err => console.error('Failed to save events', err));
        }, 1000);

        return () => clearTimeout(timer);
    }, [events, isLoaded]);

    const [viewRange, setViewRange] = useState({ start: null, end: null });

    // Initialize view range on load or when events change significantly?
    // Actually, we want to start at Today or Earliest Event.
    useEffect(() => {
        if (!viewRange.start) {
            // Initial load or Reset
            // Default: Today - 7 days to Today + 30 days
            const today = new Date();
            today.setHours(0, 0, 0, 0);

            const start = new Date(today);
            start.setDate(today.getDate() - 7);
            const end = new Date(today);
            end.setDate(today.getDate() + 30);

            setViewRange({ start, end });
        }
    }, [viewRange.start]);

    // Derived clash calculation - checks ALL events, not just view?
    // Yes, clashes exist regardless of view.
    const clashes = useMemo(() => checkForClashes(events), [events]);

    const handleLoadMorePrev = () => {
        if (!viewRange.start) return;
        setViewRange(prev => {
            const newStart = new Date(prev.start);
            newStart.setDate(newStart.getDate() - 14);
            return { ...prev, start: newStart };
        });
    };

    const handleLoadMoreNext = () => {
        if (!viewRange.end) return;
        setViewRange(prev => {
            const newEnd = new Date(prev.end);
            newEnd.setDate(newEnd.getDate() + 14);
            return { ...prev, end: newEnd };
        });
    };

    const handleToday = () => {
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        const start = new Date(today);
        start.setDate(today.getDate() - 7);
        const end = new Date(today);
        end.setDate(today.getDate() + 30);

        setViewRange({ start, end });

        // Scroll to today after render
        setTimeout(() => {
            const dateStr = format(today, 'yyyy-MM-dd');
            const element = document.getElementById(`row-${dateStr}`);
            if (element) {
                element.scrollIntoView({ behavior: 'smooth', block: 'center' });
                // Highlight
                const startOfDay = new Date(today);
                const endOfDay = new Date(today); endOfDay.setHours(23, 59, 59, 999);
                setHighlight({
                    type: 'today',
                    start: startOfDay,
                    end: endOfDay
                });
                setTimeout(() => setHighlight(null), 3000);
            }
        }, 100);
    };

    // Jump to specific date (for conflicts)
    const handleJumpToDate = (date) => {
        const target = new Date(date);
        target.setHours(0, 0, 0, 0);

        const start = new Date(target);
        start.setDate(target.getDate() - 7);
        const end = new Date(target);
        end.setDate(target.getDate() + 30);

        setViewRange({ start, end });

        setTimeout(() => {
            const dateStr = format(target, 'yyyy-MM-dd');
            const element = document.getElementById(`row-${dateStr}`);
            if (element) {
                element.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        }, 100);
    };

    const handleSaveEvent = (eventData) => {
        setEvents(prev => {
            let newEvents = [...prev];
            const updates = Array.isArray(eventData) ? eventData : [eventData];

            updates.forEach(update => {
                const index = newEvents.findIndex(e => e.id === update.id);
                if (index >= 0) {
                    newEvents[index] = update;
                } else {
                    newEvents.push(update);
                }
            });
            return newEvents;
        });

        setEditingEvent(null);
        setIsAddModalOpen(false);

        // Update selectedEvent if needed (only if single edit matches)
        if (!Array.isArray(eventData) && selectedEvent && selectedEvent.id === eventData.id) {
            setSelectedEvent(eventData);
        }
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

    const handlePrint = () => {
        // 1. Find the date range to print (Today -> Last Event)
        const today = new Date();
        today.setHours(0, 0, 0, 0);

        // Find last event date
        let maxDate = new Date(today);
        if (events.length > 0) {
            const lastEventDate = events.reduce((max, e) => e.end > max ? e.end : max, new Date(0));
            if (lastEventDate > maxDate) {
                // Clone the date to avoid mutating the event object!
                maxDate = new Date(lastEventDate);
            }
        }

        // Add a buffer to maxDate (e.g., end of that week)
        maxDate.setDate(maxDate.getDate() + 7);

        // 2. Set View Range
        const printStart = new Date(today);
        printStart.setDate(printStart.getDate() - 1); // Start slightly before today for context

        setViewRange({ start: printStart, end: maxDate });

        // 3. Print after render
        setTimeout(() => {
            window.print();
        }, 500);
    };

    return (
        <div className="app-container h-screen flex flex-col bg-gray-100 overflow-hidden">
            <header className="bg-white shadow-sm px-6 py-4 flex justify-between items-center z-50">
                <h1 className="text-xl font-bold text-gray-800">{t('app.title')}</h1>
                <div className="flex items-center gap-2">
                    <div className="flex bg-gray-100 rounded-md p-1 mr-4">
                        <button onClick={handleToday} className="px-3 py-1 text-sm font-medium text-gray-600 hover:bg-white hover:shadow-sm rounded transition-all">
                            {t('nav.today')}
                        </button>
                    </div>

                    <button
                        onClick={toggleLanguage}
                        className="flex items-center gap-2 text-gray-600 hover:text-gray-900 font-medium px-3 py-2 text-sm"
                        title={t('app.switchLanguage')}
                    >
                        <Languages className="w-4 h-4" />
                        {i18n.language === 'en' ? '中文' : 'English'}
                    </button>

                    <button
                        onClick={handlePrint}
                        className="flex items-center gap-2 text-gray-600 hover:text-gray-900 font-medium px-3 py-2 text-sm"
                        title={t('app.printCalendar')}
                    >
                        <Printer className="w-4 h-4" />
                        {t('actions.print')}
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
                                            // Reset view
                                            const today = new Date();
                                            today.setHours(0, 0, 0, 0);
                                            setViewRange({
                                                start: new Date(today.setDate(today.getDate() - 7)),
                                                end: new Date(today.setDate(today.getDate() + 30))
                                            });
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
                        // Jump to it if it's a clash
                        if (h.type === 'clash') {
                            handleJumpToDate(h.start);
                        }

                        setTimeout(() => setHighlight(null), 3000);
                    }}
                />
                <Timeline
                    startDate={viewRange.start || new Date()}
                    endDate={viewRange.end || new Date()}
                    events={events}
                    clashes={clashes}
                    onEventClick={setSelectedEvent}
                    onAddEvent={handleTimelineClick}
                    highlight={highlight}
                    onLoadPrev={handleLoadMorePrev}
                    onLoadNext={handleLoadMoreNext}
                    onUpdateEvent={handleSaveEvent}
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
                onSave={handleSaveEvent}
            />
        </div>
    )
}

export default App
