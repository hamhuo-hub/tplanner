#!/usr/bin/env node
/** Vendor immutable Git blobs; runtime consumers only read this worktree's package. */
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const packageRoot = path.join(projectRoot, 'design-assets', 'tokens');
const manifestPath = path.join(packageRoot, 'provenance.json');
const files = [
  '.gitattributes',
  'tplanner-light.tokens.json',
  'generated/tplanner-light.css',
  'generated/tplanner-light.ts',
  'generated/tplanner-light.mjs',
  'generated/contrast-report.json',
  'adapters/mui-light.ts',
];
const args = process.argv.slice(2);
let check = false;
let sourceRoot;
let sourceRef;
for (let index = 0; index < args.length; index += 1) {
  const arg = args[index];
  if (arg === '--check') check = true;
  else if (arg === '--source-root' || arg === '--source-ref') {
    const value = args[++index];
    if (!value || value.startsWith('--')) throw new Error(`Missing value for ${arg}`);
    if (arg === '--source-root') sourceRoot = path.resolve(value);
    else sourceRef = value;
  } else throw new Error(`Unknown argument: ${arg}`);
}
if (!check && !sourceRoot) {
  throw new Error('Sync requires --source-root <canonical repository> (optionally --source-ref <commit>).');
}
if (sourceRef && !sourceRoot) throw new Error('--source-ref requires --source-root.');
// Git may check out text as CRLF on Windows. Hash canonical LF content so
// line-ending conversion is not reported as an independently edited token.
const sha256 = bytes => createHash('sha256').update(bytes.toString('utf8').replace(/\r\n/g, '\n')).digest('hex');
const readManifest = async () => JSON.parse(await readFile(manifestPath, 'utf8'));
const manifest = check ? await readManifest() : null;
const git = (...gitArgs) => execFileSync('git', ['-C', sourceRoot, ...gitArgs], { maxBuffer: 8 * 1024 * 1024 });
const ref = sourceRef || manifest?.sourceCommit || '111b90d';
const commit = sourceRoot ? git('rev-parse', '--verify', `${ref}^{commit}`).toString().trim() : manifest.sourceCommit;
if (check && commit !== manifest.sourceCommit) throw new Error('Requested commit differs from the vendored provenance. Run sync explicitly to update.');
const hashes = {};
for (const file of files) {
  const localPath = path.join(packageRoot, file);
  const canonical = sourceRoot ? git('show', `${commit}:design-assets/tokens/${file}`) : null;
  if (check) {
    const actual = sha256(await readFile(localPath));
    if (actual !== manifest.files[file] || (canonical && actual !== sha256(canonical))) {
      throw new Error(`Token package drift: ${file}. Do not edit vendored files; synchronize the canonical package.`);
    }
  } else {
    await mkdir(path.dirname(localPath), { recursive: true });
    await writeFile(localPath, canonical);
    hashes[file] = sha256(canonical);
  }
}
if (!check) {
  await writeFile(manifestPath, JSON.stringify({
    schemaVersion: 1,
    sourceCommit: commit,
    sourceDirectory: 'design-assets/tokens',
    sourceDescription: 'Canonical TPlanner cross-platform light token package; synchronize from an explicitly supplied repository.',
    hashAlgorithm: 'SHA-256',
    hashNormalization: 'UTF-8 text with LF line endings',
    files: hashes,
  }, null, 2) + '\n');
}
console.log(`${check ? 'Verified' : 'Synchronized'} ${files.length} token files from ${commit.slice(0, 12)}.`);
