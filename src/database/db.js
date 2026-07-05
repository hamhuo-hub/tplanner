import { createRxDatabase, addRxPlugin } from 'rxdb';
import { getRxStorageDexie } from 'rxdb/plugins/storage-dexie';
import { RxDBUpdatePlugin } from 'rxdb/plugins/update';
import { RxDBMigrationSchemaPlugin } from 'rxdb/plugins/migration-schema';
import { eventSchema, goalSchema } from './schema';

addRxPlugin(RxDBUpdatePlugin);
addRxPlugin(RxDBMigrationSchemaPlugin);

if (process.env.NODE_ENV !== 'production') {
    import('rxdb/plugins/dev-mode').then(module => {
        addRxPlugin(module.RxDBDevModePlugin);
    });
}

// Tombstones older than 30 days can be physically removed
const TOMBSTONE_TTL_MS = 30 * 24 * 60 * 60 * 1000;

let dbPromise = null;

export const getDatabase = async () => {
    if (dbPromise) return dbPromise;

    dbPromise = (async () => {
        const db = await createRxDatabase({
            name: 'tplannerdb',
            storage: getRxStorageDexie(),
        });

        await db.addCollections({
            events: {
                schema: eventSchema,
                migrationStrategies: {
                    1: (oldDoc) => ({ ...oldDoc, deletedAt: 0 }),
                    // v1 → v2: add version field (seed from updatedAt for legacy data)
                    2: (oldDoc) => ({ ...oldDoc, version: oldDoc.updatedAt || 0 }),
                },
            },
            goals: {
                schema: goalSchema,
                migrationStrategies: {
                    // v0 → v1: add version field
                    1: (oldDoc) => ({ ...oldDoc, version: oldDoc.updatedAt || 0 }),
                },
            },
        });

        // Purge stale tombstones on startup (fire-and-forget)
        purgeOldTombstones(db).catch(() => {});

        return db;
    })();

    return dbPromise;
};

async function purgeOldTombstones(db) {
    const cutoff = Date.now() - TOMBSTONE_TTL_MS;
    const docs = await db.events
        .find({ selector: { deletedAt: { $gt: 0, $lt: cutoff } } })
        .exec();
    if (docs.length) {
        await Promise.all(docs.map(d => d.remove()));
    }
}
