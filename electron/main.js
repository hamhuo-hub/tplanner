const { app, BrowserWindow, ipcMain, Tray, Menu, Notification, nativeImage, shell, dialog, screen } = require('electron');
const path  = require('path');
const fs    = require('fs');
const http  = require('http');
const dgram = require('dgram');

// ── Constants ──────────────────────────────────────────────────────────────
const IS_DEV = process.env.NODE_ENV === 'development' || !app.isPackaged;
// vite-plugin-electron injects the actual dev server URL (with dynamic port) via env var
const VITE_DEV_SERVER_URL = process.env['VITE_DEV_SERVER_URL'] || 'http://localhost:5173';
const STATE_FILE = path.join(app.getPath('userData'), 'window-state.json');
const DEFAULT_STATE = { width: 1280, height: 800, x: undefined, y: undefined, maximized: false };

// Widget state lives in its own file so changes don't churn STATE_FILE.
const WIDGET_STATE_FILE  = path.join(app.getPath('userData'), 'widget-state.json');
const EVENTS_CACHE_FILE  = path.join(app.getPath('userData'), 'events-cache.json');
const JOURNALS_FILE      = path.join(app.getPath('userData'), 'journals.json');
const REMINDER_LEAD_MIN = 30; // minutes before event start to fire reminder
const APP_USER_MODEL_ID = 'com.tplanner.app';

// Persistent app identity so Windows toast notifications group / persist.
if (process.platform === 'win32') app.setAppUserModelId(APP_USER_MODEL_ID);

// ── Pending theme install (from double-click before window is ready) ───────
let pendingThemeFile = null;

// ── Widget / events cache state ────────────────────────────────────────────
let widgetWindow = null;
let widgetVisibleByUser = null; // null = unset → respect saved preference
/** Hydrated event objects: { id, title, start: Date, end: Date, type, ... } */
let eventsCache = [];
/** Active setTimeout handles, keyed by `${eventId}:start` / `${eventId}:lead`. */
const reminderTimers = new Map();
/** Avoid double-firing on the same event after process restart. */
const firedReminders = new Set();

