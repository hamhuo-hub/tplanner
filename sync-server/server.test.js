import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import net from 'node:net';

const serverFile = join(dirname(fileURLToPath(import.meta.url)), 'server.js');

async function reservePort() {
    return new Promise((resolve, reject) => {
        const server = net.createServer();
        server.once('error', reject);
        server.listen(0, '127.0.0.1', () => {
            const { port } = server.address();
            server.close(error => error ? reject(error) : resolve(port));
        });
    });
}

describe('sync server change feed', () => {
    let child;
    let dataDir;
    let base;
    let stderr = '';

    beforeAll(async () => {
        const port = await reservePort();
        dataDir = mkdtempSync(join(tmpdir(), 'tplanner-sync-test-'));
        base = `http://127.0.0.1:${port}`;
        child = spawn(process.execPath, [serverFile], {
            env: { ...process.env, HOST: '127.0.0.1', PORT: String(port), DATA_DIR: dataDir },
            stdio: ['ignore', 'ignore', 'pipe'],
        });
        child.stderr.on('data', chunk => { stderr += chunk.toString(); });

        for (let attempt = 0; attempt < 50; attempt++) {
            try {
                const response = await fetch(`${base}/health`);
                if (response.ok) return;
            } catch (_) { /* wait for listen */ }
            await new Promise(resolve => setTimeout(resolve, 20));
        }
        throw new Error(`server did not start: ${stderr}`);
    });

    afterAll(async () => {
        if (child && child.exitCode === null) {
            child.kill();
            await new Promise(resolve => child.once('exit', resolve));
        }
        if (dataDir) rmSync(dataDir, { recursive: true, force: true });
    });

    test('wakes another client but suppresses the writer echo', async () => {
        const initialResponse = await fetch(`${base}/tplanner/changes?since=0&clientId=desktop-b&wait=1000`);
        const initial = await initialResponse.json();
        expect(initial.datasets).toContain('events');

        const remoteNoticePromise = fetch(
            `${base}/tplanner/changes?since=${initial.revision}&clientId=desktop-b&wait=3000`,
        ).then(response => response.json());

        const event = {
            id: 'event-1',
            type: 'task',
            title: 'one unit',
            start: '2026-08-19T00:00:00.000Z',
            end: '2026-08-19T01:00:00.000Z',
            updatedAt: 1,
        };
        const writeResponse = await fetch(`${base}/tplanner/events`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json',
                'X-TPlanner-Client': 'desktop-a',
            },
            body: JSON.stringify([event]),
        });
        expect(writeResponse.ok).toBe(true);

        const remoteNotice = await remoteNoticePromise;
        expect(remoteNotice.datasets).toEqual(['events']);

        const ownResponse = await fetch(
            `${base}/tplanner/changes?since=${initial.revision}&clientId=desktop-a&wait=1000`,
        );
        const ownNotice = await ownResponse.json();
        expect(ownNotice.revision).toBe(remoteNotice.revision);
        expect(ownNotice.datasets).toEqual([]);

        const unchangedNoticePromise = fetch(
            `${base}/tplanner/changes?since=${remoteNotice.revision}&clientId=desktop-b&wait=1000`,
        ).then(response => response.json());
        const unchangedWrite = await fetch(`${base}/tplanner/events`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json',
                'X-TPlanner-Client': 'desktop-a',
            },
            body: JSON.stringify([event]),
        });
        expect((await unchangedWrite.json()).changed).toBe(false);
        expect((await unchangedNoticePromise).datasets).toEqual([]);
    });
});
