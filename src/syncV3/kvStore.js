// 极简 async KV:IndexedDB 单对象存储,同步层专用(命令 outbox / 元数据 / 回执)。
// 刻意不用 RxDB:同步内部状态不需要响应式,indexedDB 原生接口零依赖、可注入测试。
export function createIndexedDbKvStore({ dbName = 'tplanner-sync-v3', storeName = 'kv' } = {}) {
    let dbPromise = null;

    const open = () => {
        if (dbPromise) return dbPromise;
        dbPromise = new Promise((resolve, reject) => {
            const req = indexedDB.open(dbName, 1);
            req.onupgradeneeded = () => req.result.createObjectStore(storeName);
            req.onsuccess = () => resolve(req.result);
            req.onerror = () => reject(req.error);
        });
        return dbPromise;
    };

    const tx = (mode, fn) =>
        open().then(
            (db) =>
                new Promise((resolve, reject) => {
                    const t = db.transaction(storeName, mode);
                    const store = t.objectStore(storeName);
                    const outcome = fn(store);
                    t.oncomplete = () => resolve(outcome?.result);
                    t.onerror = () => reject(t.error);
                    t.onabort = () => reject(t.error ?? new Error('indexeddb transaction aborted'));
                }),
        );

    return {
        async get(key) {
            const row = await tx('readonly', (s) => s.get(key));
            return row?.value;
        },
        async set(key, value) {
            return tx('readwrite', (s) => s.put({ key, value }, key));
        },
        async setMany(entries) {
            return tx('readwrite', (s) => {
                for (const [key, value] of entries) s.put({ key, value }, key);
            });
        },
        async delete(key) {
            return tx('readwrite', (s) => s.delete(key));
        },
        async entries(prefix) {
            const rows = await tx('readonly', (s) => s.getAll());
            return (rows ?? []).filter((r) => r.key.startsWith(prefix)).map((r) => [r.key, r.value]);
        },
    };
}

export function createMemoryKvStore() {
    const map = new Map();
    const clone = (v) => (v === undefined ? undefined : structuredClone(v));
    return {
        async get(key) {
            return clone(map.get(key));
        },
        async set(key, value) {
            map.set(key, clone(value));
        },
        async setMany(entries) {
            const staged = new Map(map);
            for (const [key, value] of entries) staged.set(key, clone(value));
            map.clear();
            for (const [key, value] of staged) map.set(key, value);
        },
        async delete(key) {
            map.delete(key);
        },
        async entries(prefix) {
            return [...map.entries()]
                .filter(([k]) => k.startsWith(prefix))
                .map(([k, v]) => [k, clone(v)]);
        },
    };
}