// ── Window State ───────────────────────────────────────────────────────────
function loadWindowState() {
    try {
        if (fs.existsSync(STATE_FILE))
            return { ...DEFAULT_STATE, ...JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')) };
    } catch (e) { /* ignore */ }
    return { ...DEFAULT_STATE };
}

// ── 异步写工具（所有持久化都走这里，绝不阻塞主进程） ─────────────────────────
function writeAsync(filePath, data) {
    fs.writeFile(filePath, data, (err) => {
        if (err) console.error('[IO]', filePath, err.message);
    });
}

function saveWindowState(win) {
    try {
        const isMax  = win.isMaximized();
        const bounds = isMax ? {} : win.getBounds();
        writeAsync(STATE_FILE, JSON.stringify({ ...bounds, maximized: isMax }));
    } catch (e) { /* ignore */ }
}

// ── Main Window ────────────────────────────────────────────────────────────
let mainWindow = null;
let tray = null;

function createWindow() {
    const state = loadWindowState();

    mainWindow = new BrowserWindow({
        width: state.width,
        height: state.height,
        x: state.x,
        y: state.y,
        minWidth: 900,
        minHeight: 600,
        frame: false,
        transparent: false,
        backgroundColor: '#111111',
        titleBarStyle: 'hidden',
        icon: getIconPath(),
        webPreferences: {
            preload: path.join(__dirname, 'preload.cjs'),
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: false,
        },
        show: false,
    });

    if (state.maximized) mainWindow.maximize();

    if (IS_DEV) {
        mainWindow.loadURL(VITE_DEV_SERVER_URL);
        // mainWindow.webContents.openDevTools({ mode: 'detach' });
    } else {
        mainWindow.loadFile(path.join(__dirname, '../dist/index.html'));
    }

    mainWindow.once('ready-to-show', () => {
        mainWindow.show();

        // Restore saved zoom factor (stored by renderer in localStorage,
        // but we apply it here via webContents for immediate effect)
        try {
            const zoomFile = path.join(app.getPath('userData'), 'zoom.json');
            if (fs.existsSync(zoomFile)) {
                const { factor } = JSON.parse(fs.readFileSync(zoomFile, 'utf8'));
                if (factor && typeof factor === 'number') {
                    mainWindow.webContents.setZoomFactor(Math.max(0.5, Math.min(2.0, factor)));
                }
            }
        } catch (e) { /* ignore */ }

        // If a .tptheme file was double-clicked before the window was ready,
        // send it now that the renderer is up.
        if (pendingThemeFile) {
            sendThemeToRenderer(pendingThemeFile);
            pendingThemeFile = null;
        }
    });

    mainWindow.on('close', () => saveWindowState(mainWindow));

    // Minimize to tray instead of closing (like WeChat)
    mainWindow.on('close', (e) => {
        if (tray) {
            e.preventDefault();
            mainWindow.hide();
        }
    });

    mainWindow.on('closed', () => { mainWindow = null; });
    setupMaximizeListeners();
}

// ── Widget Window (Microsoft-Sticky-Notes-style today reminder) ────────────
function loadWidgetState() {
    const def = { x: undefined, y: undefined, width: 280, height: 420, alwaysOnTop: true, visible: true };
    try {
        if (fs.existsSync(WIDGET_STATE_FILE))
            return { ...def, ...JSON.parse(fs.readFileSync(WIDGET_STATE_FILE, 'utf8')) };
    } catch (e) { /* ignore */ }
    return def;
}

function saveWidgetState(partial) {
    try {
        const current = loadWidgetState();
        writeAsync(WIDGET_STATE_FILE, JSON.stringify({ ...current, ...partial }));
    } catch (e) { /* ignore */ }
}

function ensureOnScreen(bounds) {
    // Snap the window back into a visible display if the user unplugged the
    // monitor it was on. Otherwise BrowserWindow opens off-screen.
    if (bounds.x == null || bounds.y == null) return bounds;
    const target = { x: bounds.x, y: bounds.y, width: bounds.width || 280, height: bounds.height || 420 };
    const displays = screen.getAllDisplays();
    for (const d of displays) {
        const wa = d.workArea;
        if (target.x >= wa.x && target.y >= wa.y &&
            target.x + target.width  <= wa.x + wa.width &&
            target.y + target.height <= wa.y + wa.height) return target;
    }
    // Fallback: top-right of primary display
    const primary = screen.getPrimaryDisplay().workArea;
    return {
        x: primary.x + primary.width  - target.width  - 24,
        y: primary.y + 24,
        width:  target.width,
        height: target.height,
    };
}

function createWidgetWindow() {
    if (widgetWindow && !widgetWindow.isDestroyed()) {
        widgetWindow.show();
        widgetWindow.focus();
        return;
    }

    const state = loadWidgetState();
    const bounds = ensureOnScreen({ x: state.x, y: state.y, width: state.width, height: state.height });

    widgetWindow = new BrowserWindow({
        x: bounds.x,
        y: bounds.y,
        width: bounds.width,
        height: bounds.height,
        minWidth: 240,
        minHeight: 280,
        frame: false,
        transparent: true,
        backgroundColor: '#00000000',
        alwaysOnTop: state.alwaysOnTop,
        skipTaskbar: true,
        resizable: true,
        minimizable: false,
        maximizable: false,
        fullscreenable: false,
        hasShadow: true,
        icon: getIconPath(),
        webPreferences: {
            preload: path.join(__dirname, 'widget-preload.cjs'),
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: false,
        },
        show: false,
    });

    widgetWindow.loadFile(path.join(__dirname, 'widget.html'));

    widgetWindow.once('ready-to-show', () => {
        widgetWindow.show();
        // Kick off initial render with whatever cache we have.
        widgetWindow.webContents.send('widget:events', serializeEvents(eventsCache));
    });

    const persistBounds = () => {
        if (!widgetWindow || widgetWindow.isDestroyed()) return;
        const b = widgetWindow.getBounds();
        saveWidgetState({ x: b.x, y: b.y, width: b.width, height: b.height });
    };
    widgetWindow.on('moved', persistBounds);
    widgetWindow.on('resized', persistBounds);

    widgetWindow.on('close', (e) => {
        // Hide instead of destroy so the user can re-open from the tray
        // without losing state. Real teardown happens when the app quits.
        if (!app.isQuitting) {
            e.preventDefault();
            widgetWindow.hide();
            widgetVisibleByUser = false;
            saveWidgetState({ visible: false });
        }
    });

    widgetWindow.on('closed', () => { widgetWindow = null; });

    rebuildTrayMenu();
}

// ── Events cache + reminder scheduling ─────────────────────────────────────
function loadEventsCache() {
    try {
        if (!fs.existsSync(EVENTS_CACHE_FILE)) return [];
        const raw = JSON.parse(fs.readFileSync(EVENTS_CACHE_FILE, 'utf8'));
        if (!Array.isArray(raw)) return [];
        return hydrateEvents(raw);
    } catch (e) {
        return [];
    }
}

function saveEventsCache(events) {
    writeAsync(EVENTS_CACHE_FILE, JSON.stringify(serializeEvents(events)));
}

function hydrateEvents(arr) {
    const out = [];
    for (const e of arr) {
        if (!e || !e.id || !e.start || !e.end) continue;
        out.push({
            ...e,
            start: new Date(e.start),
            end: new Date(e.end),
        });
    }
    return out;
}

function serializeEvents(arr) {
    return arr.map(e => ({
        ...e,
        start: e.start instanceof Date ? e.start.toISOString() : e.start,
        end:   e.end   instanceof Date ? e.end.toISOString()   : e.end,
    }));
}

function isToday(d) {
    const now = new Date();
    return d.getFullYear() === now.getFullYear()
        && d.getMonth() === now.getMonth()
        && d.getDate() === now.getDate();
}

function getTodayEvents() {
    return eventsCache.filter(e => isToday(e.start) || isToday(e.end)
        || (e.start.getTime() <= Date.now() && e.end.getTime() >= Date.now()));
}

function clearAllReminders() {
    for (const t of reminderTimers.values()) clearTimeout(t);
    reminderTimers.clear();
}

function scheduleReminder(eventId, fireAt, label, kind) {
    const key = eventId + ':' + kind;
    if (reminderTimers.has(key)) clearTimeout(reminderTimers.get(key));
    const delay = fireAt - Date.now();
    if (delay <= 0) return; // already past
    if (delay > 24 * 60 * 60 * 1000) return; // we re-schedule daily; ignore far-future
    if (firedReminders.has(key)) return;
    const handle = setTimeout(() => {
        firedReminders.add(key);
        reminderTimers.delete(key);
        fireReminderNotification(eventId, label, kind);
    }, delay);
    reminderTimers.set(key, handle);
}

function rescheduleReminders() {
    clearAllReminders();
    for (const e of getTodayEvents()) {
        // Status events are background bands, not actionable — skip.
        if (e.type === 'status') continue;
        // Lead reminder (5 min before)
        const startTs = e.start.getTime();
        scheduleReminder(e.id, startTs - REMINDER_LEAD_MIN * 60 * 1000, e.title || '事件',
                         'lead');
        // Start reminder
        scheduleReminder(e.id, startTs, e.title || '事件', 'start');
    }
}

function fireReminderNotification(eventId, title, kind) {
    if (!Notification.isSupported()) return;
    const event = eventsCache.find(e => e.id === eventId);
    if (!event) return;
    const startStr = formatTime(event.start);
    const endStr   = formatTime(event.end);
    const body = kind === 'lead'
        ? `${REMINDER_LEAD_MIN} 分钟后开始 · ${startStr} – ${endStr}`
        : `开始了 · ${startStr} – ${endStr}`;

    const n = new Notification({
        title: `tPlanner · ${title}`,
        body,
        icon: getIconPath(),
        silent: false,
    });
    n.on('click', () => {
        if (mainWindow) {
            if (mainWindow.isMinimized()) mainWindow.restore();
            mainWindow.show();
            mainWindow.focus();
        }
    });
    n.show();
}

function pad2(n) { return n < 10 ? '0' + n : '' + n; }
function formatTime(d) { return pad2(d.getHours()) + ':' + pad2(d.getMinutes()); }

function broadcastEventsToWidget() {
    if (widgetWindow && !widgetWindow.isDestroyed()) {
        widgetWindow.webContents.send('widget:events', serializeEvents(eventsCache));
    }
}

// ── Icon ───────────────────────────────────────────────────────────────────
function getIconPath() {
    // On Linux nativeImage can't reliably decode .ico — prefer .png
    const isPng = process.platform === 'linux';
    const candidates = isPng
        ? [
            path.join(__dirname, '../icon.png'),
            path.join(__dirname, '../public/icon.png'),
            path.join(app.getAppPath(), 'icon.png'),
            // fallback to ico if no png found
            path.join(__dirname, '../icon.ico'),
            path.join(__dirname, '../public/icon.ico'),
          ]
        : [
            path.join(__dirname, '../icon.ico'),
            path.join(__dirname, '../public/icon.ico'),
            path.join(app.getAppPath(), 'icon.ico'),
          ];
    for (const p of candidates) {
        if (fs.existsSync(p)) return p;
    }
    return undefined;
}

// ── System Tray ────────────────────────────────────────────────────────────
function rebuildTrayMenu() {
    if (!tray) return;
    const widgetOpen = widgetWindow && !widgetWindow.isDestroyed() && widgetWindow.isVisible();
    tray.setContextMenu(Menu.buildFromTemplate([
        { label: '打开 tPlanner', click: () => { mainWindow?.show(); mainWindow?.focus(); } },
        {
            label: widgetOpen ? '隐藏今日便签' : '显示今日便签',
            click: () => {
                if (widgetOpen) {
                    widgetWindow.hide();
                    widgetVisibleByUser = false;
                    saveWidgetState({ visible: false });
                } else {
                    createWidgetWindow();
                    widgetVisibleByUser = true;
                    saveWidgetState({ visible: true });
                }
                rebuildTrayMenu();
            },
        },
        { type: 'separator' },
        { label: '退出', click: () => { app.isQuitting = true; tray.destroy(); tray = null; app.quit(); } },
    ]));
}

function createTray() {
    const iconPath = getIconPath();
    if (!iconPath) {
        console.warn('[tray] No icon found — skipping tray');
        return;
    }
    if (IS_DEV) console.log('[tray] icon path:', iconPath);

    let trayIcon;
    if (process.platform === 'linux') {
        // On Linux, pass the path directly so libappindicator handles sizing.
        // Passing a nativeImage that has been resized can produce an empty image
        // on some GTK/AppIndicator stacks, which results in the placeholder "!" icon.
        trayIcon = iconPath;
    } else {
        trayIcon = nativeImage.createFromPath(iconPath).resize({ width: 16, height: 16 });
    }

    tray = new Tray(trayIcon);
    tray.setToolTip('tPlanner');
    rebuildTrayMenu();
    tray.on('double-click', () => { mainWindow?.show(); mainWindow?.focus(); });
}

// ── .tptheme File Association ──────────────────────────────────────────────

/**
 * Read a .tptheme file and push it to the renderer via IPC.
 * Called both on macOS 'open-file' and from process.argv parsing on Windows.
 */
function handleThemeFile(filePath) {
    if (!filePath || !filePath.endsWith('.tptheme')) return;
    if (!fs.existsSync(filePath)) return;

    try {
        const content = fs.readFileSync(filePath, 'utf8');
        // Validate JSON
        JSON.parse(content);

        if (mainWindow && !mainWindow.isDestroyed() && mainWindow.isVisible()) {
            sendThemeToRenderer(filePath);
        } else {
            // Queue it — will be sent after window is ready
            pendingThemeFile = filePath;
            if (mainWindow) mainWindow.show();
        }
    } catch (e) {
        dialog.showErrorBox('主题安装失败', `文件无效或损坏：\n${e.message}`);
    }
}

function sendThemeToRenderer(filePath) {
    try {
        const content = fs.readFileSync(filePath, 'utf8');
        mainWindow?.webContents.send('theme:install', content);
    } catch (e) {
        console.error('[tptheme] Failed to read file:', e);
    }
}

// macOS: file opened while app is running
app.on('open-file', (event, filePath) => {
    event.preventDefault();
    handleThemeFile(filePath);
});

// Windows: second instance with file argument
// (first instance is already running — receive argv from second)
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
    app.quit();
} else {
    app.on('second-instance', (_event, argv) => {
        // Bring window to front
        if (mainWindow) {
            if (mainWindow.isMinimized()) mainWindow.restore();
            mainWindow.show();
            mainWindow.focus();
        }
        // Find .tptheme in argv
        const themeArg = argv.find(a => a.endsWith('.tptheme'));
        if (themeArg) handleThemeFile(themeArg);
    });
}

