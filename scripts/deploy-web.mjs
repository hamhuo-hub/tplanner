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
run('npx vite build');

// ── 2. Deploy ─────────────────────────────────────────────────────────────
console.log('\n=== Step 2: Deploy to Pi ===');

// scp to a temp location in hamhuo's home (avoids /root permission issues)
const TMP_DIR = '/tmp/tplanner-deploy';
console.log('Uploading files...');
run(`ssh ${SSH_TARGET} "rm -rf ${TMP_DIR} && mkdir -p ${TMP_DIR}"`);
run(`scp -r dist/* ${SSH_TARGET}:${TMP_DIR}/`);

// Also copy updated server.js
const serverJs = join(rootDir, 'sync-server', 'server.js');
if (existsSync(serverJs)) {
    console.log('Including updated server.js...');
    run(`scp "${serverJs}" ${SSH_TARGET}:${TMP_DIR}/server.js`);
}

// Move from temp to /root/tplanner-sync/ with sudo
console.log('Installing to /root/tplanner-sync/...');
run(`ssh ${SSH_TARGET} "sudo rm -rf ${WEB_DIR}/* && sudo mkdir -p ${WEB_DIR} && sudo cp -r ${TMP_DIR}/* ${WEB_DIR}/ && sudo cp ${TMP_DIR}/server.js ${PI_PATH}/server.js 2>/dev/null; rm -rf ${TMP_DIR}"`);

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
