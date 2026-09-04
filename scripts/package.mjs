// Windows packaging entry (npm run package).
//
// Builds the renderer, then hands the tag-derived version to electron-builder
// through --config.extraMetadata.version — package.json is never modified, so
// a build never dirties the worktree.
//
// The local electron-builder CLI is spawned directly with node (never through
// npx/.cmd): spawning .cmd shims without a shell throws EINVAL on modern Node
// (CVE-2024-27980 hardening).
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { computeVersion } from './version.mjs';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const rootDir = join(scriptDir, '..');

const version = computeVersion();
if (!/^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?$/.test(version)) {
    throw new Error(`computed version looks unsafe: ${version}`);
}
console.log(`packaging tPlanner ${version} (windows: nsis + portable)`);

const ebPkgPath = join(rootDir, 'node_modules', 'electron-builder', 'package.json');
if (!existsSync(ebPkgPath)) throw new Error('electron-builder is not installed; run npm ci first');
const binRel = JSON.parse(readFileSync(ebPkgPath, 'utf8')).bin['electron-builder'];
const cliPath = join(dirname(ebPkgPath), binRel);
if (!existsSync(cliPath)) throw new Error(`electron-builder CLI not found: ${cliPath}`);

execFileSync(
    process.execPath,
    [cliPath, 'build', '--win', '--config', `--config.extraMetadata.version=${version}`],
    { stdio: 'inherit' },
);
