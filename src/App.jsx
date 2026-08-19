import { useState, useMemo, useEffect, useRef, useCallback } from 'react'
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
import ZoomControl from './components/ZoomControl'
import LanSync from './components/LanSync'
import DebugPanel from './components/DebugPanel'
import ContextMenu from './components/ContextMenu'
import { checkForClashes } from './utils/dateUtils'
import { TIMEZONES } from './utils/constants'
import { Plus, Languages, Printer, Globe, Download, Upload, Power, X } from 'lucide-react'
import { getDatabase } from './database/db'
import { now as clockNow } from './utils/clock'
import { BUILTIN_ADAPTERS } from './utils/syncLogic'
import * as webApi from './utils/webDataAdapter'
import LoginScreen from './components/LoginScreen'

function createViewRange(anchorDate = new Date()) {
    const anchor = new Date(anchorDate);
    anchor.setHours(0, 0, 0, 0);

    const start = new Date(anchor);
    start.setDate(anchor.getDate() - 7);

    const end = new Date(anchor);
    end.setDate(anchor.getDate() + 30);

    return { start, end };
}

function hydrateEventDocuments(docs) {
    return docs.map(doc => {
        const event = doc.toJSON();
        return { ...event, start: new Date(event.start), end: new Date(event.end) };
    });
}

