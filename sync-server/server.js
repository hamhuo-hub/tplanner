#!/usr/bin/env node
/**
 * tPlanner Sync Server
 * 零依赖，仅使用 Node.js 内置模块
 * 适配树莓派 3B / 低内存 Linux 设备
 *
 * API:
 *   GET  /tplanner/events        → 返回全部事件 JSON
 *   PUT  /tplanner/events        → 合并上传的事件（updatedAt 优先）
 *   GET  /health                 → 健康检查
 */

'use strict';

const http  = require('http');
const fs    = require('fs');
const path  = require('path');

// ── 配置 ──────────────────────────────────────────────────────────────────────
// 公网入口是 Cloudflare Tunnel（https://sync.hamhuo.top → localhost:37401），
// 本服务只需监听本机端口，不再需要局域网发现。
const PORT = parseInt(process.env.PORT || '37401', 10);
const DATA_DIR  = process.env.DATA_DIR  || path.join(__dirname, 'data');
const DATA_FILE     = path.join(DATA_DIR, 'events.json');
const JOURNALS_FILE = path.join(DATA_DIR, 'journals.json');
const GOALS_FILE    = path.join(DATA_DIR, 'goals.json');
const LOG_FILE      = path.join(DATA_DIR, 'server.log');

// 最多保留多少个备份
const MAX_BACKUPS = 5;

// ── 启动前准备 ─────────────────────────────────────────────────────────────────
if (!fs.existsSync(DATA_DIR))     fs.mkdirSync(DATA_DIR, { recursive: true });
if (!fs.existsSync(DATA_FILE))     fs.writeFileSync(DATA_FILE, '[]');
if (!fs.existsSync(JOURNALS_FILE)) fs.writeFileSync(JOURNALS_FILE, '{}');
if (!fs.existsSync(GOALS_FILE))    fs.writeFileSync(GOALS_FILE, '[]');

// ── 日志 ──────────────────────────────────────────────────────────────────────
function log(level, msg) {
    const line = `[${new Date().toISOString()}] [${level}] ${msg}`;
    console.log(line);
    try { fs.appendFileSync(LOG_FILE, line + '\n'); } catch (_) {}
}

// ── 数据持久化 ────────────────────────────────────────────────────────────────
function readEvents() {
    try {
        const raw = fs.readFileSync(DATA_FILE, 'utf8');
        return JSON.parse(raw);
    } catch (_) {
        return [];
    }
}

function writeEvents(events) {
    // 先备份当前文件
    rotatBackup();
    fs.writeFileSync(DATA_FILE, JSON.stringify(events, null, 2));
}

function rotatBackup() {
    if (!fs.existsSync(DATA_FILE)) return;
    const ts  = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const bak = path.join(DATA_DIR, `events.${ts}.bak`);
    fs.copyFileSync(DATA_FILE, bak);

    // 清理过期备份
    const baks = fs.readdirSync(DATA_DIR)
        .filter(f => f.endsWith('.bak'))
        .map(f => path.join(DATA_DIR, f))
        .sort();                        // 按文件名（时间戳）升序
    while (baks.length > MAX_BACKUPS) {
        const old = baks.shift();
        try { fs.unlinkSync(old); } catch (_) {}
    }
}

// ── Goals 持久化 ──────────────────────────────────────────────────────────────
function readGoals() {
    try { return JSON.parse(fs.readFileSync(GOALS_FILE, 'utf8')); } catch (_) { return []; }
}

function writeGoals(goals) {
    fs.writeFileSync(GOALS_FILE, JSON.stringify(goals, null, 2));
}

// ── Journals 持久化 ───────────────────────────────────────────────────────────
// 条目格式：{ text, updatedAt, deletedAt }。旧版纯字符串格式在读取时迁移为
// { text, updatedAt: 0, deletedAt: null }，时间戳为 0 保证会被任何带时间戳的
// 写入/删除覆盖，不会再出现"软删除时间戳失效"导致的回环恢复。
function normalizeJournalEntry(value) {
    if (value && typeof value === 'object') {
        return { text: value.text || '', updatedAt: value.updatedAt || 0, deletedAt: value.deletedAt ?? null };
    }
    return { text: value || '', updatedAt: 0, deletedAt: null };
}

function normalizeJournals(map) {
    const result = {};
    for (const [date, value] of Object.entries(map || {})) {
        result[date] = normalizeJournalEntry(value);
    }
    return result;
}

function readJournals() {
    try { return normalizeJournals(JSON.parse(fs.readFileSync(JOURNALS_FILE, 'utf8'))); } catch (_) { return {}; }
}

