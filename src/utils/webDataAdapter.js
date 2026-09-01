/**
 * Web-mode data adapter(V3)—— 与 Electron 共用同一套同步引擎
 * (IndexedDB 镜像 + 命令 outbox + 快照安装,见 src/syncV3/)。
 *
 * Web 不再把树莓派 REST 数据当直接状态,也不再 GET 整库后再 PUT 整库:
 * 本地改动先 diff 成语义命令上传,展示数据永远是"中央镜像 + 本地 pending"的投影。
 * 认证仍走 V1 兼容端点(过渡期内服务器保留 /tplanner/events 的 Basic Auth)。
 */
import { createSyncEngine } from '../syncV3/createSyncEngine';
import { appendCommands } from '../syncV3/commandOutbox';
import { assertJsonResponse } from '../syncV3/httpResponse';
import {
    diffEventsToCommands,
    diffJournalsToCommands,
    toLegacyEvents,
    toLegacyJournals,
} from '../syncV3/commandsFromData';

const AUTH_SESSION_KEY = 'tplanner_web_auth_session';
const AUTH_PERSIST_KEY = 'tplanner_web_auth_persist';

let authHeader = null;
let enginePromise = null;

function readStoredAuth() {
    if (typeof window === 'undefined') return null;
    return window.sessionStorage?.getItem(AUTH_SESSION_KEY)
        || window.localStorage?.getItem(AUTH_PERSIST_KEY)
        || null;
}

authHeader = readStoredAuth();

function encodeBasicCredentials(account, password) {
    const bytes = new TextEncoder().encode(`${account}:${password}`);
    const binary = Array.from(bytes, byte => String.fromCharCode(byte)).join('');
    return `Basic ${btoa(binary)}`;
}

function persistAuth(header, remember) {
    authHeader = header;
    window.sessionStorage?.removeItem(AUTH_SESSION_KEY);
    window.localStorage?.removeItem(AUTH_PERSIST_KEY);
    (remember ? window.localStorage : window.sessionStorage)?.setItem(
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
    window.sessionStorage?.removeItem(AUTH_SESSION_KEY);
    window.localStorage?.removeItem(AUTH_PERSIST_KEY);
}

async function apiFetch(input, init = {}, authorization = authHeader) {
    const headers = new Headers(init.headers || {});
    if (authorization) headers.set('Authorization', authorization);
    return fetch(input, { ...init, headers });
}

async function verifyAuthorization(authorization) {
    const response = await apiFetch('/tplanner/events', { method: 'GET', cache: 'no-store' }, authorization);
    if (response.status === 401 || response.status === 403) return false;
    if (!response.ok) throw new Error(`认证服务暂时不可用（HTTP ${response.status}）`);
    assertJsonResponse(response, 'authentication request');
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

// ── V3 引擎(同源部署:serverUrl 用相对路径)───────────────────────────────

async function getEngine() {
    if (!enginePromise) {
        enginePromise = createSyncEngine({ serverUrl: '', fetchFn: apiFetch });
    }
    return enginePromise;
}

/** 把本地现状 diff 成命令上传,安装最新快照,返回展示数据投影。 */
async function syncWithLocal({ events, journals } = {}) {
    const engine = await getEngine();
    const mirror = (await engine.installer.getServerMirror())
        ?? { tasks: {}, customLists: {}, journals: {}, goals: {}, insights: {} };

    const commands = [
        ...diffEventsToCommands(mirror, events ?? []),
        ...diffJournalsToCommands(mirror, journals ?? {}),
    ];
    if (commands.length > 0) await appendCommands(engine.store, commands);

    await engine.uploader.flush();
    await engine.installer.syncToLatest();
    const display = (await engine.installer.getDisplayState())
        ?? (await engine.installer.getServerMirror())
        ?? { tasks: {}, journals: {} };

    return {
        events: toLegacyEvents(display),
        journals: toLegacyJournals(display),
    };
}

// ── 数据接口(签名与旧版一致,内部已无 GET-before-save)────────────────────

export async function loadEvents() {
    const { events } = await syncWithLocal({});
    return events;
}

export async function saveEvents(events) {
    const result = await syncWithLocal({ events });
    return result.events;
}

export async function loadJournals() {
    const { journals } = await syncWithLocal({});
    return journals;
}

export async function saveJournals(journals) {
    const result = await syncWithLocal({ journals });
    return result.journals;
}

export async function loadInsights() {
    const engine = await getEngine();
    const mirror = await engine.installer.getServerMirror();
    const insights = mirror?.insights ?? {};
    const entries = Object.entries(insights).map(([id, payload]) => ({ id, ...payload }));
    return { entries, reports: {} };
}

// ── 版本监听与展示刷新 ────────────────────────────────────────────────────

/** 挂起直到服务器发布新版本(长轮询),由调用方决定何时安装。 */
export async function waitForServerChange() {
    const engine = await getEngine();
    const { notified } = await engine.notifications.pollOnce();
    return notified;
}

/** 收到通知后调用:先冲刷本地改动,再返回最新展示数据。 */
export async function refreshAndGetDisplay(events, journals) {
    return syncWithLocal({ events, journals });
}
