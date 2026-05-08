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
});
