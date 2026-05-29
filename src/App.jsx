import { useState, useMemo, useEffect, useRef } from 'react'
import { debounceTime } from 'rxjs'
import { format } from 'date-fns'
import { useTranslation } from 'react-i18next'
import Timeline from './components/Timeline'
import AddEventModal from './components/AddEventModal'
import EventDetailsModal from './components/EventDetailsModal'
import ClashBanner from './components/ClashBanner'
import OverdueBanner from './components/OverdueBanner'
import ReminderBanner from './components/ReminderBanner'
import TitleBar from './components/TitleBar'
import ThemeManager from './components/ThemeManager'
import ZoomControl from './components/ZoomControl'
import LanSync from './components/LanSync'
import DebugPanel from './components/DebugPanel'
import ContextMenu from './components/ContextMenu'
import { ThemeProvider } from './contexts/ThemeContext'
import { checkForClashes } from './utils/dateUtils'
import { TIMEZONES } from './utils/constants'
import { Plus, Languages, Printer, Globe, Download, Upload, Power } from 'lucide-react'
import { getDatabase } from './database/db'

function App() {
    const { t, i18n } = useTranslation();
    const [events, setEvents] = useState([]);
    const [highlight, setHighlight] = useState(null);
    const [travelTimezone, setTravelTimezone] = useState('');

    const [contextMenu, setContextMenu] = useState(null); // { x, y, event }
    const [clipboard, setClipboard]   = useState(null);  // event waiting to be pasted
    const [autoLaunch, setAutoLaunch] = useState(false);
    const [isAddModalOpen, setIsAddModalOpen] = useState(false);
    const [selectedEvent, setSelectedEvent] = useState(null);
    const [modalDefaultDate, setModalDefaultDate] = useState(null);
    const [editingEvent, setEditingEvent] = useState(null);
    const [isLoaded, setIsLoaded] = useState(false);
    const [db, setDb] = useState(null);

    // Detect Electron environment
    const isElectron = typeof window !== 'undefined' && !!window.electronAPI;

    // ── Database & Native Init ───────────────────────────────────────────
    useEffect(() => {
        let subscription;
        getDatabase().then(database => {
            setDb(database);
            subscription = database.events.find().$.pipe(debounceTime(50)).subscribe(docs => {
                // Keep ALL docs (including tombstones) in state so they can
                // be included in the sync payload and propagate deletions.
                const hydrated = docs.map(doc => {
                    const e = doc.toJSON();
                    return { ...e, start: new Date(e.start), end: new Date(e.end) };
                });
                setEvents(hydrated);
                setIsLoaded(true);
            });
        }).catch(err => {
            console.error("Failed to init RxDB", err);
        });

        const savedTz = localStorage.getItem('tplanner_travel_timezone');
        if (savedTz) setTravelTimezone(savedTz);

        return () => { if (subscription) subscription.unsubscribe(); };
    }, []);

    // ── Electron Today-Widget Sync ────────────────────────────────────────
    // Debounce: rapid RxDB updates (delete/batch) collapse into one IPC call
    // ESC cancels paste mode
    useEffect(() => {
        if (!clipboard) return;
        const handler = (e) => { if (e.key === 'Escape') setClipboard(null); };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [clipboard]);

    // Sync auto-launch state from tray menu changes
    useEffect(() => {
        if (!isElectron) return;
        window.electronAPI?.getAutoLaunch().then(v => setAutoLaunch(!!v));
        const off = window.electronAPI?.onAutoLaunchChanged?.((v) => setAutoLaunch(v));
        return () => off?.();
    }, [isElectron]);

    const syncTimerRef = useRef(null);
    useEffect(() => {
        if (!isLoaded) return;
        if (!isElectron || !window.electronAPI?.syncEvents) return;
        clearTimeout(syncTimerRef.current);
        syncTimerRef.current = setTimeout(() => {
            const serial = events.map(e => ({
                ...e,
                start: e.start instanceof Date ? e.start.toISOString() : e.start,
                end:   e.end   instanceof Date ? e.end.toISOString()   : e.end,
            }));
            window.electronAPI.syncEvents(serial);
        }, 150);
    }, [events, isLoaded, isElectron]);

    // Mirror task-toggles done in the widget back into RxDB so both views agree.
    useEffect(() => {
        if (!isElectron || !window.electronAPI?.onEventsRemoteUpdate || !db) return;
        const off = window.electronAPI.onEventsRemoteUpdate(async ({ id, completed, checklist }) => {
            try {
                const doc = await db.events.findOne(id).exec();
                if (doc) {
                    const patch = { completed, updatedAt: Date.now() };
                    if (checklist !== undefined) patch.checklist = checklist;
                    await doc.update({ $set: patch });
                }
            } catch (err) {
                console.error('Widget→RxDB sync failed', err);
            }
        });
        return () => { if (typeof off === 'function') off(); };
    }, [db, isElectron]);

    // ── Journal (随笔) ────────────────────────────────────────────────────
    const [journals, setJournals] = useState({});

    useEffect(() => {
        if (isElectron && window.electronAPI?.getJournals) {
            window.electronAPI.getJournals().then(j => setJournals(j || {}));
            const off1 = window.electronAPI.onJournalUpdated?.((date, text) => {
                setJournals(prev => ({ ...prev, [date]: text || '' }));
            });
            // LAN sync batch update
            const off2 = window.electronAPI.onJournalAllUpdated?.(merged => {
                setJournals(merged || {});
            });
            return () => { off1?.(); off2?.(); };
        } else {
            // Web fallback: scan localStorage
            const data = {};
            for (let i = 0; i < localStorage.length; i++) {
                const k = localStorage.key(i);
                if (k?.startsWith('tplanner_journal_')) {
                    data[k.replace('tplanner_journal_', '')] = localStorage.getItem(k);
                }
            }
            setJournals(data);
        }
    }, [isElectron]);

    const handleSaveJournal = (dateStr, text) => {
        setJournals(prev => ({ ...prev, [dateStr]: text }));
        if (isElectron && window.electronAPI?.saveJournal) {
            window.electronAPI.saveJournal(dateStr, text);
        } else {
            if (text?.trim()) {
                localStorage.setItem(`tplanner_journal_${dateStr}`, text);
            } else {
                localStorage.removeItem(`tplanner_journal_${dateStr}`);
            }
        }
    };

    const [viewRange, setViewRange] = useState({ start: null, end: null });

    const handleTimezoneChange = (e) => {
        const value = e.target.value;
        setTravelTimezone(value);
        if (value) {
            localStorage.setItem('tplanner_travel_timezone', value);
        } else {
            localStorage.removeItem('tplanner_travel_timezone');
        }
    };

    useEffect(() => {
        if (!viewRange.start) {
            const today = new Date();
            today.setHours(0, 0, 0, 0);
            const start = new Date(today);
            start.setDate(today.getDate() - 7);
            const end = new Date(today);
            end.setDate(today.getDate() + 30);
            setViewRange({ start, end });
        }
    }, [viewRange.start]);

    // Strip tombstones for display; sync payload keeps them to propagate deletions
    const visibleEvents = useMemo(() => events.filter(e => !e.deletedAt), [events]);
    const clashes = useMemo(() => checkForClashes(visibleEvents), [visibleEvents]);

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
        setTimeout(() => {
            const dateStr = format(today, 'yyyy-MM-dd');
            const element = document.getElementById(`row-${dateStr}`);
            if (element) {
                // Using 'nearest' for block to avoid scrolling the whole page/app container
                element.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                const startOfDay = new Date(today);
                const endOfDay = new Date(today); endOfDay.setHours(23, 59, 59, 999);
                setHighlight({ type: 'today', start: startOfDay, end: endOfDay });
                setTimeout(() => setHighlight(null), 3000);
            }
        }, 100);
    };

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
                // Using 'nearest' for block
                element.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }
        }, 100);
    };

    const handleToggleTaskComplete = async (eventId, completedStatus) => {
        if (!db) return;
        try {
            const doc = await db.events.findOne(eventId).exec();
            if (doc) {
                await doc.update({ $set: { completed: completedStatus, updatedAt: Date.now() } });
            }
        } catch (err) {
            console.error('Update failed', err);
        }
    };

    const handleSaveEvent = async (eventData, config = { scope: 'single' }) => {
        if (!db) return;
        const updates = Array.isArray(eventData) ? eventData : [eventData];
        try {
            if (config.scope === 'all' && config.originalGroupId) {
                const docsObj = await db.events.find({ selector: { groupId: config.originalGroupId } }).exec();
                await Promise.all(docsObj.map(doc => doc.remove()));
            } else if (config.scope === 'future' && config.originalGroupId && config.originalStartDate) {
                const cutoff = new Date(config.originalStartDate).getTime();
                const docsObj = await db.events.find({ selector: { groupId: config.originalGroupId } }).exec();
                const toRemove = docsObj.filter(doc => new Date(doc.get('start')).getTime() >= cutoff);
                await Promise.all(toRemove.map(doc => doc.remove()));
            }
            const upserts = updates.map(update => {
                const cleanUpdate = { ...update };
                cleanUpdate.start = new Date(cleanUpdate.start).toISOString();
                cleanUpdate.end = new Date(cleanUpdate.end).toISOString();
                cleanUpdate.updatedAt = Date.now();
                return cleanUpdate;
            });
            await db.events.bulkUpsert(upserts);
        } catch (err) {
            console.error('Error saving events to RxDB', err);
        }
        setEditingEvent(null);
        setIsAddModalOpen(false);
        if (!Array.isArray(eventData) && selectedEvent && selectedEvent.id === eventData.id) {
            setSelectedEvent(eventData);
        } else if (config.scope !== 'single' && selectedEvent) {
            setSelectedEvent(null);
        }
    };

    // Soft-delete: stamp deletedAt instead of physically removing,
    // so the tombstone propagates to peers during the next LAN sync.
    const softDelete = async (doc) => {
        await doc.update({ $set: { deletedAt: Date.now(), updatedAt: Date.now() } });
    };

    const handleDeleteEvent = async (id, scope = 'single', event = null) => {
        if (!db) return;
        try {
            const now = Date.now();
            if (scope === 'all' && event?.groupId) {
                const docsObj = await db.events.find({ selector: { groupId: event.groupId } }).exec();
                // bulkUpsert fires a single batch write → single subscription emission
                await db.events.bulkUpsert(docsObj.map(doc => ({
                    ...doc.toJSON(), deletedAt: now, updatedAt: now,
                })));
            } else if (scope === 'future' && event?.groupId) {
                const cutoff = new Date(event.start).getTime();
                const docsObj = await db.events.find({ selector: { groupId: event.groupId } }).exec();
                const toMark = docsObj.filter(doc => new Date(doc.get('start')).getTime() >= cutoff);
                if (toMark.length) {
                    await db.events.bulkUpsert(toMark.map(doc => ({
                        ...doc.toJSON(), deletedAt: now, updatedAt: now,
                    })));
                }
            } else {
                const doc = await db.events.findOne(id).exec();
                if (doc) await softDelete(doc);
            }
        } catch (err) {
            console.error('Error deleting event', err);
        }
        setSelectedEvent(null);
    };

    // Copy: store in clipboard, don't save yet
    const handleCopyEvent = (event) => {
        setClipboard(event);
    };

    // Paste clipboard event at clicked time
    const pasteClipboard = async (start) => {
        if (!db || !clipboard) return;
        const duration = clipboard.end - clipboard.start;
        try {
            const copy = {
                ...clipboard,
                id: crypto.randomUUID(),
                title: clipboard.title + ' (副本)',
                groupId: crypto.randomUUID(),
                start: new Date(start).toISOString(),
                end:   new Date(start.getTime() + duration).toISOString(),
                completed: false,
                deletedAt: 0,
                updatedAt: Date.now(),
                checklist: (clipboard.checklist || []).map(i => ({ ...i, id: crypto.randomUUID(), completed: false })),
            };
            await db.events.insert(copy);
        } catch (err) {
            console.error('Paste failed', err);
        }
        setClipboard(null);
    };

    const handleTimelineClick = (start) => {
        if (clipboard) {
            pasteClipboard(start);
            return;
        }
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
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        let maxDate = new Date(today);
        if (events.length > 0) {
            const lastEventDate = events.reduce((max, e) => e.end > max ? e.end : max, new Date(0));
            if (lastEventDate > maxDate) maxDate = new Date(lastEventDate);
        }
        maxDate.setDate(maxDate.getDate() + 7);
        const printStart = new Date(today);
        printStart.setDate(printStart.getDate() - 1);
        setViewRange({ start: printStart, end: maxDate });
        setTimeout(() => window.print(), 500);
    };

    const handleExport = () => {
        const dataStr = JSON.stringify(events, null, 2);
        const blob = new Blob([dataStr], { type: "application/json" });
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = "tplanner-data.json";
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
    };

    const handleImport = async (e) => {
        const file = e.target.files[0];
        if (!file || !db) return;
        const reader = new FileReader();
        reader.onload = async (ev) => {
            try {
                const parsed = JSON.parse(ev.target.result);
                if (Array.isArray(parsed)) {
                    const allDocs = await db.events.find().exec();
                    await Promise.all(allDocs.map(d => d.remove()));
                    const upserts = parsed.map(event => {
                        const cleanUpdate = { ...event };
                        cleanUpdate.start = new Date(cleanUpdate.start).toISOString();
                        cleanUpdate.end = new Date(cleanUpdate.end).toISOString();
                        cleanUpdate.updatedAt = Date.now();
                        if (!cleanUpdate.note) cleanUpdate.note = "";
                        if (!cleanUpdate.timezone) cleanUpdate.timezone = "";
                        if (!cleanUpdate.groupId) cleanUpdate.groupId = "";
                        if (cleanUpdate.completed === undefined) cleanUpdate.completed = false;
                        if (cleanUpdate.checklist === undefined) cleanUpdate.checklist = [];
                        if (!cleanUpdate.recurrenceType) cleanUpdate.recurrenceType = "none";
                        if (!cleanUpdate.recurrenceCount) cleanUpdate.recurrenceCount = 1;
                        return cleanUpdate;
                    });
                    await db.events.bulkUpsert(upserts);
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
    };

    return (
        <div className="app-container" style={{ display: 'flex', flexDirection: 'column', height: '100vh', background: 'var(--clr-bg)', overflow: 'hidden', cursor: clipboard ? 'crosshair' : undefined }}>

            {/* Custom Title Bar (Electron only) */}
            {isElectron && <TitleBar />}

            {/* App Header */}
            <header className="app-header">
                <div className="app-header-left">
                    {/* App title — only show if NOT in electron (TitleBar already shows it) */}
                    {!isElectron && (
                        <h1 className="app-header-title">{t('app.title')}</h1>
                    )}

                    {/* Today button */}
                    <button onClick={handleToday} className="btn btn--ghost" id="btn-today">
                        {t('nav.today')}
                    </button>
                </div>

                <div className="app-header-right">
                    {/* Timezone selector */}
                    <div className="tz-select-wrap" title="Display Timezone">
                        <Globe size={13} />
                        <select
                            value={travelTimezone}
                            onChange={handleTimezoneChange}
                            className="tz-select"
                            id="tz-select"
                        >
                            {TIMEZONES.map(tz => (
                                <option key={tz.value} value={tz.value}>
                                    {t(`timezones.${tz.value.replace('/', '_')}`, tz.label)}
                                </option>
                            ))}
                        </select>
                    </div>

                    {/* Language toggle */}
                    <button
                        onClick={toggleLanguage}
                        className="btn btn--ghost"
                        title={t('app.switchLanguage')}
                        id="btn-lang"
                    >
                        <Languages size={13} />
                        {i18n.language === 'en' ? '中文' : 'EN'}
                    </button>

                    {/* Print */}
                    <button
                        onClick={handlePrint}
                        className="btn btn--ghost"
                        title={t('app.printCalendar')}
                        id="btn-print"
                    >
                        <Printer size={13} />
                    </button>

                    {/* Export */}
                    <button
                        onClick={handleExport}
                        className="btn btn--ghost"
                        title={t('actions.export')}
                        id="btn-export"
                    >
                        <Download size={13} />
                    </button>

                    {/* Import */}
                    <label
                        className="btn btn--ghost"
                        title={t('actions.import')}
                        style={{ cursor: 'pointer' }}
                        id="btn-import-label"
                    >
                        <Upload size={13} />
                        <input
                            type="file"
                            accept=".json"
                            style={{ display: 'none' }}
                            onChange={handleImport}
                            id="btn-import"
                        />
                    </label>

                    {/* Theme Manager */}
                    <ThemeManager />

                    {/* Zoom Control */}
                    <ZoomControl />

                    {/* LAN Sync */}
                    {isElectron && (
                        <LanSync
                            events={events}
                            journals={journals}
                            onMergeEvents={async (merged) => {
                                if (!db) return;
                                try {
                                    const upserts = merged.map(e => ({
                                        ...e,
                                        start: e.start instanceof Date ? e.start.toISOString() : e.start,
                                        end:   e.end   instanceof Date ? e.end.toISOString()   : e.end,
                                        updatedAt: e.updatedAt || Date.now(),
                                        note: e.note || '',
                                        timezone: e.timezone || '',
                                        groupId: e.groupId || '',
                                        completed: e.completed ?? false,
                                        checklist: e.checklist ?? [],
                                        recurrenceType: e.recurrenceType || 'none',
                                        recurrenceCount: e.recurrenceCount || 1,
                                    }));
                                    await db.events.bulkUpsert(upserts);
                                } catch (err) {
                                    console.error('LAN merge failed', err);
                                }
                            }}
                            onMergeJournals={(merged) => {
                                setJournals(merged);
                                if (isElectron && window.electronAPI?.saveAllJournals) {
                                    // Single atomic write — no N sequential IPC calls
                                    window.electronAPI.saveAllJournals(merged);
                                } else {
                                    Object.entries(merged).forEach(([date, text]) => {
                                        if (text?.trim()) localStorage.setItem(`tplanner_journal_${date}`, text);
                                        else localStorage.removeItem(`tplanner_journal_${date}`);
                                    });
                                }
                            }}
                        />
                    )}

                    {/* Add Event */}
                    <button
                        onClick={openAddModal}
                        className="btn btn--primary"
                        id="btn-add-event"
                    >
                        <Plus size={13} />
                        {t('actions.addEvent')}
                    </button>
                </div>
            </header>

            {/* Main Content */}
            <main style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: '12px', minHeight: 0, gap: '8px' }}>
                <ReminderBanner
                    events={visibleEvents}
                    travelTimezone={travelTimezone}
                    onHighlight={(h) => {
                        setHighlight(h);
                        handleJumpToDate(h.start);
                        setTimeout(() => setHighlight(null), 3000);
                    }}
                />
                <OverdueBanner
                    events={visibleEvents}
                    travelTimezone={travelTimezone}
                    onHighlight={(h) => {
                        setHighlight(h);
                        if (h.type === 'overdue') handleJumpToDate(h.start);
                        setTimeout(() => setHighlight(null), 3000);
                    }}
                />
                <ClashBanner
                    clashes={clashes}
                    events={visibleEvents}
                    travelTimezone={travelTimezone}
                    onHighlight={(h) => {
                        setHighlight(h);
                        if (h.type === 'clash') handleJumpToDate(h.start);
                        setTimeout(() => setHighlight(null), 3000);
                    }}
                />
                <Timeline
                    startDate={viewRange.start || new Date()}
                    endDate={viewRange.end || new Date()}
                    events={visibleEvents}
                    clashes={clashes}
                    onEventClick={setSelectedEvent}
                    onAddEvent={handleTimelineClick}
                    highlight={highlight}
                    onLoadPrev={handleLoadMorePrev}
                    onLoadNext={handleLoadMoreNext}
                    onUpdateEvent={handleSaveEvent}
                    onToggleTaskComplete={handleToggleTaskComplete}
                    onContextMenu={(e, ev) => setContextMenu({ x: e.clientX, y: e.clientY, event: ev })}
                    travelTimezone={travelTimezone}
                    journals={journals}
                    onSaveJournal={handleSaveJournal}
                />
            </main>

            <AddEventModal
                isOpen={isAddModalOpen}
                onClose={() => { setIsAddModalOpen(false); setEditingEvent(null); }}
                onSave={handleSaveEvent}
                defaultDate={modalDefaultDate}
                initialEvent={editingEvent}
                events={visibleEvents}
            />

            <EventDetailsModal
                event={selectedEvent}
                travelTimezone={travelTimezone}
                onClose={() => setSelectedEvent(null)}
                onDelete={handleDeleteEvent}
                onEdit={handleEditEvent}
                onSave={handleSaveEvent}
            />

            <DebugPanel />

            {/* Paste mode toast */}
            {clipboard && (
                <div style={{
                    position: 'fixed', bottom: 60, left: '50%', transform: 'translateX(-50%)',
                    zIndex: 9000, background: 'var(--clr-surface,#1e1e1e)',
                    border: '1px solid var(--clr-gold,#C9A84C)', borderRadius: 8,
                    padding: '10px 18px', display: 'flex', alignItems: 'center', gap: 12,
                    boxShadow: '0 4px 20px rgba(0,0,0,0.5)',
                    fontFamily: 'var(--font-mono)', fontSize: 12,
                }}>
                    <span style={{ color: 'var(--clr-gold)' }}>已复制</span>
                    <span style={{ color: 'var(--clr-text)' }}>「{clipboard.title}」</span>
                    <span style={{ color: 'var(--clr-text-dim)' }}>— 点击时间轴空白处粘贴</span>
                    <button onClick={() => setClipboard(null)}
                        style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--clr-text-dim)', padding: 0, marginLeft: 4 }}
                        title="取消 (Esc)"
                    >✕</button>
                </div>
            )}

            {contextMenu && (
                <ContextMenu
                    x={contextMenu.x}
                    y={contextMenu.y}
                    event={contextMenu.event}
                    onClose={() => setContextMenu(null)}
                    onCopy={handleCopyEvent}
                    onDelete={(ev) => handleDeleteEvent(ev.id, 'single', ev)}
                />
            )}
        </div>
    )
}

export default function AppWithTheme() {
    return (
        <ThemeProvider>
            <App />
        </ThemeProvider>
    );
}
