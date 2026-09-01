import { beforeEach, describe, expect, test, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
    createSyncEngine: vi.fn(),
}));

vi.mock('../syncV3/createSyncEngine', () => ({
    createSyncEngine: mocks.createSyncEngine,
}));

import * as webDataAdapter from './webDataAdapter';

describe('web data adapter', () => {
    beforeEach(() => {
        webDataAdapter.clearWebAuth();
        vi.clearAllMocks();
    });

    test('does not accept the Vite HTML fallback as successful authentication', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => ({
            status: 200,
            ok: true,
            headers: new Headers({ 'content-type': 'text/html; charset=utf-8' }),
        })));

        await expect(webDataAdapter.authenticateWeb('user', 'password')).rejects.toThrow(
            /text\/html.*SPA fallback/i,
        );
        expect(webDataAdapter.hasStoredWebAuth()).toBe(false);
    });

    test('treats the form-login 403 response as invalid credentials', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => ({
            status: 403,
            ok: false,
            headers: new Headers({ 'content-type': 'text/plain' }),
        })));

        await expect(webDataAdapter.authenticateWeb('user', 'wrong')).resolves.toBe(false);
        expect(webDataAdapter.hasStoredWebAuth()).toBe(false);
    });

    test('passes authenticated fetch into the V3 engine and preserves caller headers', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => ({
            status: 200,
            ok: true,
            headers: new Headers({ 'content-type': 'application/json' }),
            json: async () => ({
                softwareVersion: '8.0.0',
                protocolVersion: 3,
                schemaVersion: 3,
                serverInstanceId: 'srv-web',
            }),
        })));
        mocks.createSyncEngine.mockResolvedValue({
            installer: { getServerMirror: async () => ({ insights: {} }) },
        });

        await webDataAdapter.authenticateWeb('user', 'password', false);
        await webDataAdapter.loadInsights();

        expect(mocks.createSyncEngine).toHaveBeenCalledWith({
            serverUrl: '',
            fetchFn: expect.any(Function),
        });
        const { fetchFn } = mocks.createSyncEngine.mock.calls[0][0];
        await fetchFn('/tplanner/v3/capabilities', { headers: { 'cache-control': 'no-store' } });
        const [, init] = fetch.mock.calls.at(-1);
        expect(init.headers.get('authorization')).toMatch(/^Basic /);
        expect(init.headers.get('cache-control')).toBe('no-store');
    });

    test('does not store credentials for a capability-compatible HTTP response from the wrong release', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => ({
            status: 200,
            ok: true,
            headers: new Headers({ 'content-type': 'application/json' }),
            json: async () => ({
                softwareVersion: '7.9.9',
                protocolVersion: 3,
                schemaVersion: 3,
                serverInstanceId: 'srv-web',
            }),
        })));

        await expect(webDataAdapter.authenticateWeb('user', 'password')).rejects.toThrow(
            /TPlanner 8\.0\.0.*Sync V3 schema 3/i,
        );
        expect(webDataAdapter.hasStoredWebAuth()).toBe(false);
        expect(mocks.createSyncEngine).not.toHaveBeenCalled();
    });
});