// ── IPC Handlers — Window Controls ────────────────────────────────────────
ipcMain.on('window:minimize', () => mainWindow?.minimize());
ipcMain.on('window:maximize', () => {
    if (!mainWindow) return;
    mainWindow.isMaximized() ? mainWindow.unmaximize() : mainWindow.maximize();
});
ipcMain.on('window:close',  () => { if (mainWindow) mainWindow.hide(); });
ipcMain.on('window:quit',   () => { tray?.destroy(); tray = null; app.quit(); });

// ── DevTools / Debug ──────────────────────────────────────────────────────
ipcMain.on('devtools:toggle', () => {
    if (!mainWindow) return;
    if (mainWindow.webContents.isDevToolsOpened()) {
        mainWindow.webContents.closeDevTools();
    } else {
        mainWindow.webContents.openDevTools({ mode: 'detach' });
    }
});
ipcMain.handle('devtools:getMemory', () => process.memoryUsage());
ipcMain.handle('devtools:getPerfInfo', () => ({
    processMemory: process.memoryUsage(),
    uptime: process.uptime(),
    platform: process.platform,
    nodeVersion: process.version,
    electronVersion: process.versions.electron,
    cpuUsage: process.cpuUsage(),
}));

ipcMain.handle('app:version',     () => app.getVersion());
ipcMain.handle('app:isMaximized', () => mainWindow?.isMaximized() ?? false);

