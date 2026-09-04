// Derive the app version from git tags. The git tag is the single source of
// truth; this module COMPUTES the version and never modifies any source file
// (package.json keeps a static placeholder and stays clean in git).
//
//   HEAD exactly on a v* tag  -> "4.2.0"
//   HEAD after a v* tag       -> "4.2.0-dev" (+ "-dirty" when the tree is dirty)
//   no matching tag / no git  -> "4.0.0-dev" (fallback)
//
// Usage:
//   node scripts/version.mjs            prints the version to stdout
//   import { computeVersion } from ...  returns the version string
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
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

export function computeVersion() {
    const exact = parseSemver(git(['describe', '--tags', '--match', 'v*', '--exact-match']));
    const nearest = parseSemver(git(['describe', '--tags', '--match', 'v*', '--always']));
    const dirty = git(['status', '--porcelain']) !== '';

    const parts = exact ?? nearest ?? (() => {
        const [major, minor, patch] = FALLBACK.split('.').map(Number);
        return { major, minor, patch };
    })();

    return exact
        ? `${parts.major}.${parts.minor}.${parts.patch}`
        : `${parts.major}.${parts.minor}.${parts.patch}-dev${dirty ? '-dirty' : ''}`;
}

const isDirectRun = process.argv[1]
    && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isDirectRun) {
    console.log(computeVersion());
}
