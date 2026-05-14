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
const dgram = require('dgram');
const fs    = require('fs');
const path  = require('path');
const os    = require('os');

// ── 配置 ──────────────────────────────────────────────────────────────────────
const PORT         = parseInt(process.env.PORT          || '37401', 10);
const DISCOVER_PORT = parseInt(process.env.DISCOVER_PORT || '37402', 10);
const DATA_DIR  = process.env.DATA_DIR  || path.join(__dirname, 'data');
const DATA_FILE = path.join(DATA_DIR, 'events.json');
const LOG_FILE  = path.join(DATA_DIR, 'server.log');

// 最多保留多少个备份
const MAX_BACKUPS = 5;

// ── 启动前准备 ─────────────────────────────────────────────────────────────────
if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
if (!fs.existsSync(DATA_FILE)) fs.writeFileSync(DATA_FILE, '[]');

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

// ── 合并逻辑：updatedAt 较大的版本胜出 ──────────────────────────────────────
function mergeEvents(local, incoming) {
    const map = new Map();
    for (const e of local)    map.set(e.id, e);
    for (const e of incoming) {
        const exist = map.get(e.id);
        if (!exist || (e.updatedAt || 0) > (exist.updatedAt || 0)) {
            map.set(e.id, e);
        }
    }
    return Array.from(map.values());
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
        json(res, 200, {
            status:    'ok',
            events:    events.length,
            uptime:    Math.floor(process.uptime()),
            memory_mb: Math.round(process.memoryUsage().rss / 1024 / 1024),
            time:      new Date().toISOString(),
        });
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

server.listen(PORT, '0.0.0.0', () => {
    // 显示本机所有 IPv4 地址，方便配置客户端
    const nets = os.networkInterfaces();
    const ips  = [];
    for (const name of Object.keys(nets)) {
        for (const net of nets[name]) {
            if (net.family === 'IPv4' && !net.internal) ips.push(net.address);
        }
    }

    log('INFO', `tPlanner Sync Server started`);
    log('INFO', `Port     : ${PORT}`);
    log('INFO', `Data dir : ${DATA_DIR}`);
    log('INFO', `LAN IPs  : ${ips.join(', ') || '(none detected)'}`);
    log('INFO', `Endpoint : http://<IP>:${PORT}/tplanner/events`);
});

server.on('error', err => {
    log('ERROR', `Server error: ${err.message}`);
    process.exit(1);
});

// ── UDP 局域网发现响应 ────────────────────────────────────────────────────────
function getLanIp() {
    const nets = os.networkInterfaces();
    for (const name of Object.keys(nets)) {
        for (const net of nets[name]) {
            if (net.family === 'IPv4' && !net.internal) return net.address;
        }
    }
    return '127.0.0.1';
}

const udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });

udp.on('message', (msg, rinfo) => {
    if (msg.toString().trim() !== 'TPLANNER_DISCOVER') return;
    const events   = readEvents();
    const response = Buffer.from(JSON.stringify({
        name:    os.hostname(),
        ip:      getLanIp(),
        port:    PORT,
        events:  events.length,
        version: '1.0',
    }));
    udp.send(response, rinfo.port, rinfo.address, (err) => {
        if (!err) log('INFO', `Discovery reply → ${rinfo.address}:${rinfo.port}`);
    });
});

udp.on('error', (err) => log('WARN', `UDP error: ${err.message}`));

udp.bind(DISCOVER_PORT, '0.0.0.0', () => {
    log('INFO', `UDP discovery listening on port ${DISCOVER_PORT}`);
});

// 平滑关闭
process.on('SIGTERM', () => { log('INFO', 'SIGTERM received, shutting down'); udp.close(); server.close(() => process.exit(0)); });
process.on('SIGINT',  () => { log('INFO', 'SIGINT received, shutting down');  udp.close(); server.close(() => process.exit(0)); });