function writeJournals(journals) {
    fs.writeFileSync(JOURNALS_FILE, JSON.stringify(journals, null, 2));
}

// ── 合并逻辑：updatedAt 较大的版本胜出；tombstone（deletedAt>0）正常参与竞争 ──
// 30 天前的 tombstone 在服务端也物理清除，防止无限积累
const TOMBSTONE_TTL_MS = 30 * 24 * 60 * 60 * 1000;

// ── 统一实体合并核心 ──────────────────────────────────────────────────────────
// events / goals / journals 底层结构不同，但同步关心的属性相同：
// 唯一标识、净荷内容、最后修改时间、软删除标记。统一映射成
// { id, payload, updatedAt, deletedAt } 后共用同一套比较/合并逻辑，
// 不必为每种数据各写一份、又要求彼此"逐字一致"的合并代码。
//
// pickEntity / mergeEntities 是跨设备共享的核心比较逻辑，必须与
// src/utils/syncLogic.js 中的同名实现逐字一致：否则两端在"打破平局"时
// 可能选出不同的胜者，导致永远收敛不到同一结果（死锁式分歧）。
function pickEntity(a, b) {
    const au = a?.updatedAt || 0, bu = b?.updatedAt || 0;
    if (au !== bu) return au > bu ? a : b;
    const ak = JSON.stringify({ payload: a?.payload, deletedAt: a?.deletedAt ?? null });
    const bk = JSON.stringify({ payload: b?.payload, deletedAt: b?.deletedAt ?? null });
    return ak >= bk ? a : b;
}

function mergeEntities(local, remote) {
    const map = new Map(local.map(e => [e.id, e]));
    for (const e of remote) {
        const ex = map.get(e.id);
        map.set(e.id, ex ? pickEntity(ex, e) : e);
    }
    return Array.from(map.values());
}

const toEventEntity   = e => ({ id: e.id, payload: e, updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt ?? null });
const toJournalEntity = (date, entry) => ({ id: date, payload: entry || {}, updatedAt: entry?.updatedAt || 0, deletedAt: entry?.deletedAt ?? null });
const journalEntries  = obj => Object.entries(obj || {}).map(([date, entry]) => toJournalEntity(date, entry));
const fromEntity      = e => e.payload;

function mergeJournals(local, incoming) {
    const merged = mergeEntities(journalEntries(local), journalEntries(normalizeJournals(incoming)));
    const now = Date.now();
    const result = {};
    for (const e of merged) {
        if (e.deletedAt && (now - e.deletedAt) >= TOMBSTONE_TTL_MS) continue;
        result[e.id] = fromEntity(e);
    }
    return result;
}

function mergeEvents(local, incoming) {
    const merged = mergeEntities(local.map(toEventEntity), incoming.map(toEventEntity)).map(fromEntity);
    const now = Date.now();
    // Drop tombstones older than TTL — both sides already have them
    return merged.filter(e => !e.deletedAt || (now - e.deletedAt) < TOMBSTONE_TTL_MS);
}

// ── HTTP 工具 ─────────────────────────────────────────────────────────────────
function setCORS(res) {
    res.setHeader('Access-Control-Allow-Origin',  '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, PUT, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
}

function json(res, statusCode, data) {
    const body = JSON.stringify(data);
    res.writeHead(statusCode, {
        'Content-Type':   'application/json',
        'Content-Length': Buffer.byteLength(body),
    });
    res.end(body);
}

function readBody(req) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        let size = 0;
        req.on('data', chunk => {
            size += chunk.length;
            if (size > 50 * 1024 * 1024) {   // 50 MB 上限
                reject(new Error('Payload too large'));
            } else {
                chunks.push(chunk);
            }
        });
        req.on('end',   () => resolve(Buffer.concat(chunks).toString('utf8')));
        req.on('error', reject);
    });
}