// ── IPC Handlers — Theme ───────────────────────────────────────────────────
/**
 * Renderer can ask main to open a file-picker for .tptheme files.
 * This is for users who prefer the menu over drag-and-drop.
 */
ipcMain.handle('theme:openFilePicker', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
        title: '安装 tPlanner 主题',
        filters: [{ name: 'tPlanner Theme', extensions: ['tptheme'] }],
        properties: ['openFile'],
    });
    if (result.canceled || !result.filePaths.length) return null;
    const filePath = result.filePaths[0];
    try {
        return fs.readFileSync(filePath, 'utf8');
    } catch (e) {
        return null;
    }
});

// ── IPC Handlers — Zoom / GUI Scale ───────────────────────────────────────
ipcMain.handle('app:setZoom', (_, factor) => {
    const clamped = Math.max(0.5, Math.min(2.0, Number(factor) || 1));
    if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.setZoomFactor(clamped);
    }
    // Persist for next startup
    try {
        const zoomFile = path.join(app.getPath('userData'), 'zoom.json');
        writeAsync(zoomFile, JSON.stringify({ factor: clamped }));
    } catch (e) { /* ignore */ }
    return clamped;
});

ipcMain.handle('app:getZoom', () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
        return mainWindow.webContents.getZoomFactor();
    }
    return 1.0;
});

