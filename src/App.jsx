import { useState, useMemo, useEffect, useRef } from 'react'
import { debounceTime } from 'rxjs'
import { format } from 'date-fns'
import { useTranslation } from 'react-i18next'
import Timeline from './components/Timeline'
import DecadePlan from './components/DecadePlan'
import DecadeFab from './components/DecadeFab'
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
import { makeGoal } from './utils/goalUtils'
import { now as clockNow } from './utils/clock'
import { BUILTIN_ADAPTERS } from './utils/syncLogic'
import * as webApi from './utils/webDataAdapter'

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
    const [selectedIds, setSelectedIds] = useState(() => new Set()); // box-select for batch ops
    const [modalDefaultDate, setModalDefaultDate] = useState(null);
    const [editingEvent, setEditingEvent] = useState(null);
    const [isLoaded, setIsLoaded] = useState(false);
    const [db, setDb] = useState(null);
    const [activeTab, setActiveTab] = useState('calendar');

    // ── Decade Plan ───────────────────────────────────────────────────────
    const [goals, setGoals] = useState([]);
    const [selectedGoalId, setSelectedGoalId] = useState(null);

    // Detect Electron environment
    const isElectron = typeof window !== 'undefined' && !!window.electronAPI;

    // ── Database & Native Init ───────────────────────────────────────────
    // Electron: RxDB (IndexedDB) with observable subscription
    // Web: fetch from sync server API (same machine)
    useEffect(() => {
        if (isElectron) {
            let subscription;
            getDatabase().then(database => {
                setDb(database);
                subscription = database.events.find().$.pipe(debounceTime(50)).subscribe(docs => {
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
        } else {
            // Web mode: load directly from server API
            Promise.all([
                webApi.loadEvents(),
                webApi.loadGoals(),
            ]).then(([ev, g]) => {
                setEvents(ev);
                setGoals(g);
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
                    const patch = { completed, updatedAt: clockNow() };
                    if (checklist !== undefined) patch.checklist = checklist;
                    await doc.update({ $set: patch });
                }
            } catch (err) {
                console.error('Widget→RxDB sync failed', err);
            }
        });
        return () => { if (typeof off === 'function') off(); };
    }, [db, isElectron]);

    // ── Goals subscription ───────────────────────────────────────────────
    useEffect(() => {
        if (!db) return;
        const sub = db.goals.find().$.pipe(debounceTime(50)).subscribe(docs => {
            setGoals(docs.map(d => d.toJSON()));
        });
        return () => sub.unsubscribe();
    }, [db]);

    const visibleGoals = useMemo(() => goals.filter(g => !g.deletedAt), [goals]);

    const handleAddGoal = async ({ q, r, s }) => {
        if (!db) return;
        const goal = makeGoal({ order: visibleGoals.length, q, r, s });
        await db.goals.insert(goal);
        setSelectedGoalId(goal.id);
    };

    const handleUpdateGoal = async (id, patch) => {
        if (!db) return;
        try {
            const doc = await db.goals.findOne(id).exec();
            if (doc) {
                const v = (doc.get('version') || 0) + 1;
                await doc.incrementalPatch({ ...patch, version: v, updatedAt: clockNow() });
            }
        } catch (err) {
            console.error('Update goal failed', err);
        }
    };

    const handleDeleteGoal = async (id) => {
        if (!db) return;
        try {
            const doc = await db.goals.findOne(id).exec();
            if (doc) {
                const v = (doc.get('version') || 0) + 1;
                await doc.update({ $set: { version: v, deletedAt: clockNow(), updatedAt: clockNow() } });
            }
        } catch (err) {
            console.error('Delete goal failed', err);
        }
        if (selectedGoalId === id) setSelectedGoalId(null);
    };

    // ── Debug console commands ────────────────────────────────────────────
    useEffect(() => {
        if (!db) return;
        window.__tplanner = {
            ...(window.__tplanner ?? {}),
            clearEmptyGoals: async () => {
                const docs = await db.goals.find().exec();
                const empty = docs.filter(d => {
                    const title = (d.get('title') ?? '').trim();
                    const note  = (d.get('note')  ?? '').trim();
                    const dead  = d.get('deletedAt') > 0;
                    return !dead && note === '' && (title === '' || title === '新目标' || title === 'New Goal');
                });
                if (!empty.length) { console.log('[tplanner] 没有空目标'); return; }
                const ts = clockNow();
                await Promise.all(empty.map(d => d.incrementalPatch({ deletedAt: ts, updatedAt: ts })));
                console.log(`[tplanner] 已清除 ${empty.length} 个空目标`);
            },
        };
    }, [db]);

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
            window.electronAPI.getJournals().then(j => setJournals(normalizeJournals(j)));
            const off1 = window.electronAPI.onJournalUpdated?.((date, entry) => {
                setJournals(prev => ({ ...prev, [date]: normalizeJournalEntry(entry) }));
            });
            // LAN sync batch update
            const off2 = window.electronAPI.onJournalAllUpdated?.(merged => {
                setJournals(normalizeJournals(merged));
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
    }, [isElectron]);

    const handleSaveJournal = (dateStr, text) => {
        setJournals(prev => {
            const oldVer = prev[dateStr]?.version || 0;
            const ts = clockNow();
            const entry = text?.trim()
                ? { text, version: oldVer + 1, updatedAt: ts, deletedAt: null }
                : { text: '', version: oldVer + 1, updatedAt: ts, deletedAt: ts };
            if (isElectron && window.electronAPI?.saveJournal) {
                window.electronAPI.saveJournal(dateStr, entry);
            } else {
                // Web mode: save to localStorage (instant) + server (debounced in useEffect below)
                localStorage.setItem(`tplanner_journal_${dateStr}`, JSON.stringify(entry));
            }
            return { ...prev, [dateStr]: entry };
        });
    };

    // ── Web-mode auto-save goals to server ────────────────────────────────
    const webGoalSaveTimerRef = useRef(null);
    useEffect(() => {
        if (isElectron || !isLoaded) return;
        clearTimeout(webGoalSaveTimerRef.current);
        webGoalSaveTimerRef.current = setTimeout(() => {
            webApi.saveGoals(goals).catch(err =>
                console.error('Failed to save goals to server', err)
            );
        }, 300);
    }, [goals, isLoaded, isElectron]);

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
                const v = (doc.get('version') || 0) + 1;
                await doc.update({ $set: { completed: completedStatus, version: v, updatedAt: clockNow() } });
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
                cleanUpdate.version = (update.version || 0) + 1;
                cleanUpdate.updatedAt = clockNow();
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
        const v = (doc.get('version') || 0) + 1;
        await doc.update({ $set: { deletedAt: clockNow(), version: v, updatedAt: clockNow() } });
    };

    const handleDeleteEvent = async (id, scope = 'single', event = null) => {
        if (!db) return;
        try {
            const now = clockNow();
            if (scope === 'all' && event?.groupId) {
                const docsObj = await db.events.find({ selector: { groupId: event.groupId } }).exec();
                // bulkUpsert fires a single batch write → single subscription emission
                await db.events.bulkUpsert(docsObj.map(doc => {
                    const old = doc.toJSON();
                    return { ...old, version: (old.version || 0) + 1, deletedAt: now, updatedAt: now };
                }));
            } else if (scope === 'future' && event?.groupId) {
                const cutoff = new Date(event.start).getTime();
                const docsObj = await db.events.find({ selector: { groupId: event.groupId } }).exec();
                const toMark = docsObj.filter(doc => new Date(doc.get('start')).getTime() >= cutoff);
                if (toMark.length) {
                    await db.events.bulkUpsert(toMark.map(doc => {
                        const old = doc.toJSON();
                        return { ...old, version: (old.version || 0) + 1, deletedAt: now, updatedAt: now };
                    }));
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

    // Batch delete: tombstone every box-selected event in one write.
    // Temporary patch — recurring instances aren't synced as a group yet,
    // so a multi-select box lets users clear them all without one-by-one deletes.
    const handleBatchDelete = async (ids) => {
        if (!db || !ids?.length) return;
        try {
            const now = clockNow();
            const docs = await db.events.findByIds(ids).exec();
            const upserts = Array.from(docs.values()).map(doc => {
                const old = doc.toJSON();
                return { ...old, version: (old.version || 0) + 1, deletedAt: now, updatedAt: now };
            });
            if (upserts.length) await db.events.bulkUpsert(upserts);
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
        if (!db || !clipboard) return;
        const duration = clipboard.end - clipboard.start;
        try {
            const copy = {
                ...clipboard,
                id: crypto.randomUUID(),
                title: clipboard.title + t('event.copySuffix'),
                groupId: crypto.randomUUID(),
                start: new Date(start).toISOString(),
                end:   new Date(start.getTime() + duration).toISOString(),
                completed: false,
                version: 1,
                deletedAt: 0,
                updatedAt: clockNow(),
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
                        cleanUpdate.updatedAt = clockNow();
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

            {/* App Header — hidden on decade tab, but kept mounted so LanSync state persists */}
            <header className="app-header" style={{ display: activeTab === 'decade' ? 'none' : undefined }}>
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
                            adapters={[
                                {
                                    ...BUILTIN_ADAPTERS.events,
                                    _getLocal: () => events,
                                    _writeLocal: async (merged) => {
                                        if (!db) return;
                                        try { await db.events.bulkUpsert(merged); } catch (err) { console.error('LAN merge failed', err); }
                                    },
                                },
                                {
                                    ...BUILTIN_ADAPTERS.goals,
                                    _getLocal: () => goals,
                                    _writeLocal: async (merged) => {
                                        if (!db) return;
                                        try { await db.goals.bulkUpsert(merged); } catch (err) { console.error('LAN goals merge failed', err); }
                                    },
                                },
                                {
                                    ...BUILTIN_ADAPTERS.journals,
                                    _getLocal: () => journals,
                                    _writeLocal: (merged) => {
                                        const normalized = normalizeJournals(merged);
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
                {activeTab === 'calendar' && <>
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
                </>}
                {activeTab === 'decade' && (
                    <DecadePlan
                        goals={visibleGoals}
                        selectedId={selectedGoalId}
                        onSelect={setSelectedGoalId}
                        onAddGoal={handleAddGoal}
                        onUpdateGoal={handleUpdateGoal}
                        onDeleteGoal={handleDeleteGoal}
                    />
                )}
            </main>

            {activeTab === 'decade' && (
                <DecadeFab
                    lang={i18n.language}
                    onToggleLanguage={toggleLanguage}
                    onPrint={handlePrint}
                    onExport={handleExport}
                    onImport={handleImport}
                />
            )}

            {/* Bottom Tab Bar */}
            <div style={{
                display: 'flex',
                alignItems: 'flex-end',
                gap: 2,
                padding: '0 12px',
                background: 'var(--clr-void)',
                borderTop: '1px solid var(--clr-border)',
                flexShrink: 0,
            }}>
                {[
                    { id: 'calendar', label: t('tabs.calendar') },
                    { id: 'decade',   label: t('tabs.decade') },
                ].map(tab => (
                    <button
                        key={tab.id}
                        onClick={() => setActiveTab(tab.id)}
                        style={{
                            padding: '5px 16px',
                            fontSize: 11,
                            fontFamily: 'var(--font-display)',
                            letterSpacing: '0.08em',
                            textTransform: 'uppercase',
                            cursor: 'pointer',
                            border: '1px solid var(--clr-border)',
                            borderBottom: 'none',
                            borderRadius: '3px 3px 0 0',
                            background: activeTab === tab.id ? 'var(--clr-surface)' : 'var(--clr-void)',
                            color: activeTab === tab.id ? 'var(--clr-gold)' : 'var(--clr-text-dim)',
                            borderColor: activeTab === tab.id ? 'var(--clr-gold-dim)' : 'var(--clr-border)',
                            transition: 'color 120ms ease, background 120ms ease',
                            position: 'relative',
                            bottom: activeTab === tab.id ? -1 : 0,
                        }}
                    >
                        {tab.label}
                    </button>
                ))}
            </div>

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

export default App;