function PlannerApp() {
    const { t, i18n } = useTranslation();
    const [events, setEvents] = useState([]);
    const [highlight, setHighlight] = useState(null);
    const [travelTimezone, setTravelTimezone] = useState('');

    const [contextMenu, setContextMenu] = useState(null); // { x, y, event }
    const [clipboard, setClipboard]   = useState(null);  // event waiting to be pasted
    const [autoLaunch, setAutoLaunch] = useState(false);
    const [isAddModalOpen, setIsAddModalOpen] = useState(false);
    const [selectedEvent, setSelectedEvent] = useState(null);
    const [selectedIds, setSelectedIds] = useState(() => new Set()); // box-select for batch ops
    const [modalDefaultDate, setModalDefaultDate] = useState(null);
    const [editingEvent, setEditingEvent] = useState(null);
    const [isLoaded, setIsLoaded] = useState(false);
    const [db, setDb] = useState(null);
    // Detect Electron environment
    const isElectron = typeof window !== 'undefined' && !!window.electronAPI;
    const [syncRequest, setSyncRequest] = useState({ sequence: 0, dataset: null });
    const requestUnitSync = useCallback((dataset) => {
        if (!isElectron) return;
        setSyncRequest(previous => ({ sequence: previous.sequence + 1, dataset }));
    }, [isElectron]);
    const eventsRef = useRef(events);
    eventsRef.current = events;

    // ── Database & Native Init ───────────────────────────────────────────
    // Electron: RxDB (IndexedDB) with observable subscription
    // Web: fetch from sync server API (same machine)
    useEffect(() => {
        if (isElectron) {
            let subscription;
            getDatabase().then(database => {
                setDb(database);
                subscription = database.events.find().$.pipe(debounceTime(50)).subscribe(docs => {
                    setEvents(hydrateEventDocuments(docs));
                    setIsLoaded(true);
                });
            }).catch(err => {
                console.error("Failed to init RxDB", err);
            });

            const savedTz = localStorage.getItem('tplanner_travel_timezone');
            if (savedTz) setTravelTimezone(savedTz);

            return () => { if (subscription) subscription.unsubscribe(); };
        } else {
            // Web mode: load directly from server API
            webApi.loadEvents().then(ev => {
                setEvents(ev);
                setIsLoaded(true);
            }).catch(err => {
                console.error('Failed to load from server', err);
                setIsLoaded(true); // show UI even if load fails
            });

            const savedTz = localStorage.getItem('tplanner_travel_timezone');
            if (savedTz) setTravelTimezone(savedTz);
        }
    }, [isElectron]);

    // ── Electron Today-Widget Sync ────────────────────────────────────────
    // Debounce: rapid RxDB updates (delete/batch) collapse into one IPC call
    // ESC cancels paste mode
    useEffect(() => {
        if (!clipboard) return;
        const handler = (e) => { if (e.key === 'Escape') setClipboard(null); };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [clipboard]);

    // ESC clears box-selection
    useEffect(() => {
        if (selectedIds.size === 0) return;
        const handler = (e) => { if (e.key === 'Escape') setSelectedIds(new Set()); };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [selectedIds]);

    // Sync auto-launch state from tray menu changes
    useEffect(() => {
        if (!isElectron) return;
        window.electronAPI?.getAutoLaunch().then(v => setAutoLaunch(!!v));
        const off = window.electronAPI?.onAutoLaunchChanged?.((v) => setAutoLaunch(v));
        return () => off?.();
    }, [isElectron]);

    // ── Web-mode auto-save to server ─────────────────────────────────────
    // Debounced PUT on every events change; runs only in browser (not Electron).
    const webEventSaveTimerRef = useRef(null);
    useEffect(() => {
        if (isElectron || !isLoaded) return;
        clearTimeout(webEventSaveTimerRef.current);
        webEventSaveTimerRef.current = setTimeout(() => {
            webApi.saveEvents(events).catch(err =>
                console.error('Failed to save events to server', err)
            );
        }, 300);
    }, [events, isLoaded, isElectron]);

    useEffect(() => {
        if (!isLoaded) return;
        if (!isElectron || !window.electronAPI?.syncEvents) return;
        const serial = events.map(e => ({
            ...e,
            start: e.start instanceof Date ? e.start.toISOString() : e.start,
            end:   e.end   instanceof Date ? e.end.toISOString()   : e.end,
        }));
        window.electronAPI.syncEvents(serial);
    }, [events, isLoaded, isElectron]);

    // Mirror task-toggles done in the widget back into RxDB so both views agree.
    useEffect(() => {
        if (!isElectron || !window.electronAPI?.onEventsRemoteUpdate || !db) return;
        const off = window.electronAPI.onEventsRemoteUpdate(async ({ id, completed, checklist }) => {
            try {
                const doc = await db.events.findOne(id).exec();
                if (doc) {
                    const patch = { completed, updatedAt: clockNow() };
                    if (checklist !== undefined) patch.checklist = checklist;
                    await doc.update({ $set: patch });
                    requestUnitSync('events');
                }
            } catch (err) {
                console.error('Widget→RxDB sync failed', err);
            }
        });
        return () => { if (typeof off === 'function') off(); };
    }, [db, isElectron, requestUnitSync]);

    // ── Journal (随笔) ────────────────────────────────────────────────────
    // 条目格式：{ text, updatedAt, deletedAt }，与 events 的 tombstone 模型一致。
    // 删除时写入 deletedAt+updatedAt（而不是直接抹掉记录），这样合并时删除记录
    // 能凭借更新的 updatedAt 战胜对端尚存的旧内容，从而修复"软删除时间戳失效
    // 导致回环恢复"的问题。旧版纯字符串格式在读取时迁移为时间戳 0 的记录，
    // 保证会被任何带时间戳的写入/删除覆盖。
    const normalizeJournalEntry = (value) => {
        if (value && typeof value === 'object') {
            return { text: value.text || '', updatedAt: value.updatedAt || 0, deletedAt: value.deletedAt ?? null };
        }
        return { text: value || '', updatedAt: 0, deletedAt: null };
    };
    const normalizeJournals = (map) => {
        const result = {};
        for (const [date, value] of Object.entries(map || {})) {
            result[date] = normalizeJournalEntry(value);
        }
        return result;
    };

    const [journals, setJournals] = useState({});
    const journalsRef = useRef(journals);
    journalsRef.current = journals;

    // 用于展示的纯文本映射：过滤掉 tombstone，解包出 text
    const visibleJournals = useMemo(() => {
        const result = {};
        for (const [date, entry] of Object.entries(journals)) {
            if (entry && !entry.deletedAt) result[date] = entry.text;
        }
        return result;
    }, [journals]);

    useEffect(() => {
        if (isElectron && window.electronAPI?.getJournals) {
            window.electronAPI.getJournals().then(j => {
                const normalized = normalizeJournals(j);
                journalsRef.current = normalized;
                setJournals(normalized);
            });
            const off1 = window.electronAPI.onJournalUpdated?.((date, entry) => {
                const next = { ...journalsRef.current, [date]: normalizeJournalEntry(entry) };
                journalsRef.current = next;
                setJournals(next);
                requestUnitSync('journals');
            });
            // LAN sync batch update
            const off2 = window.electronAPI.onJournalAllUpdated?.(merged => {
                const normalized = normalizeJournals(merged);
                journalsRef.current = normalized;
                setJournals(normalized);
            });
            return () => { off1?.(); off2?.(); };
        } else {
            // Web mode: load from server API (authoritative), fall back to localStorage
            const data = {};
            for (let i = 0; i < localStorage.length; i++) {
                const k = localStorage.key(i);
                if (k?.startsWith('tplanner_journal_')) {
                    const raw = localStorage.getItem(k);
                    let parsed;
                    try { parsed = JSON.parse(raw); } catch { parsed = raw; }
                    data[k.replace('tplanner_journal_', '')] = normalizeJournalEntry(parsed);
                }
            }
            setJournals(data);
            // Then try server (overwrites localStorage if server has newer data)
            webApi.loadJournals().then(j => {
                setJournals(normalizeJournals(j));
                // Mirror server data back to localStorage
                for (const [date, entry] of Object.entries(j)) {
                    if (entry && !entry.deletedAt) {
                        localStorage.setItem(`tplanner_journal_${date}`, JSON.stringify(entry));
                    }
                }
            }).catch(() => { /* server unavailable, use localStorage */ });
        }
    }, [isElectron, requestUnitSync]);

    const handleSaveJournal = (dateStr, text) => {
        const oldVer = journalsRef.current[dateStr]?.version || 0;
        const ts = clockNow();
        const entry = text?.trim()
            ? { text, version: oldVer + 1, updatedAt: ts, deletedAt: null }
            : { text: '', version: oldVer + 1, updatedAt: ts, deletedAt: ts };
        const nextJournals = { ...journalsRef.current, [dateStr]: entry };
        journalsRef.current = nextJournals;
        setJournals(nextJournals);
        if (isElectron && window.electronAPI?.saveJournal) {
            window.electronAPI.saveJournal(dateStr, entry);
        } else {
            // Web mode: save to localStorage (instant) + server (debounced in useEffect below)
            localStorage.setItem(`tplanner_journal_${dateStr}`, JSON.stringify(entry));
        }
        requestUnitSync('journals');
    };

    // ── Web-mode auto-save journals to server ─────────────────────────────
    const webJournalSaveTimerRef = useRef(null);
    useEffect(() => {
        if (isElectron || Object.keys(journals).length === 0) return;
        clearTimeout(webJournalSaveTimerRef.current);
        webJournalSaveTimerRef.current = setTimeout(() => {
            webApi.saveJournals(journals).catch(err =>
                console.error('Failed to save journals to server', err)
            );
        }, 500);
    }, [journals, isElectron]);

    // Browser clients also keep a long-poll open. Before applying a remote notice, flush any
    // pending local edit so the server's LWW merge sees both sides, then pull fresh snapshots.
    useEffect(() => {
        if (isElectron || !isLoaded) return;
        const controller = new AbortController();
        let revision = 0;
        let retryDelay = 1000;
        let retryTimer = null;

        const pause = () => new Promise(resolve => {
            retryTimer = setTimeout(resolve, retryDelay);
            controller.signal.addEventListener('abort', resolve, { once: true });
            retryDelay = Math.min(retryDelay * 2, 30000);
        });

        const pullDatasets = async (datasets) => {
            if (datasets.includes('events')) {
                clearTimeout(webEventSaveTimerRef.current);
                await webApi.saveEvents(eventsRef.current);
                setEvents(await webApi.loadEvents());
            }
            if (datasets.includes('journals')) {
                clearTimeout(webJournalSaveTimerRef.current);
                await webApi.saveJournals(journalsRef.current);
                const remote = normalizeJournals(await webApi.loadJournals());
                setJournals(remote);
                for (const [date, entry] of Object.entries(remote)) {
                    const key = `tplanner_journal_${date}`;
                    if (entry?.deletedAt) localStorage.removeItem(key);
                    else localStorage.setItem(key, JSON.stringify(entry));
                }
            }
        };

        const listen = async () => {
            while (!controller.signal.aborted) {
                try {
                    const notice = await webApi.waitForRemoteChanges(revision, controller.signal);
                    revision = Number(notice.revision) || revision;
                    retryDelay = 1000;
                    const datasets = Array.isArray(notice.datasets) ? notice.datasets : [];
                    if (datasets.length > 0) await pullDatasets(datasets);
                } catch (error) {
                    if (controller.signal.aborted || error?.name === 'AbortError') return;
                    console.error('Remote change listener failed', error);
                    await pause();
                }
            }
        };

        listen();
        return () => {
            controller.abort();
            clearTimeout(retryTimer);
        };
    }, [isElectron, isLoaded]);

    const [viewRange, setViewRange] = useState(createViewRange);

    const handleTimezoneChange = (e) => {
        const value = e.target.value;
        setTravelTimezone(value);
        if (value) {
            localStorage.setItem('tplanner_travel_timezone', value);
        } else {
            localStorage.removeItem('tplanner_travel_timezone');
        }
    };

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

    const scrollTimelineToDate = (date, behavior = 'smooth') => {
        const dateStr = format(date, 'yyyy-MM-dd');
        const element = document.getElementById(`row-${dateStr}`);
        const scrollContainer = element?.closest('.timeline-scroll-area');
        if (!element || !scrollContainer) return false;

        // Only scroll the timeline body. scrollIntoView() can also move outer
        // ancestors/the page in browsers, which pushes the banners off-screen.
        const elementRect = element.getBoundingClientRect();
        const containerRect = scrollContainer.getBoundingClientRect();
        const stickyHeaderHeight = scrollContainer.querySelector('.timeline-header')?.offsetHeight || 0;
        scrollContainer.scrollTo({
            top: Math.max(0, scrollContainer.scrollTop + elementRect.top - containerRect.top - stickyHeaderHeight),
            behavior,
        });
        return true;
    };

    const handleToday = () => {
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        setViewRange(createViewRange(today));
        setTimeout(() => {
            if (scrollTimelineToDate(today)) {
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
        setViewRange(createViewRange(target));
        setTimeout(() => {
            scrollTimelineToDate(target);
        }, 100);
    };

    const handleToggleTaskComplete = async (eventId, completedStatus) => {
        if (!db) {
            // Web mode: update state directly (autosave effect will PUT to server)
            const now = clockNow();
            setEvents(prev => prev.map(e =>
                e.id === eventId ? { ...e, completed: completedStatus, version: (e.version || 0) + 1, updatedAt: now } : e
            ));
            return;
        }
        try {
            const doc = await db.events.findOne(eventId).exec();
            if (doc) {
                const v = (doc.get('version') || 0) + 1;
                await doc.update({ $set: { completed: completedStatus, version: v, updatedAt: clockNow() } });
                requestUnitSync('events');
            }
        } catch (err) {
            console.error('Update failed', err);
        }
    };

    const handleSaveEvent = async (eventData) => {
        if (!db) {
            // Web mode: update state directly (autosave effect will PUT to server)
            const updates = Array.isArray(eventData) ? eventData : [eventData];
            const now = clockNow();
            setEvents(prev => {
                const map = new Map(prev.map(e => [e.id, e]));
                for (const u of updates) {
                    // Keep Date objects in state — saveEvents handles serialization
                    map.set(u.id, {
                        ...u,
                        start: u.start instanceof Date ? u.start : new Date(u.start),
                        end: u.end instanceof Date ? u.end : new Date(u.end),
                        version: (u.version || 0) + 1,
                        updatedAt: now,
                    });
                }
                return Array.from(map.values());
            });
            setEditingEvent(null);
            setIsAddModalOpen(false);
            if (!Array.isArray(eventData) && selectedEvent && selectedEvent.id === eventData.id) {
                setSelectedEvent(eventData);
            }
            return;
        }
        const updates = Array.isArray(eventData) ? eventData : [eventData];
        try {
            const upserts = updates.map(update => {
                const cleanUpdate = { ...update };
                cleanUpdate.start = new Date(cleanUpdate.start).toISOString();
                cleanUpdate.end = new Date(cleanUpdate.end).toISOString();
                cleanUpdate.version = (update.version || 0) + 1;
                cleanUpdate.updatedAt = clockNow();
                return cleanUpdate;
            });
            await db.events.bulkUpsert(upserts);
            requestUnitSync('events');
        } catch (err) {
            console.error('Error saving events to RxDB', err);
        }
        setEditingEvent(null);
        setIsAddModalOpen(false);
        if (!Array.isArray(eventData) && selectedEvent && selectedEvent.id === eventData.id) {
            setSelectedEvent(eventData);
        }
    };

    // Soft-delete: stamp deletedAt instead of physically removing,
    // so the tombstone propagates to peers during the next LAN sync.
    const softDelete = async (doc) => {
        const v = (doc.get('version') || 0) + 1;
        await doc.update({ $set: { deletedAt: clockNow(), version: v, updatedAt: clockNow() } });
    };

    const handleDeleteEvent = async (id) => {
        if (!db) {
            // Web mode: tombstone directly in state (autosave will PUT to server)
            const now = clockNow();
            setEvents(prev => prev.map(e =>
                e.id === id ? { ...e, version: (e.version || 0) + 1, deletedAt: now, updatedAt: now } : e
            ));
            setSelectedEvent(null);
            return;
        }
        let changed = false;
        try {
            const doc = await db.events.findOne(id).exec();
            if (doc) {
                await softDelete(doc);
                changed = true;
            }
        } catch (err) {
            console.error('Error deleting event', err);
        }
        if (changed) requestUnitSync('events');
        setSelectedEvent(null);
    };

    // Batch delete: tombstone every box-selected event in one write.
    // Temporary patch — recurring instances aren't synced as a group yet,
    // so a multi-select box lets users clear them all without one-by-one deletes.
    const handleBatchDelete = async (ids) => {
        if (!ids?.length) return;
        if (!db) {
            // Web mode: tombstone directly in state
            const now = clockNow();
            const idSet = new Set(ids);
            setEvents(prev => prev.map(e =>
                idSet.has(e.id) ? { ...e, version: (e.version || 0) + 1, deletedAt: now, updatedAt: now } : e
            ));
            setSelectedIds(new Set());
            return;
        }
        try {
            const now = clockNow();
            const docs = await db.events.findByIds(ids).exec();
            const upserts = Array.from(docs.values()).map(doc => {
                const old = doc.toJSON();
                return { ...old, version: (old.version || 0) + 1, deletedAt: now, updatedAt: now };
            });
            if (upserts.length) {
                await db.events.bulkUpsert(upserts);
                requestUnitSync('events');
            }
        } catch (err) {
            console.error('Error batch deleting events', err);
        }
        setSelectedIds(new Set());
    };

    // Copy: store in clipboard, don't save yet
    const handleCopyEvent = (event) => {
        setClipboard(event);
    };

    // Paste clipboard event at clicked time
    const pasteClipboard = async (start) => {
        if (!clipboard) return;
        const duration = clipboard.end - clipboard.start;
        const copy = {
            ...clipboard,
            id: crypto.randomUUID(),
            title: clipboard.title + t('event.copySuffix'),
            start: new Date(start).toISOString(),
            end:   new Date(start.getTime() + duration).toISOString(),
            completed: false,
            version: 1,
            deletedAt: 0,
            updatedAt: clockNow(),
            checklist: (clipboard.checklist || []).map(i => ({ ...i, id: crypto.randomUUID(), completed: false })),
        };
        if (!db) {
            // Web mode: add directly to state
            setEvents(prev => [...prev, copy]);
            setClipboard(null);
            return;
        }
        try {
            await db.events.insert(copy);
            requestUnitSync('events');
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
                        cleanUpdate.updatedAt = clockNow();
                        if (!cleanUpdate.note) cleanUpdate.note = "";
                        if (!cleanUpdate.timezone) cleanUpdate.timezone = "";
                        delete cleanUpdate.groupId;
                        if (cleanUpdate.completed === undefined) cleanUpdate.completed = false;
                        if (cleanUpdate.checklist === undefined) cleanUpdate.checklist = [];
                        if (!cleanUpdate.recurrenceType) cleanUpdate.recurrenceType = "none";
                        if (!cleanUpdate.recurrenceCount) cleanUpdate.recurrenceCount = 1;
                        return cleanUpdate;
                    });
                    await db.events.bulkUpsert(upserts);
                    requestUnitSync('events');
                    const today = new Date();
                    setViewRange(createViewRange(today));
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
                    <div className="tz-select-wrap" title={t('app.displayTimezone')}>
                        <Globe size={13} />
                        <select
                            value={travelTimezone}
                            onChange={handleTimezoneChange}
                            className="tz-select"
                            id="tz-select"
                        >
                            {TIMEZONES.map(tz => (
                                <option key={tz.value} value={tz.value}>
                                    {t(`timezones.${tz.value ? tz.value.replace('/', '_') : 'default'}`, tz.label)}
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

                    {/* Import — Electron only (security: no local file upload in web) */}
                    {isElectron && (
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
                    )}

                    {/* Zoom Control */}
                    <ZoomControl />

                    {/* LAN Sync — 适配器驱动 */}
                    {isElectron  && (
                        <LanSync
                            syncRequest={syncRequest}
                            adapters={[
                                {
                                    ...BUILTIN_ADAPTERS.events,
                                    _getLocal: async () => {
                                        if (!db) return eventsRef.current;
                                        const docs = await db.events.find().exec();
                                        return hydrateEventDocuments(docs);
                                    },
                                    _writeLocal: async (merged) => {
                                        if (!db) return;
                                        try { await db.events.bulkUpsert(merged); } catch (err) { console.error('LAN merge failed', err); }
                                    },
                                },
                                {
                                    ...BUILTIN_ADAPTERS.journals,
                                    _getLocal: () => journalsRef.current,
                                    _writeLocal: (merged) => {
                                        const normalized = normalizeJournals(merged);
                                        journalsRef.current = normalized;
                                        setJournals(normalized);
                                        if (isElectron && window.electronAPI?.saveAllJournals) {
                                            window.electronAPI.saveAllJournals(normalized);
                                        } else {
                                            Object.entries(normalized).forEach(([date, entry]) => {
                                                localStorage.setItem(`tplanner_journal_${date}`, JSON.stringify(entry));
                                            });
                                        }
                                    },
                                },
                            ]}
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
                <div className="calendar-banners">
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
                </div>
                <Timeline
                        startDate={viewRange.start}
                        endDate={viewRange.end}
                        events={visibleEvents}
                        clashes={clashes}
                        onEventClick={(ev) => { setSelectedEvent(ev); setSelectedIds(new Set()); }}
                        onAddEvent={handleTimelineClick}
                        highlight={highlight}
                        onLoadPrev={handleLoadMorePrev}
                        onLoadNext={handleLoadMoreNext}
                        onUpdateEvent={handleSaveEvent}
                        onToggleTaskComplete={handleToggleTaskComplete}
                        onContextMenu={(e, ev) => setContextMenu({ x: e.clientX, y: e.clientY, event: ev })}
                        travelTimezone={travelTimezone}
                        journals={visibleJournals}
                        onSaveJournal={handleSaveJournal}
                        selectedIds={selectedIds}
                        onSelectionChange={setSelectedIds}
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

            {/* Debug panel — Electron only; browser has F12 */}
            {isElectron && <DebugPanel />}

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
                    <span style={{ color: 'var(--clr-gold)' }}>{t('paste.copied')}</span>
                    <span style={{ color: 'var(--clr-text)' }}>「{clipboard.title}」</span>
                    <span style={{ color: 'var(--clr-text-dim)' }}>{t('paste.hint')}</span>
                    <button onClick={() => setClipboard(null)}
                        style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--clr-text-dim)', padding: 0, marginLeft: 4, display: 'flex', alignItems: 'center' }}
                        title={t('paste.cancel')}
                    ><X size={14} /></button>
                </div>
            )}

            {/* Box-selection batch toolbar */}
            {selectedIds.size > 0 && (
                <div style={{
                    position: 'fixed', bottom: 60, left: '50%', transform: 'translateX(-50%)',
                    zIndex: 9000, background: 'var(--clr-surface,#1e1e1e)',
                    border: '1px solid var(--clr-gold,#C9A84C)', borderRadius: 8,
                    padding: '10px 18px', display: 'flex', alignItems: 'center', gap: 12,
                    boxShadow: '0 4px 20px rgba(0,0,0,0.5)',
                    fontFamily: 'var(--font-mono)', fontSize: 12,
                }}>
                    <span style={{ color: 'var(--clr-gold)' }}>{t('selection.count', { count: selectedIds.size })}</span>
                    <button
                        onClick={() => handleBatchDelete(Array.from(selectedIds))}
                        style={{
                            background: 'none', border: '1px solid var(--clr-red,#C0392B)', borderRadius: 4,
                            cursor: 'pointer', color: 'var(--clr-red,#C0392B)', padding: '3px 10px', fontSize: 12,
                        }}
                    >
                        {t('selection.delete')}
                    </button>
                    <button onClick={() => setSelectedIds(new Set())}
                        style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--clr-text-dim)', padding: 0, marginLeft: 4, display: 'flex', alignItems: 'center' }}
                        title={t('selection.cancel')}
                    ><X size={14} /></button>
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

function App() {
    const isElectron = typeof window !== 'undefined' && !!window.electronAPI
    const [authState, setAuthState] = useState(() => {
        if (isElectron) return 'authenticated'
        return webApi.hasStoredWebAuth() ? 'checking' : 'unauthenticated'
    })

    useEffect(() => {
        if (isElectron || authState !== 'checking') return

        let active = true
        webApi.restoreWebAuth()
            .then(authenticated => {
                if (active) setAuthState(authenticated ? 'authenticated' : 'unauthenticated')
            })
            .catch(() => {
                webApi.clearWebAuth()
                if (active) setAuthState('unauthenticated')
            })

        return () => { active = false }
    }, [authState, isElectron])

    const handleWebLogin = async ({ account, password, remember }) => {
        const authenticated = await webApi.authenticateWeb(account, password, remember)
        if (authenticated) setAuthState('authenticated')
        return authenticated
    }

    if (isElectron) return <PlannerApp />

    if (authState === 'checking') {
        return <div className="web-auth-loading" role="status">正在验证安全会话…</div>
    }

    if (authState !== 'authenticated') {
        return <LoginScreen onLogin={handleWebLogin} />
    }

    return <PlannerApp />
}

export default App;
