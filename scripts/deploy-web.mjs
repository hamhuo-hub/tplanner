/**
 * Build and atomically deploy only the Web bundle to the Raspberry Pi.
 *
 * Sync Server and State Builder are independent services: this script never
 * copies their code and never restarts them.
 */
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const rootDir = join(scriptDir, '..');
const piHost = process.env.PI_HOST || '192.168.1.9';
const piUser = process.env.PI_USER || 'hamhuo';
const webRoot = process.env.PI_WEB_ROOT || '/srv/tplanner-web';
const sshTarget = `${piUser}@${piHost}`;

function requireSafe(value, pattern, label) {
    if (!pattern.test(value)) throw new Error(`Unsafe ${label}: ${value}`);
}

requireSafe(piHost, /^[A-Za-z0-9.-]+$/, 'PI_HOST');
requireSafe(piUser, /^[A-Za-z_][A-Za-z0-9_-]*$/, 'PI_USER');
requireSafe(webRoot, /^\/srv\/tplanner-web(?:\/[A-Za-z0-9._-]+)*$/, 'PI_WEB_ROOT');

function run(command, args, { capture = false } = {}) {
    console.log(`> ${command} ${args.join(' ')}`);
    return execFileSync(command, args, {
        cwd: rootDir,
        stdio: capture ? ['ignore', 'pipe', 'inherit'] : 'inherit',
        encoding: capture ? 'utf8' : undefined,
    });
}

const gitSha = String(run('git', ['rev-parse', '--short=12', 'HEAD'], { capture: true })).trim();
const stamp = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
const releaseId = process.env.WEB_RELEASE_ID || `${stamp}-${gitSha}`;
requireSafe(releaseId, /^[A-Za-z0-9._-]+$/, 'WEB_RELEASE_ID');

const remoteTemp = `/tmp/tplanner-web-deploy-${releaseId}`;
const remoteRelease = `${webRoot}/releases/${releaseId}`;
const caddyTemplate = 'scripts/deploy/tplanner-web.Caddyfile';
if (!existsSync(join(rootDir, caddyTemplate))) throw new Error(`Missing ${caddyTemplate}`);

console.log('=== Build Web bundle ===');
const npx = process.platform === 'win32' ? 'npx.cmd' : 'npx';
run(npx, ['vite', 'build']);
if (!existsSync(join(rootDir, 'dist', 'index.html'))) throw new Error('dist/index.html was not built');

console.log('=== Upload release ===');
run('ssh', [sshTarget, `set -eu; mkdir -p '${remoteTemp}/dist'`]);
run('scp', ['-r', 'dist/.', `${sshTarget}:${remoteTemp}/dist/`]);
run('scp', [caddyTemplate, `${sshTarget}:${remoteTemp}/tplanner-web.Caddyfile`]);

const installScript = `
set -eu
WEB_ROOT='${webRoot}'
RELEASE='${remoteRelease}'
TEMP='${remoteTemp}'
BACKUP="/etc/caddy/Caddyfile.tplanner-web.$(date +%Y%m%d%H%M%S).bak"
PREVIOUS="$(readlink -f "$WEB_ROOT/current" 2>/dev/null || true)"

sudo install -d -m 0755 "$WEB_ROOT/releases"
if sudo test -e "$RELEASE"; then
  echo "Release already exists: $RELEASE" >&2
  exit 1
fi
sudo install -d -m 0755 "$RELEASE"
sudo cp -a "$TEMP/dist/." "$RELEASE/"
sudo chown -R root:root "$RELEASE"

AUTH_HASH="$(sudo awk '$1 == "hamhuo" && $2 ~ /^\\$2/ { print $2; exit }' /etc/caddy/Caddyfile)"
if [ -z "$AUTH_HASH" ]; then
  echo 'Could not preserve the existing Caddy Basic Auth hash' >&2
  exit 1
fi
sed "s|__TPLANNER_BASIC_HASH__|$AUTH_HASH|g" "$TEMP/tplanner-web.Caddyfile" > "$TEMP/Caddyfile"
sudo cp /etc/caddy/Caddyfile "$BACKUP"
sudo install -m 0644 "$TEMP/Caddyfile" /etc/caddy/Caddyfile
sudo caddy validate --config /etc/caddy/Caddyfile

sudo ln -sfn "$RELEASE" "$WEB_ROOT/.current-$RELEASE_ID"
sudo mv -Tf "$WEB_ROOT/.current-$RELEASE_ID" "$WEB_ROOT/current"
if ! sudo systemctl reload caddy; then
  sudo cp "$BACKUP" /etc/caddy/Caddyfile
  [ -n "$PREVIOUS" ] && sudo ln -sfn "$PREVIOUS" "$WEB_ROOT/current"
  sudo systemctl reload caddy || true
  exit 1
fi

if ! curl -fsS --max-time 10 -H 'Host: plan.hamhuo.top' http://127.0.0.1:37400/ >/dev/null; then
  sudo cp "$BACKUP" /etc/caddy/Caddyfile
  [ -n "$PREVIOUS" ] && sudo ln -sfn "$PREVIOUS" "$WEB_ROOT/current"
  sudo systemctl reload caddy || true
  exit 1
fi
rm -rf "$TEMP"
echo "previous=$PREVIOUS"
echo "release=$RELEASE"
`;

execFileSync('ssh', [sshTarget, `RELEASE_ID='${releaseId}' sh -s`], {
    cwd: rootDir,
    stdio: ['pipe', 'inherit', 'inherit'],
    input: installScript,
});

console.log('=== Verify public site ===');
run('curl', ['-fsS', '--max-time', '20', 'https://plan.hamhuo.top/'], { capture: true });
console.log(`Deployed ${releaseId} to https://plan.hamhuo.top`);
