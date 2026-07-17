/**
 * Web-mode data adapter — direct HTTP calls to the sync server API.
 *
 * Since the web app and sync server run on the same Raspberry Pi, there is no
 * need for LanSync or RxDB. The web app reads/writes directly via the server's
 * existing REST endpoints, which persist to JSON files on disk.
 *
 * Used only when !isElectron (i.e., running in a browser, not the desktop app).
 */

const API = ''; // same origin — the server IS the web host

// ── Helpers ────────────────────────────────────────────────────────────────

/** Hydrate ISO date strings back to Date objects. */
function hydrateDates(obj) {
    if (!obj) return obj;
    if (Array.isArray(obj)) return obj.map(hydrateDates);
    if (typeof obj !== 'object') return obj;
    const out = { ...obj };
    for (const k of ['start', 'end', 'updatedAt', 'deletedAt']) {
        if (out[k] && typeof out[k] === 'string' && out[k].match(/^\d{4}-\d{2}-\d{2}T/)) {
            const d = new Date(out[k]);
            if (!isNaN(d.getTime())) out[k] = d;
        }
    }
    if (Array.isArray(out.checklist)) out.checklist = out.checklist.map(hydrateDates);
    return out;
}

/** Serialize Date objects back to ISO for the wire. */
function serializeForWire(obj) {
    return JSON.parse(JSON.stringify(obj));
}

// ── Events ─────────────────────────────────────────────────────────────────

export async function loadEvents() {
    const res = await fetch(`${API}/tplanner/events`);
    if (!res.ok) throw new Error(`GET events: HTTP ${res.status}`);
    const raw = await res.json();
    return hydrateDates(raw).filter(e => !e.deletedAt);
}

export async function saveEvents(events) {
    // Merge with server: read current, upsert our changes, write back.
    // This keeps tombstones and other clients' changes intact.
    const res = await fetch(`${API}/tplanner/events`);
    if (!res.ok) throw new Error(`GET events before save: HTTP ${res.status}`);
    const serverEvents = await res.json();

    const map = new Map(serverEvents.map(e => [e.id, e]));
    for (const ev of events) {
        const existing = map.get(ev.id);
        if (!existing || (ev.updatedAt || 0) >= (existing.updatedAt || 0)) {
            map.set(ev.id, serializeForWire(ev));
        }
    }
    const merged = Array.from(map.values());

    const putRes = await fetch(`${API}/tplanner/events`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(merged),
    });
    if (!putRes.ok) throw new Error(`PUT events: HTTP ${putRes.status}`);
    return putRes.json();
}

// ── Journals ───────────────────────────────────────────────────────────────

export async function loadJournals() {
    const res = await fetch(`${API}/tplanner/journals`);
    if (!res.ok) throw new Error(`GET journals: HTTP ${res.status}`);
    return res.json();
}

export async function saveJournals(journals) {
    const res = await fetch(`${API}/tplanner/journals`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(journals),
    });
    if (!res.ok) throw new Error(`PUT journals: HTTP ${res.status}`);
    return res.json();
}

// ── Insights (read-only for web) ───────────────────────────────────────────

export async function loadInsights() {
    const res = await fetch(`${API}/tplanner/insights`);
    if (!res.ok) throw new Error(`GET insights: HTTP ${res.status}`);
    return res.json();
}
