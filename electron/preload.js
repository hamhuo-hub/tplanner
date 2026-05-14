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

    // ── Today-Widget Sync ─────────────────────────────────────────────────
    /**
     * Push the current event list to main so it can drive the widget +
     * notification scheduler. start/end can be either Date or ISO strings —
     * main re-hydrates either way.
     */
    syncEvents: (events) => ipcRenderer.send('events:sync', events),

    /**
     * Subscribe to remote updates pushed back from main (e.g. the user
     * ticked a task in the widget — main tells the React app to mirror
     * the change in RxDB so both views stay consistent).
     */
    onEventsRemoteUpdate: (callback) => {
        const handler = (_e, payload) => callback(payload);
        ipcRenderer.on('events:remoteUpdate', handler);
        return () => ipcRenderer.removeListener('events:remoteUpdate', handler);
    },

    /** Show / hide the today widget on demand. */
    showWidget: () => ipcRenderer.send('widget:show'),
    hideWidget: () => ipcRenderer.send('widget:hide'),

    // ── LAN Sync ───────────────────────────────────────────────────────────
    discoverLan:   () => ipcRenderer.invoke('lan:discover'),
    getLanConfig:  () => ipcRenderer.invoke('lan:getConfig'),
    saveLanConfig: (cfg) => ipcRenderer.send('lan:saveConfig', cfg),
    getLocalIp:    () => ipcRenderer.invoke('lan:getLocalIp'),
    onLanEventsUpdated: (cb) => {
        const h = (_e, events) => cb(events);
        ipcRenderer.on('lan:eventsUpdated', h);
        return () => ipcRenderer.removeListener('lan:eventsUpdated', h);
    },
    onLanServerError: (cb) => {
        const h = (_e, msg) => cb(msg);
        ipcRenderer.on('lan:serverError', h);
        return () => ipcRenderer.removeListener('lan:serverError', h);
    },

    // ── Journal (随笔) ─────────────────────────────────────────────────────
    getJournals: () => ipcRenderer.invoke('journal:getAll'),
    saveJournal: (date, text) => ipcRenderer.send('journal:save', date, text),
    onJournalUpdated: (callback) => {
        const handler = (_e, date, text) => callback(date, text);
        ipcRenderer.on('journal:updated', handler);
        return () => ipcRenderer.removeListener('journal:updated', handler);
    },
});
