// Derive the app version from git tags at build time.
// The git tag is the single source of truth; this script stamps package.json
// (electron-builder and the app read the version from there) before each build.
//
//   HEAD exactly on a v* tag  -> "4.2.0"
//   HEAD after a v* tag       -> "4.2.0-dev" (+ "-dirty" when the tree is dirty)
//   no matching tag / no git  -> "4.0.0-dev" (fallback)
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const FALLBACK = '4.0.0';

function git(args) {
  try {
    return execFileSync('git', args, { encoding: 'utf8' }).trim();
  } catch {
    return '';
  }
}

const semverRe = /^(?:[A-Za-z]+[_-])?v?(\d+)\.(\d+)\.(\d+)/;

function parseSemver(text) {
  if (!text) return null;
  const m = semverRe.exec(text.trim());
  return m ? { major: Number(m[1]), minor: Number(m[2]), patch: Number(m[3]) } : null;
}

const exact = parseSemver(git(['describe', '--tags', '--match', 'v*', '--exact-match']));
const nearest = parseSemver(git(['describe', '--tags', '--match', 'v*', '--always']));
const dirty = git(['status', '--porcelain']) !== '';

const parts = exact ?? nearest ?? (() => {
  const [major, minor, patch] = FALLBACK.split('.').map(Number);
  return { major, minor, patch };
})();

const version = exact
  ? `${parts.major}.${parts.minor}.${parts.patch}`
  : `${parts.major}.${parts.minor}.${parts.patch}-dev${dirty ? '-dirty' : ''}`;

const pkgPath = fileURLToPath(new URL('../package.json', import.meta.url));
const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'));
if (pkg.version === version) {
  console.log(`version: ${version} (already stamped)`);
} else {
  const old = pkg.version;
  pkg.version = version;
  writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n');
  console.log(`version: ${old} -> ${version}`);
}