function setupMaximizeListeners() {
    mainWindow?.on('maximize',   () => mainWindow?.webContents.send('window:maximized', true));
    mainWindow?.on('unmaximize', () => mainWindow?.webContents.send('window:maximized', false));
}

// ── IPC Handlers — Widget / Events Sync ───────────────────────────────────
/**
 * Renderer (main app) calls this whenever its event list changes. Main
 * stores the events, persists them, broadcasts to the widget, and
 * recomputes today's reminders.
 */
// Debounce: 快速连续操作（删除、批量更新）只触发一次写盘
let syncDebounceTimer = null;
ipcMain.on('events:sync', (_e, raw) => {
    if (!Array.isArray(raw)) return;
    eventsCache = hydrateEvents(raw);   // 内存更新立即生效
    broadcastEventsToWidget();           // widget 立即刷新，不需要等写盘

    clearTimeout(syncDebounceTimer);
    syncDebounceTimer = setTimeout(() => {
        saveEventsCache(eventsCache);    // 延迟写盘，避免频繁 I/O
        firedReminders.clear();
        rescheduleReminders();
    }, 300);
});

/** Widget renderer pulls events on init or by user request. */
ipcMain.handle('widget:getEvents', () => serializeEvents(eventsCache));

ipcMain.on('widget:show', () => { createWidgetWindow(); rebuildTrayMenu(); });
ipcMain.on('widget:hide', () => {
    if (widgetWindow && !widgetWindow.isDestroyed()) widgetWindow.hide();
    saveWidgetState({ visible: false });
    rebuildTrayMenu();
});
ipcMain.on('widget:close', () => {
    if (widgetWindow && !widgetWindow.isDestroyed()) widgetWindow.hide();
    saveWidgetState({ visible: false });
    rebuildTrayMenu();
});
ipcMain.on('widget:openMain', () => {
    if (mainWindow) {
        if (mainWindow.isMinimized()) mainWindow.restore();
        mainWindow.show();
        mainWindow.focus();
    }
});
ipcMain.handle('widget:toggleAlwaysOnTop', () => {
    if (!widgetWindow || widgetWindow.isDestroyed()) return false;
    const next = !widgetWindow.isAlwaysOnTop();
    widgetWindow.setAlwaysOnTop(next);
    saveWidgetState({ alwaysOnTop: next });
    return next;
});
ipcMain.handle('widget:isAlwaysOnTop', () => {
    if (!widgetWindow || widgetWindow.isDestroyed()) return true;
    return widgetWindow.isAlwaysOnTop();
});

