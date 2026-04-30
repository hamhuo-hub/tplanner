const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, shell, dialog } = require('electron');
const path = require('path');
const fs = require('fs');

// ── Constants ──────────────────────────────────────────────────────────────
const IS_DEV = process.env.NODE_ENV === 'development' || !app.isPackaged;
const VITE_DEV_SERVER_URL = 'http://localhost:5173';

// Window state persistence
const STATE_FILE = path.join(app.getPath('userData'), 'window-state.json');
const DEFAULT_STATE = { width: 1280, height: 800, x: undefined, y: undefined, maximized: false };

// ── Window State ───────────────────────────────────────────────────────────
function loadWindowState() {
    try {
        if (fs.existsSync(STATE_FILE)) {
            return { ...DEFAULT_STATE, ...JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')) };
        }
    } catch (e) { /* ignore */ }
    return { ...DEFAULT_STATE };
}

function saveWindowState(win) {
    try {
        const isMax = win.isMaximized();
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
        frame: false,           // Frameless — custom title bar
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
        show: false, // Show after ready-to-show
    });

    // Restore maximized state
    if (state.maximized) mainWindow.maximize();

    // Load the app
    if (IS_DEV) {
        mainWindow.loadURL(VITE_DEV_SERVER_URL);
        mainWindow.webContents.openDevTools({ mode: 'detach' });
    } else {
        mainWindow.loadFile(path.join(__dirname, '../dist/index.html'));
    }

    // Show window gracefully
    mainWindow.once('ready-to-show', () => {
        mainWindow.show();
    });

    // Persist window state on close
    mainWindow.on('close', () => saveWindowState(mainWindow));

    // Minimize to tray on close (like WeChat)
    mainWindow.on('close', (e) => {
        if (tray) {
            e.preventDefault();
            mainWindow.hide();
        }
    });

    mainWindow.on('closed', () => { mainWindow = null; });
}

// ── Icon Resolution ────────────────────────────────────────────────────────
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

    const contextMenu = Menu.buildFromTemplate([
        {
            label: '打开 tPlanner',
            click: () => {
                if (mainWindow) {
                    mainWindow.show();
                    mainWindow.focus();
                } else {
                    createWindow();
                }
            }
        },
        { type: 'separator' },
        {
            label: '退出',
            click: () => {
                tray.destroy();
                tray = null;
                app.quit();
            }
        }
    ]);

    tray.setContextMenu(contextMenu);
    tray.on('double-click', () => {
        if (mainWindow) {
            mainWindow.show();
            mainWindow.focus();
        }
    });
}

// ── IPC Handlers — Window Controls ────────────────────────────────────────
ipcMain.on('window:minimize', () => mainWindow?.minimize());
ipcMain.on('window:maximize', () => {
    if (!mainWindow) return;
    if (mainWindow.isMaximized()) mainWindow.unmaximize();
    else mainWindow.maximize();
});
ipcMain.on('window:close', () => {
    if (mainWindow) mainWindow.hide(); // Hide to tray instead of close
});
ipcMain.on('window:quit', () => {
    tray?.destroy();
    tray = null;
    app.quit();
});

// ── IPC Handlers — App Info ────────────────────────────────────────────────
ipcMain.handle('app:version', () => app.getVersion());
ipcMain.handle('app:isMaximized', () => mainWindow?.isMaximized() ?? false);

// Notify renderer when maximize state changes
function setupMaximizeListeners() {
    mainWindow?.on('maximize', () => mainWindow?.webContents.send('window:maximized', true));
    mainWindow?.on('unmaximize', () => mainWindow?.webContents.send('window:maximized', false));
}

// ── App Lifecycle ─────────────────────────────────────────────────────────
app.whenReady().then(() => {
    createWindow();
    createTray();
    setupMaximizeListeners();

    // macOS: re-create window if activated with no windows
    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

// Prevent quit when all windows closed (stay in tray)
app.on('window-all-closed', () => {
    if (process.platform !== 'darwin' && !tray) {
        app.quit();
    }
});
