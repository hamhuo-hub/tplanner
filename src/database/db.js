import { createRxDatabase, addRxPlugin } from 'rxdb';
import { getRxStorageDexie } from 'rxdb/plugins/storage-dexie';
import { eventSchema } from './schema';

// Add plugins
import { RxDBUpdatePlugin } from 'rxdb/plugins/update';
addRxPlugin(RxDBUpdatePlugin);

// Enable dev mode if not in production
if (process.env.NODE_ENV !== 'production') {
    import('rxdb/plugins/dev-mode').then(module => {
        addRxPlugin(module.RxDBDevModePlugin);
    });
}

let dbPromise = null;

export const getDatabase = async () => {
    if (dbPromise) return dbPromise;

    dbPromise = (async () => {
        const db = await createRxDatabase({
            name: 'tplannerdb',
            storage: getRxStorageDexie(),
        });
        await db.addCollections({ events: { schema: eventSchema } });
        return db;
    })();

    return dbPromise;
};