/** Mark a task as completed from the widget. */
ipcMain.on('widget:toggleTask', (_e, eventId) => {
    const idx = eventsCache.findIndex(e => e.id === eventId);
    if (idx < 0) return;
    const ev = eventsCache[idx];
    if (ev.type !== 'task') return;
    ev.completed = !ev.completed;
    ev.updatedAt = Date.now();
    saveEventsCache(eventsCache);
    if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('events:remoteUpdate', { id: eventId, completed: ev.completed });
    }
    broadcastEventsToWidget();
});

ipcMain.on('widget:toggleSubtask', (_e, eventId, subtaskId) => {
    const idx = eventsCache.findIndex(e => e.id === eventId);
    if (idx < 0) return;
    const ev = eventsCache[idx];
    if (!Array.isArray(ev.checklist)) return;

    const sub = ev.checklist.find(s => s.id === subtaskId);
    if (!sub) return;
    sub.completed = !sub.completed;

    // Mirror the same auto-complete logic as EventDetailsModal
    const allDone  = ev.checklist.every(s => s.completed);
    const anyUndone = ev.checklist.some(s => !s.completed);
    if (allDone)   ev.completed = true;
    if (anyUndone) ev.completed = false;

    ev.updatedAt = Date.now();
    saveEventsCache(eventsCache);

    if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('events:remoteUpdate', {
            id: eventId,
            completed: ev.completed,
            checklist: ev.checklist,
        });
    }
    broadcastEventsToWidget();
});

// ── Journal (随笔) IPC ─────────────────────────────────────────────────────
function loadJournals() {
    try {
        if (fs.existsSync(JOURNALS_FILE))
            return JSON.parse(fs.readFileSync(JOURNALS_FILE, 'utf8'));
    } catch (e) { /* ignore */ }
    return {};
}

