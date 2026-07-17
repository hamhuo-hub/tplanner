// @vitest-environment node

import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, request } from 'node:http';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const SERVER_PATH = fileURLToPath(new URL('./server.js', import.meta.url));

let serverProcess;
let serverOutput = '';
let tempRoot;
let port;

function getFreePort() {
    return new Promise((resolve, reject) => {
        const probe = createServer();

        probe.once('error', reject);
        probe.listen(0, '127.0.0.1', () => {
            const address = probe.address();
            probe.close(error => {
                if (error) reject(error);
                else resolve(address.port);
            });
        });
    });
}

function sendRequest(pathname, headers = {}) {
    return new Promise((resolve, reject) => {
        const req = request({
            hostname: '127.0.0.1',
            port,
            path: pathname,
            method: 'GET',
            headers,
        }, res => {
            const chunks = [];

            res.on('data', chunk => chunks.push(chunk));
            res.on('end', () => {
                resolve({
                    status: res.statusCode,
                    headers: res.headers,
                    body: Buffer.concat(chunks).toString('utf8'),
                });
            });
        });

        req.setTimeout(2_000, () => req.destroy(new Error('Request timed out')));
        req.once('error', reject);
        req.end();
    });
}

async function waitUntilReady() {
    const deadline = Date.now() + 5_000;

    while (Date.now() < deadline) {
        if (serverProcess.exitCode !== null) {
            throw new Error(`Sync server exited before it was ready.\n${serverOutput}`);
        }

        try {
            const response = await sendRequest('/health', { Host: 'sync.hamhuo.top' });
            if (response.status === 200) return;
        } catch {
            // The child may still be binding its port.
        }

        await new Promise(resolve => setTimeout(resolve, 25));
    }

    throw new Error(`Timed out waiting for sync server.\n${serverOutput}`);
}

function waitForExit(timeoutMs) {
    if (!serverProcess || serverProcess.exitCode !== null) return Promise.resolve(true);

    return new Promise(resolve => {
        const onExit = () => finish(true);
        const timer = setTimeout(() => finish(false), timeoutMs);

        function finish(exited) {
            clearTimeout(timer);
            serverProcess.off('exit', onExit);
            resolve(exited);
        }

        serverProcess.once('exit', onExit);
    });
}

async function stopServer() {
    if (!serverProcess || serverProcess.exitCode !== null) return;

    serverProcess.kill('SIGTERM');
    if (await waitForExit(3_000)) return;

    serverProcess.kill('SIGKILL');
    await waitForExit(3_000);
}

beforeAll(async () => {
    tempRoot = mkdtempSync(join(tmpdir(), 'tplanner-server-test-'));
    const dataDir = join(tempRoot, 'data');
    const webDir = join(tempRoot, 'dist-web');

    mkdirSync(join(webDir, 'assets'), { recursive: true });
    writeFileSync(join(webDir, 'index.html'), '<!doctype html><title>PLAN FIXTURE</title>');
    writeFileSync(join(webDir, 'assets', 'app.js'), 'globalThis.planFixture = true;');

    port = await getFreePort();

    const childEnv = { ...process.env };
    delete childEnv.WEB_HOSTS;
    Object.assign(childEnv, {
        PORT: String(port),
        DATA_DIR: dataDir,
        WEB_DIR: webDir,
    });

    serverProcess = spawn(process.execPath, [SERVER_PATH], {
        cwd: dirname(SERVER_PATH),
        env: childEnv,
        stdio: ['ignore', 'pipe', 'pipe'],
        windowsHide: true,
    });

    serverProcess.stdout.on('data', chunk => { serverOutput += chunk.toString(); });
    serverProcess.stderr.on('data', chunk => { serverOutput += chunk.toString(); });

    try {
        await waitUntilReady();
    } catch (error) {
        await stopServer();
        throw error;
    }
}, 10_000);

afterAll(async () => {
    await stopServer();
    if (tempRoot) rmSync(tempRoot, { recursive: true, force: true });
}, 10_000);

describe('sync server Host routing', () => {
    it('serves the web root and SPA fallback only on the default plan host', async () => {
        const root = await sendRequest('/', { Host: 'plan.hamhuo.top' });
        const spaRoute = await sendRequest('/calendar/today', { Host: 'plan.hamhuo.top' });

        expect(root.status).toBe(200);
        expect(root.headers['content-type']).toContain('text/html');
        expect(root.body).toContain('PLAN FIXTURE');
        expect(spaRoute.status).toBe(200);
        expect(spaRoute.headers['content-type']).toContain('text/html');
        expect(spaRoute.body).toContain('PLAN FIXTURE');
    });

    it('returns JSON 404 for the sync host root and static assets', async () => {
        const root = await sendRequest('/', { Host: 'sync.hamhuo.top' });
        const asset = await sendRequest('/assets/app.js', { Host: 'sync.hamhuo.top' });

        for (const response of [root, asset]) {
            expect(response.status).toBe(404);
            expect(response.headers['content-type']).toContain('application/json');
            expect(JSON.parse(response.body)).toEqual({ error: 'Not found' });
        }
    });

    it('keeps health and time APIs available on the sync host', async () => {
        const health = await sendRequest('/health', { Host: 'sync.hamhuo.top' });
        const time = await sendRequest('/tplanner/time', { Host: 'sync.hamhuo.top' });

        expect(health.status).toBe(200);
        expect(JSON.parse(health.body)).toMatchObject({ status: 'ok' });
        expect(time.status).toBe(200);
        expect(JSON.parse(time.body)).toEqual({ now: expect.any(Number) });
    });

    it('normalizes Host casing and port before checking the web allowlist', async () => {
        const response = await sendRequest('/', { Host: `PLAN.HAMHUO.TOP:${port}` });

        expect(response.status).toBe(200);
        expect(response.body).toContain('PLAN FIXTURE');
    });

    it('does not trust X-Forwarded-Host to bypass the allowlist', async () => {
        const response = await sendRequest('/', {
            Host: 'sync.hamhuo.top',
            'X-Forwarded-Host': 'plan.hamhuo.top',
        });

        expect(response.status).toBe(404);
        expect(response.headers['content-type']).toContain('application/json');
        expect(JSON.parse(response.body)).toEqual({ error: 'Not found' });
    });
});
