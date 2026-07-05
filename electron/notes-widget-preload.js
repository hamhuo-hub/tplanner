const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('notesAPI', {
    toggleAlwaysOnTop: () => ipcRenderer.invoke('notes:toggleAlwaysOnTop'),
    isAlwaysOnTop:     () => ipcRenderer.invoke('notes:isAlwaysOnTop'),
    close:             () => ipcRenderer.send('notes:close'),
    openMain:          () => ipcRenderer.send('notes:openMain'),

    getJournals: () => ipcRenderer.invoke('journal:getAll'),
    saveJournal: (date, text) => ipcRenderer.send('journal:save', date, text),
    onJournalUpdated: (callback) => {
        const handler = (_e, date, text) => callback(date, text);
        ipcRenderer.on('journal:updated', handler);
        return () => ipcRenderer.removeListener('journal:updated', handler);
    },
    // LAN sync batch replace — emit each entry so widget stays in sync
    onJournalAllUpdated: (callback) => {
        const handler = (_e, merged) => {
            if (merged && typeof merged === 'object') {
                Object.entries(merged).forEach(([date, entry]) => callback(date, entry));
            }
        };
        ipcRenderer.on('journal:allUpdated', handler);
        return () => ipcRenderer.removeListener('journal:allUpdated', handler);
    },
});
