/**
 * Deploy the web app to the Raspberry Pi.
 *
 * Usage: node scripts/deploy-web.mjs
 *
 * 1. Builds the React app (vite build) if not already built
 * 2. Clears old files on the Pi, then scps dist/ over
 * 3. Restarts the tplanner-sync service via SSH
 *
 * Prerequisites:
 *   - SSH key-based auth to pi@192.168.5.5
 *   - scp and ssh available (built-in on Windows 10+, macOS, Linux)
 *   - PI_HOST env var overrides default (192.168.5.5)
 *   - PI_USER env var overrides default (hamhuo)
 *   - PI_PATH env var overrides default (/home/hamhuo/Documents/sync-server)
 */

import { execSync } from 'child_process';
import { existsSync } from 'fs';
import { join, dirname, basename } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const rootDir = join(__dirname, '..');

const PI_HOST = process.env.PI_HOST || '192.168.5.5';
const PI_USER = process.env.PI_USER || 'hamhuo';
const PI_PATH = process.env.PI_PATH || '/root/tplanner-sync';
const SSH_TARGET = `${PI_USER}@${PI_HOST}`;
const WEB_DIR  = `${PI_PATH}/dist-web`;

function run(cmd, opts = {}) {
    console.log(`\n> ${cmd}`);
    return execSync(cmd, { stdio: 'inherit', cwd: rootDir, ...opts });
}

// ── 1. Build ──────────────────────────────────────────────────────────────
console.log('=== Step 1: Build web app ===');
if (!existsSync(join(rootDir, 'dist', 'index.html'))) {
    console.log('dist/ not found, running vite build...');
    run('npx vite build');
} else {
    console.log('dist/ already exists. Run "npm run build:web" to rebuild, or delete dist/ to force rebuild.');
    console.log('Skipping build — using existing dist/.');
}

// ── 2. Deploy ─────────────────────────────────────────────────────────────
console.log('\n=== Step 2: Deploy to Pi ===');

// Clear old files on the Pi (so removed assets don't stick around)
console.log('Clearing old web files on Pi...');
try {
    run(`ssh ${SSH_TARGET} "rm -rf ${WEB_DIR}/*"`);
} catch (e) {
    console.log('(may be first deploy — directory empty or does not exist)');
}

// Ensure dist-web/ exists on the Pi
run(`ssh ${SSH_TARGET} "mkdir -p ${WEB_DIR}"`);

// Copy new web files via scp
run(`scp -r dist/* ${SSH_TARGET}:${WEB_DIR}/`);

// Also deploy updated server.js (static file serving)
const serverJs = join(rootDir, 'sync-server', 'server.js');
if (existsSync(serverJs)) {
    console.log('Deploying updated server.js...');
    run(`scp "${serverJs}" ${SSH_TARGET}:${PI_PATH}/server.js`);
}

// ── 3. Restart service ────────────────────────────────────────────────────
console.log('\n=== Step 3: Restart sync server ===');
try {
    run(`ssh ${SSH_TARGET} "sudo systemctl restart tplanner-sync"`);
    console.log('Service restarted successfully.');
} catch (e) {
    console.error('Failed to restart service. You may need to restart manually:');
    console.error(`  ssh ${SSH_TARGET} sudo systemctl restart tplanner-sync`);
}

console.log('\n=== Done ===');
console.log(`Web app deployed to http://${PI_HOST}:37401/`);
console.log('Public URL: https://plan.hamhuo.top (after Cloudflare Tunnel config)');
