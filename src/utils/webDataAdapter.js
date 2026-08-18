/**
 * Web-mode data adapter — direct HTTP calls to the sync server API.
 *
 * Since the web app and sync server run on the same Raspberry Pi, there is no
 * need for LanSync or RxDB. The web app reads/writes directly via the server's
 * existing REST endpoints, which persist to JSON files on disk.
 *
 * Used only when !isElectron (i.e., running in a browser, not the desktop app).
 */

import { getSyncClientId } from './syncClient';

const API = ''; // same origin — the server IS the web host
const AUTH_SESSION_KEY = 'tplanner_web_auth_session';
const AUTH_PERSIST_KEY = 'tplanner_web_auth_persist';

let authHeader = null;

function readStoredAuth() {
    if (typeof window === 'undefined') return null;
    return sessionStorage.getItem(AUTH_SESSION_KEY) || localStorage.getItem(AUTH_PERSIST_KEY);
}

authHeader = readStoredAuth();

function encodeBasicCredentials(account, password) {
    const bytes = new TextEncoder().encode(`${account}:${password}`);
    const binary = Array.from(bytes, byte => String.fromCharCode(byte)).join('');
    return `Basic ${btoa(binary)}`;
}

function persistAuth(header, remember) {
    authHeader = header;
    sessionStorage.removeItem(AUTH_SESSION_KEY);
    localStorage.removeItem(AUTH_PERSIST_KEY);
    (remember ? localStorage : sessionStorage).setItem(
        remember ? AUTH_PERSIST_KEY : AUTH_SESSION_KEY,
        header,
    );
}

export function hasStoredWebAuth() {
    return !!readStoredAuth();
}

export function clearWebAuth() {
    authHeader = null;
    if (typeof window === 'undefined') return;
    sessionStorage.removeItem(AUTH_SESSION_KEY);
    localStorage.removeItem(AUTH_PERSIST_KEY);
}

async function apiFetch(input, init = {}, authorization = authHeader) {
    const headers = new Headers(init.headers || {});
    if (authorization) headers.set('Authorization', authorization);
    headers.set('X-TPlanner-Client', getSyncClientId());
    return fetch(input, { ...init, headers });
}

async function verifyAuthorization(authorization) {
    const response = await apiFetch(`${API}/tplanner/events`, {
        method: 'GET',
        cache: 'no-store',
    }, authorization);

    if (response.status === 401) return false;
    if (!response.ok) throw new Error(`认证服务暂时不可用（HTTP ${response.status}）`);
    return true;
}

export async function authenticateWeb(account, password, remember = true) {
    const authorization = encodeBasicCredentials(account.trim(), password);
    const authenticated = await verifyAuthorization(authorization);
    if (!authenticated) return false;
    persistAuth(authorization, remember);
    return true;
}

export async function restoreWebAuth() {
    const stored = readStoredAuth();
    if (!stored) return false;

    const authenticated = await verifyAuthorization(stored);
    if (!authenticated) clearWebAuth();
    else authHeader = stored;
    return authenticated;
}

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
    const res = await apiFetch(`${API}/tplanner/events`);
    if (!res.ok) throw new Error(`GET events: HTTP ${res.status}`);
    const raw = await res.json();
    return hydrateDates(raw);
}

export async function saveEvents(events) {
    // Merge with server: read current, upsert our changes, write back.
    // This keeps tombstones and other clients' changes intact.
    const res = await apiFetch(`${API}/tplanner/events`);
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

    const putRes = await apiFetch(`${API}/tplanner/events`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(merged),
    });
    if (!putRes.ok) throw new Error(`PUT events: HTTP ${putRes.status}`);
    return putRes.json();
}

// ── Journals ───────────────────────────────────────────────────────────────

export async function loadJournals() {
    const res = await apiFetch(`${API}/tplanner/journals`);
    if (!res.ok) throw new Error(`GET journals: HTTP ${res.status}`);
    return res.json();
}

export async function saveJournals(journals) {
    const res = await apiFetch(`${API}/tplanner/journals`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(journals),
    });
    if (!res.ok) throw new Error(`PUT journals: HTTP ${res.status}`);
    return res.json();
}

// ── Insights (read-only for web) ───────────────────────────────────────────

export async function loadInsights() {
    const res = await apiFetch(`${API}/tplanner/insights`);
    if (!res.ok) throw new Error(`GET insights: HTTP ${res.status}`);
    return res.json();
}

// ── Cross-device change feed ──────────────────────────────────────────────

/** Long-poll until another client writes data, then return affected datasets. */
export async function waitForRemoteChanges(since, signal) {
    const query = new URLSearchParams({
        since: String(since || 0),
        clientId: getSyncClientId(),
        wait: '25000',
    });
    const res = await apiFetch(`${API}/tplanner/changes?${query}`, {
        method: 'GET',
        cache: 'no-store',
        signal,
    });
    if (!res.ok) throw new Error(`GET changes: HTTP ${res.status}`);
    return res.json();
}
