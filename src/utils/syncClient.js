const CLIENT_ID_KEY = 'tplanner_sync_client_id';

let memoryClientId = null;

/** Stable per-install identifier used only to suppress a client's own notifications. */
export function getSyncClientId() {
    if (memoryClientId) return memoryClientId;

    try {
        const stored = globalThis.localStorage?.getItem(CLIENT_ID_KEY);
        if (stored) {
            memoryClientId = stored;
            return stored;
        }
    } catch (_) { /* storage can be unavailable in restricted browser contexts */ }

    memoryClientId = globalThis.crypto?.randomUUID?.()
        || `client-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    try { globalThis.localStorage?.setItem(CLIENT_ID_KEY, memoryClientId); } catch (_) {}
    return memoryClientId;
}

/** Hold one request open until another client changes server data or the wait times out. */
export async function waitForRemoteChanges(serverUrl, since, signal) {
    const base = String(serverUrl || '').replace(/\/$/, '');
    const query = new URLSearchParams({
        since: String(since || 0),
        clientId: getSyncClientId(),
        wait: '25000',
    });
    const response = await fetch(`${base}/tplanner/changes?${query}`, {
        method: 'GET',
        cache: 'no-store',
        headers: { 'X-TPlanner-Client': getSyncClientId() },
        signal,
    });
    if (!response.ok) throw new Error(`change feed: HTTP ${response.status}`);
    return response.json();
}
