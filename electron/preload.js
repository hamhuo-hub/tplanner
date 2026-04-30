const { contextBridge, ipcRenderer } = require('electron');

/**
 * Electron Preload — Secure bridge between Main and Renderer processes.
 * Only explicitly whitelisted APIs are exposed via contextBridge.
 */
contextBridge.exposeInMainWorld('electronAPI', {
    // ── Platform Info ───────────────────────────────────────────────────
    platform: process.platform, // 'win32' | 'darwin' | 'linux'
    isElectron: true,

    // ── Window Controls ──────────────────────────────────────────────────
    minimize: () => ipcRenderer.send('window:minimize'),
    maximize: () => ipcRenderer.send('window:maximize'),
    close: () => ipcRenderer.send('window:close'),
    quit: () => ipcRenderer.send('window:quit'),
    isMaximized: () => ipcRenderer.invoke('app:isMaximized'),

    // ── Maximize State Listener ──────────────────────────────────────────
    onMaximizeChange: (callback) => {
        const handler = (_event, isMaximized) => callback(isMaximized);
        ipcRenderer.on('window:maximized', handler);
        // Return cleanup function
        return () => ipcRenderer.removeListener('window:maximized', handler);
    },

    // ── App Info ─────────────────────────────────────────────────────────
    getVersion: () => ipcRenderer.invoke('app:version'),
});
