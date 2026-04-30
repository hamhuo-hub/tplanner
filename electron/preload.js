const { contextBridge, ipcRenderer } = require('electron');

/**
 * Electron Preload — Secure contextBridge.
 * Exposes only whitelisted APIs to the renderer.
 */
contextBridge.exposeInMainWorld('electronAPI', {
    // ── Platform ─────────────────────────────────────────────────────────
    platform:   process.platform,
    isElectron: true,

    // ── Window Controls ───────────────────────────────────────────────────
    minimize:    () => ipcRenderer.send('window:minimize'),
    maximize:    () => ipcRenderer.send('window:maximize'),
    close:       () => ipcRenderer.send('window:close'),
    quit:        () => ipcRenderer.send('window:quit'),
    isMaximized: () => ipcRenderer.invoke('app:isMaximized'),

    onMaximizeChange: (callback) => {
        const handler = (_e, v) => callback(v);
        ipcRenderer.on('window:maximized', handler);
        return () => ipcRenderer.removeListener('window:maximized', handler);
    },

    // ── App Info ──────────────────────────────────────────────────────────
    getVersion: () => ipcRenderer.invoke('app:version'),

    // ── Theme API ─────────────────────────────────────────────────────────

    /**
     * Listen for theme installation events pushed from main process.
     * Triggered when the user double-clicks a .tptheme file.
     * @param {(jsonString: string) => void} callback
     */
    onThemeInstall: (callback) => {
        const handler = (_e, jsonString) => callback(jsonString);
        ipcRenderer.on('theme:install', handler);
        return () => ipcRenderer.removeListener('theme:install', handler);
    },

    /**
     * Open a native file picker to select a .tptheme file.
     * Returns the raw JSON string of the selected file, or null if cancelled.
     * @returns {Promise<string|null>}
     */
    openThemePicker: () => ipcRenderer.invoke('theme:openFilePicker'),

    // ── Zoom / GUI Scale ──────────────────────────────────────────────────────

    /**
     * Set the renderer zoom factor (0.5 – 2.0).
     * Delegates to webContents.setZoomFactor on the main process.
     */
    setZoom: (factor) => ipcRenderer.invoke('app:setZoom', factor),

    /**
     * Get the current renderer zoom factor.
     * @returns {Promise<number>}
     */
    getZoom: () => ipcRenderer.invoke('app:getZoom'),
});