// ── 请求路由 ──────────────────────────────────────────────────────────────────
async function handleRequest(req, res) {
    const { method, url } = req;

    setCORS(res);

    // Preflight
    if (method === 'OPTIONS') {
        res.writeHead(204);
        res.end();
        return;
    }

    log('INFO', `${method} ${url} from ${req.socket.remoteAddress}`);

    // ── GET /health ──────────────────────────────────────────────────────────
    if (method === 'GET' && url === '/health') {
        const events = readEvents();
        const goals  = readGoals();
        json(res, 200, {
            status:    'ok',
            events:    events.length,
            goals:     goals.length,
            uptime:    Math.floor(process.uptime()),
            memory_mb: Math.round(process.memoryUsage().rss / 1024 / 1024),
            time:      new Date().toISOString(),
        });
        return;
    }

    // ── GET /tplanner/time ───────────────────────────────────────────────────
    // 客户端据此校准本机时钟与服务器的偏移量，避免设备间时钟不一致导致
    // updatedAt-wins 合并失去意义（时钟偏快的设备会永久覆盖偏慢的设备）。
    if (method === 'GET' && url === '/tplanner/time') {
        json(res, 200, { now: Date.now() });
        return;
    }

    // ── GET /tplanner/events ─────────────────────────────────────────────────
    if (method === 'GET' && url === '/tplanner/events') {
        const events = readEvents();
        log('INFO', `Serving ${events.length} events`);
        json(res, 200, events);
        return;
    }

    // ── PUT /tplanner/events ─────────────────────────────────────────────────
    if (method === 'PUT' && url === '/tplanner/events') {
        let body;
        try {
            body = await readBody(req);
        } catch (e) {
            json(res, 413, { error: e.message });
            return;
        }

        let incoming;
        try {
            incoming = JSON.parse(body);
        } catch (_) {
            json(res, 400, { error: 'Invalid JSON' });
            return;
        }

        if (!Array.isArray(incoming)) {
            json(res, 400, { error: 'Expected array' });
            return;
        }

        const local  = readEvents();
        const merged = mergeEvents(local, incoming);
        writeEvents(merged);

        log('INFO', `Merged: local=${local.length} incoming=${incoming.length} result=${merged.length}`);
        json(res, 200, { ok: true, count: merged.length });
        return;
    }

    // ── GET /tplanner/journals ───────────────────────────────────────────────
    if (method === 'GET' && url === '/tplanner/journals') {
        const journals = readJournals();
        log('INFO', `Serving ${Object.keys(journals).length} journal entries`);
        json(res, 200, journals);
        return;
    }

    // ── PUT /tplanner/journals ───────────────────────────────────────────────
    if (method === 'PUT' && url === '/tplanner/journals') {
        let body;
        try { body = await readBody(req); } catch (e) { json(res, 413, { error: e.message }); return; }
        let incoming;
        try { incoming = JSON.parse(body); } catch (_) { json(res, 400, { error: 'Invalid JSON' }); return; }
        if (typeof incoming !== 'object' || Array.isArray(incoming)) {
            json(res, 400, { error: 'Expected object' }); return;
        }
        const local  = readJournals();
        const merged = mergeJournals(local, incoming);
        writeJournals(merged);
        log('INFO', `Journals merged: ${Object.keys(merged).length} entries`);
        json(res, 200, { ok: true, count: Object.keys(merged).length });
        return;
    }

    // ── GET /tplanner/goals ──────────────────────────────────────────────────
    if (method === 'GET' && url === '/tplanner/goals') {
        const goals = readGoals();
        log('INFO', `Serving ${goals.length} goals`);
        json(res, 200, goals);
        return;
    }

    // ── PUT /tplanner/goals ──────────────────────────────────────────────────
    if (method === 'PUT' && url === '/tplanner/goals') {
        let body;
        try { body = await readBody(req); } catch (e) { json(res, 413, { error: e.message }); return; }
        let incoming;
        try { incoming = JSON.parse(body); } catch (_) { json(res, 400, { error: 'Invalid JSON' }); return; }
        if (!Array.isArray(incoming)) { json(res, 400, { error: 'Expected array' }); return; }
        const local  = readGoals();
        const merged = mergeEvents(local, incoming); // same updatedAt-wins + tombstone logic
        writeGoals(merged);
        log('INFO', `Goals merged: local=${local.length} incoming=${incoming.length} result=${merged.length}`);
        json(res, 200, { ok: true, count: merged.length });
        return;
    }

    // 404
    json(res, 404, { error: 'Not found' });
}

// ── 启动服务器 ────────────────────────────────────────────────────────────────
const server = http.createServer((req, res) => {
    handleRequest(req, res).catch(err => {
        log('ERROR', err.message);
        try { json(res, 500, { error: 'Internal server error' }); } catch (_) {}
    });
});

server.listen(PORT, '::', () => {
    log('INFO', `tPlanner Sync Server started`);
    log('INFO', `Port     : ${PORT}`);
    log('INFO', `Data dir : ${DATA_DIR}`);
    log('INFO', `Endpoint : http://localhost:${PORT}/tplanner/events (public: Cloudflare Tunnel)`);
});

server.on('error', err => {
    log('ERROR', `Server error: ${err.message}`);
    process.exit(1);
});

// 平滑关闭
process.on('SIGTERM', () => { log('INFO', 'SIGTERM received, shutting down'); server.close(() => process.exit(0)); });
process.on('SIGINT',  () => { log('INFO', 'SIGINT received, shutting down');  server.close(() => process.exit(0)); });
