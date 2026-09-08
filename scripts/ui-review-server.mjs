import { createServer } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const server = await createServer({
    root,
    configFile: false,
    envFile: false,
    appType: 'mpa',
    plugins: [
        react(),
        {
            name: 'tplanner-offline-review',
            configureServer(vite) {
                vite.middlewares.use((request, response, next) => {
                    if (request.url === '/') {
                        response.writeHead(302, { Location: '/scripts/ui-review.html' });
                        response.end();
                        return;
                    }
                    if (/^\/(?:api|tplanner)(?:\/|\?|$)/.test(request.url || '')) {
                        response.writeHead(410, { 'Content-Type': 'text/plain; charset=utf-8' });
                        response.end('Offline UI review has no backend.');
                        return;
                    }
                    next();
                });
            },
        },
    ],
    resolve: { dedupe: ['react', 'react-dom', '@emotion/react', '@emotion/styled', '@emotion/cache', '@emotion/serialize'] },
    optimizeDeps: { entries: ['scripts/ui-review.html'], include: ['react', 'react-dom', '@emotion/react', '@emotion/styled'] },
    server: { host: '127.0.0.1', port: 4174, strictPort: true, open: false },
});

await server.listen();
console.log('Offline Web review:     http://127.0.0.1:4174/scripts/ui-review.html');
console.log('Offline Desktop review: http://127.0.0.1:4174/scripts/ui-review.html?profile=desktop');
console.log('Fixture clock: 2026-09-08 10:30 Asia/Shanghai. Event and journal changes are memory-only.');

for (const signal of ['SIGINT', 'SIGTERM']) {
    process.once(signal, async () => { await server.close(); process.exit(0); });
}
