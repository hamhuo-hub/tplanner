const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, shell, dialog } = require('electron');
const path = require('path');
const fs   = require('fs');

// ── Constants ──────────────────────────────────────────────────────────────
const IS_DEV = process.env.NODE_ENV === 'development' || !app.isPackaged;
const VITE_DEV_SERVER_URL = 'http://localhost:5173';
const STATE_FILE = path.join(app.getPath('userData'), 'window-state.json');
const DEFAULT_STATE = { width: 1280, height: 800, x: undefined, y: undefined, maximized: false };

// ── Pending theme install (from double-click before window is ready) ───────
let pendingThemeFile = null;

// ── Window State ───────────────────────────────────────────────────────────
function loadWindowState() {
    try {
        if (fs.existsSync(STATE_FILE))
            return { ...DEFAULT_STATE, ...JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')) };
    } catch (e) { /* ignore */ }
    return { ...DEFAULT_STATE };
}

function saveWindowState(win) {
    try {
        const isMax  = win.isMaximized();
        const bounds = isMax ? {} : win.getBounds();
        fs.writeFileSync(STATE_FILE, JSON.stringify({ ...bounds, maximized: isMax }));
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

// ── Icon ───────────────────────────────────────────────────────────────────
function getIconPath() {
    const candidates = [
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
function createTray() {
    const iconPath = getIconPath();
    if (!iconPath) return;
    const icon = nativeImage.createFromPath(iconPath).resize({ width: 16, height: 16 });
    tray = new Tray(icon);
    tray.setToolTip('tPlanner');
    tray.setContextMenu(Menu.buildFromTemplate([
        { label: '打开 tPlanner', click: () => { mainWindow?.show(); mainWindow?.focus(); } },
        { type: 'separator' },
        { label: '退出', click: () => { tray.destroy(); tray = null; app.quit(); } }
    ]));
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
        fs.writeFileSync(zoomFile, JSON.stringify({ factor: clamped }));
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

// ── App Lifecycle ─────────────────────────────────────────────────────────
app.whenReady().then(() => {
    // Check argv for .tptheme on first launch (Windows double-click)
    const themeArg = process.argv.find(a => a.endsWith('.tptheme'));
    if (themeArg) pendingThemeFile = themeArg;

    createWindow();
    createTray();

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin' && !tray) app.quit();
});