function saveJournals(data) {
    writeAsync(JOURNALS_FILE, JSON.stringify(data));
}

ipcMain.handle('journal:getAll', () => loadJournals());

ipcMain.on('journal:save', (_e, date, text) => {
    const data = loadJournals();
    if (text && text.trim()) {
        data[date] = text;
    } else {
        delete data[date];
    }
    saveJournals(data);
    // Broadcast to OTHER windows only — sender already has the latest value
    const senderId = _e.sender.id;
    BrowserWindow.getAllWindows().forEach(win => {
        if (!win.isDestroyed() && win.webContents.id !== senderId)
            win.webContents.send('journal:updated', date, text);
    });
});

// ── Daily Checklist ────────────────────────────────────────────────────────
const CHECKLISTS_FILE = path.join(app.getPath('userData'), 'daily-checklists.json');

function loadChecklists() {
    try {
        if (fs.existsSync(CHECKLISTS_FILE))
            return JSON.parse(fs.readFileSync(CHECKLISTS_FILE, 'utf8'));
    } catch (e) { /* ignore */ }
    return {};
}

function saveChecklists(data) {
    writeAsync(CHECKLISTS_FILE, JSON.stringify(data));
}

ipcMain.handle('checklist:getAll', () => loadChecklists());

ipcMain.on('checklist:save', (_e, date, items) => {
    const data = loadChecklists();
    if (Array.isArray(items) && items.length > 0) {
        data[date] = items;
    } else {
        delete data[date];
    }
    saveChecklists(data);
    const senderId = _e.sender.id;
    BrowserWindow.getAllWindows().forEach(win => {
        if (!win.isDestroyed() && win.webContents.id !== senderId)
            win.webContents.send('checklist:updated', date, items);
    });
});

// ── LAN Sync ───────────────────────────────────────────────────────────────
const LAN_CONFIG_FILE = path.join(app.getPath('userData'), 'lan-sync.json');
const DEFAULT_LAN_CONFIG = { peerIp: '', port: 37401, serverEnabled: false };

let lanServer = null;

function loadLanConfig() {
    try {
        if (fs.existsSync(LAN_CONFIG_FILE))
            return { ...DEFAULT_LAN_CONFIG, ...JSON.parse(fs.readFileSync(LAN_CONFIG_FILE, 'utf8')) };
    } catch (e) { /* ignore */ }
    return { ...DEFAULT_LAN_CONFIG };
}

function saveLanConfig(cfg) {
    writeAsync(LAN_CONFIG_FILE, JSON.stringify(cfg));
}

function startLanServer(port) {
    if (lanServer) {
        lanServer.close();
        lanServer = null;
    }
    lanServer = http.createServer((req, res) => {
        res.setHeader('Access-Control-Allow-Origin', '*');
        res.setHeader('Access-Control-Allow-Methods', 'GET, PUT, OPTIONS');
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
        if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

        if (req.url === '/tplanner/events') {
            if (req.method === 'GET') {
                const data = JSON.stringify(serializeEvents(eventsCache));
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(data);
            } else if (req.method === 'PUT') {
                let body = '';
                req.on('data', c => body += c);
                req.on('end', () => {
                    try {
                        const incoming = JSON.parse(body);
                        if (Array.isArray(incoming)) {
                            // Merge: keep latest updatedAt per id
                            const map = new Map(eventsCache.map(e => [e.id, e]));
                            for (const e of hydrateEvents(incoming)) {
                                const existing = map.get(e.id);
                                if (!existing || (e.updatedAt || 0) > (existing.updatedAt || 0)) {
                                    map.set(e.id, e);
                                }
                            }
                            eventsCache = Array.from(map.values());
                            saveEventsCache(eventsCache);
                            rescheduleReminders();
                            broadcastEventsToWidget();
                            // Notify main window to refresh from cache
                            if (mainWindow && !mainWindow.isDestroyed()) {
                                mainWindow.webContents.send('lan:eventsUpdated', serializeEvents(eventsCache));
                            }
                        }
                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ ok: true, count: eventsCache.length }));
                    } catch (e) {
                        res.writeHead(400); res.end(JSON.stringify({ error: 'bad json' }));
                    }
                });
            } else {
                res.writeHead(405); res.end();
            }
        } else {
            res.writeHead(404); res.end();
        }
    });
    lanServer.listen(port, '0.0.0.0', () => {
        console.log(`[LAN Sync] Server listening on port ${port}`);
    });
    lanServer.on('error', (e) => {
        console.error('[LAN Sync] Server error:', e.message);
        if (mainWindow && !mainWindow.isDestroyed())
            mainWindow.webContents.send('lan:serverError', e.message);
    });
}

