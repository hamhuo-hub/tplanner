// Windows packaging entry (npm run package).
//
// Builds the renderer, then hands the tag-derived version to electron-builder
// through --config.extraMetadata.version — package.json is never modified, so
// a build never dirties the worktree.
import { execFileSync } from 'node:child_process';
import { computeVersion } from './version.mjs';

const version = computeVersion();
if (!/^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?$/.test(version)) {
    throw new Error(`computed version looks unsafe: ${version}`);
}
console.log(`packaging tPlanner ${version} (windows: nsis + portable)`);

const npx = process.platform === 'win32' ? 'npx.cmd' : 'npx';
execFileSync(
    npx,
    ['electron-builder', 'build', '--win', '--config', `--config.extraMetadata.version=${version}`],
    { stdio: 'inherit' },
);
