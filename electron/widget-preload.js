const { contextBridge, ipcRenderer } = require('electron');

/**
 * Preload for the today-events sticky-note widget.
 * Exposes a minimal IPC surface used by widget.js.
 */
contextBridge.exposeInMainWorld('widgetAPI', {
    /** Pull current events from main (Promise<Event[]>). */
    getEvents: () => ipcRenderer.invoke('widget:getEvents'),

    /** Subscribe to live event-list updates pushed by main. */
    onEvents: (callback) => {
        const handler = (_e, list) => callback(list);
        ipcRenderer.on('widget:events', handler);
        return () => ipcRenderer.removeListener('widget:events', handler);
    },

    /** Bring the main tPlanner window forward. */
    openMain: () => ipcRenderer.send('widget:openMain'),

    /** Hide the widget (kept alive in tray). */
    close: () => ipcRenderer.send('widget:close'),

    /** Toggle / inspect always-on-top — Promise<boolean>. */
    toggleAlwaysOnTop: () => ipcRenderer.invoke('widget:toggleAlwaysOnTop'),
    isAlwaysOnTop:     () => ipcRenderer.invoke('widget:isAlwaysOnTop'),

    /** Toggle a task's completed flag (round-trips through main). */
    toggleTask: (eventId) => ipcRenderer.send('widget:toggleTask', eventId),

    /** Toggle an individual subtask checklist item. */
    toggleSubtask: (eventId, subtaskId) => ipcRenderer.send('widget:toggleSubtask', eventId, subtaskId),

    // ── Daily Checklist ────────────────────────────────────────────────────
    getChecklists:  () => ipcRenderer.invoke('checklist:getAll'),
    saveChecklist:  (date, items) => ipcRenderer.send('checklist:save', date, items),
    onChecklistUpdated: (callback) => {
        const handler = (_e, date, items) => callback(date, items);
        ipcRenderer.on('checklist:updated', handler);
        return () => ipcRenderer.removeListener('checklist:updated', handler);
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