const DISCOVER_PORT = 37402;
const DISCOVER_TIMEOUT_MS = 2500;

ipcMain.handle('lan:discover', () => new Promise((resolve) => {
    const found = new Map();
    const sock  = dgram.createSocket({ type: 'udp4', reuseAddr: true });

    sock.on('message', (msg, rinfo) => {
        try {
            const info = JSON.parse(msg.toString());
            // 过滤掉本机自己的服务（避免把自己显示出来）
            const key = `${info.ip}:${info.port}`;
            found.set(key, { ...info, ip: info.ip || rinfo.address });
        } catch (_) {}
    });

    sock.on('error', () => {});

    sock.bind(0, () => {
        sock.setBroadcast(true);
        const probe = Buffer.from('TPLANNER_DISCOVER');
        // 全局广播
        sock.send(probe, DISCOVER_PORT, '255.255.255.255');
        // 同时尝试本机所在各子网广播
        const { networkInterfaces } = require('os');
        const nets = networkInterfaces();
        for (const iface of Object.values(nets)) {
            for (const net of iface) {
                if (net.family !== 'IPv4' || net.internal) continue;
                const parts = net.address.split('.');
                const bcast = `${parts[0]}.${parts[1]}.${parts[2]}.255`;
                sock.send(probe, DISCOVER_PORT, bcast);
            }
        }
    });

    setTimeout(() => {
        try { sock.close(); } catch (_) {}
        resolve(Array.from(found.values()));
    }, DISCOVER_TIMEOUT_MS);
}));

ipcMain.handle('lan:getConfig', () => loadLanConfig());
ipcMain.on('lan:saveConfig', (_e, cfg) => {
    saveLanConfig(cfg);
    if (cfg.serverEnabled) {
        startLanServer(cfg.port || 37401);
    } else if (lanServer) {
        lanServer.close();
        lanServer = null;
    }
});
ipcMain.handle('lan:getLocalIp', () => {
    const { networkInterfaces } = require('os');
    const nets = networkInterfaces();
    for (const name of Object.keys(nets)) {
        for (const net of nets[name]) {
            if (net.family === 'IPv4' && !net.internal) return net.address;
        }
    }
    return '127.0.0.1';
});

// ── App Lifecycle ─────────────────────────────────────────────────────────
app.whenReady().then(() => {
    // Check argv for .tptheme on first launch (Windows double-click)
    const themeArg = process.argv.find(a => a.endsWith('.tptheme'));
    if (themeArg) pendingThemeFile = themeArg;

    // Bring back persisted events so reminders work even if the main app
    // window hasn't pushed yet.
    eventsCache = loadEventsCache();
    rescheduleReminders();

    createWindow();
    createTray();

    // Auto-start LAN server if it was enabled last session
    const lanCfg = loadLanConfig();
    if (lanCfg.serverEnabled) startLanServer(lanCfg.port || 37401);

    // Open the widget if the user had it open last session.
    const widgetState = loadWidgetState();
    if (widgetState.visible) createWidgetWindow();

    // Re-evaluate reminders at midnight so tomorrow's schedule kicks in.
    setInterval(rescheduleReminders, 5 * 60 * 1000);

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

app.on('before-quit', () => { app.isQuitting = true; });

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin' && !tray) app.quit();
});
