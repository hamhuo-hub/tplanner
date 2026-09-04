// Tag-driven release helper (npm run release -- <patch|minor|major|x.y.z>).
// Computes the next version from existing v* tags, validates, then creates an
// annotated tag. The build derives its version from the tag via
// scripts/version.mjs (package.json is never edited), so no files are edited
// here.
//
//   npm run release -- patch
//   npm run release -- 4.3.0
import { execFileSync } from 'node:child_process';

function git(args) {
  try {
    return execFileSync('git', args, { encoding: 'utf8' }).trim();
  } catch {
    return '';
  }
}

function fail(msg) {
  console.error(`error: ${msg}`);
  process.exit(1);
}

const arg = process.argv[2] ?? 'patch';

// 1. Working tree must be clean, otherwise the tag would not include pending changes.
const status = git(['status', '--porcelain']);
if (status) {
  fail(`working tree is dirty; commit or discard changes first:\n${status}`);
}

// 2. Current version = nearest release tag (v* plus legacy desktop_*).
const describe = git(['describe', '--tags', '--match', 'v*', '--match', 'desktop_*', '--always']);
const m = /^(?:[A-Za-z]+[_-])?v?(\d+)\.(\d+)\.(\d+)/.exec(describe);
const cur = m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;

// 3. Compute the next version.
let next;
if (/^\d+\.\d+\.\d+$/.test(arg)) {
  next = arg.split('.').map(Number);
} else if (cur) {
  next = [...cur];
  if (arg === 'patch') next[2] += 1;
  else if (arg === 'minor') { next[1] += 1; next[2] = 0; }
  else if (arg === 'major') { next[0] += 1; next[1] = 0; next[2] = 0; }
  else fail('usage: npm run release -- patch | minor | major | x.y.z');
} else {
  fail('no existing release tag to bump from; pass an explicit version: npm run release -- 4.2.1');
}

const pad = (p) => p.map((n) => String(n).padStart(4, '0')).join('');
if (cur && pad(next) <= pad(cur)) {
  fail(`next version ${next.join('.')} is not higher than current ${cur.join('.')}`);
}

const tag = `v${next.join('.')}`;
if (git(['tag', '-l', tag])) fail(`tag ${tag} already exists`);

// 4. Confirm the current branch.
const branch = git(['branch', '--show-current']);
if (branch !== 'master') {
  console.warn(`warning: current branch is ${branch} (releases are usually tagged on master)`);
}

// 5. Create an annotated tag (git describe prefers annotated tags).
execFileSync('git', ['tag', '-a', tag, '-m', `Release ${tag}`], { stdio: 'inherit' });
console.log(`tag created: ${tag}; builds will derive version ${next.join('.')} from this tag`);
console.log(`push it with: git push origin ${tag}`);
