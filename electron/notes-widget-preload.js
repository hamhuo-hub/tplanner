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
});
