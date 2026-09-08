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
import { closeSync, existsSync, openSync, readFileSync, readSync } from 'node:fs';
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

const builderArgs = [cliPath, 'build', '--win', '--config', `--config.extraMetadata.version=${version}`];

// Reuse npm's installed, already-extracted Windows runtime. Recent builder
// versions extract the same archive again and rename win-unpacked.tmp once;
// that rename fails with EPERM on this Windows host. electronDist uses the
// supported copy path instead. Cross-platform builds retain normal downloads.
if (process.platform === 'win32') {
    const electronDir = join(rootDir, 'node_modules', 'electron');
    const electronDist = join(electronDir, 'dist');
    const installedVersion = JSON.parse(readFileSync(join(electronDir, 'package.json'), 'utf8')).version;
    const runtimeVersion = readFileSync(join(electronDist, 'version'), 'utf8').trim();
    if (runtimeVersion !== installedVersion) {
        throw new Error('Installed Electron runtime version does not match its package; run npm ci');
    }

    // Both Windows targets in package.json are x64; reject a cross-installed
    // runtime rather than silently packaging an ARM64 or 32-bit executable.
    const executable = openSync(join(electronDist, 'electron.exe'), 'r');
    try {
        const dos = Buffer.alloc(64);
        const pe = Buffer.alloc(6);
        if (readSync(executable, dos, 0, dos.length, 0) !== dos.length
            || dos.readUInt16LE(0) !== 0x5a4d
            || readSync(executable, pe, 0, pe.length, dos.readUInt32LE(0x3c)) !== pe.length
            || pe.readUInt32LE(0) !== 0x00004550
            || pe.readUInt16LE(4) !== 0x8664) {
            throw new Error('Windows packaging requires an installed x64 Electron runtime; reinstall Electron for x64');
        }
    } finally {
        closeSync(executable);
    }
    builderArgs.push('--x64', `--config.electronDist=${electronDist}`);
    console.log(`using installed Electron ${runtimeVersion} (win32-x64)`);
}

execFileSync(
    process.execPath,
    builderArgs,
    { cwd: rootDir, stdio: 'inherit' },
);
