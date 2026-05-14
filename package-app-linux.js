import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const STAGING_DIR = path.join(__dirname, 'staging-linux');

const env = {
    ...process.env,
    PATH: `${path.join(__dirname, 'node_modules', '.bin')}:${process.env.PATH || ''}`,
};

console.log('Building frontend...');
try {
    execSync('npm run build', { stdio: 'inherit', env });
} catch (e) {
    console.error('Frontend build failed.');
    process.exit(1);
}

console.log('Preparing staging environment...');
if (fs.existsSync(STAGING_DIR)) {
    fs.rmSync(STAGING_DIR, { recursive: true, force: true });
}
fs.mkdirSync(STAGING_DIR);

console.log('Copying files...');
fs.cpSync(path.join(__dirname, 'dist'), path.join(STAGING_DIR, 'dist'), { recursive: true });
fs.copyFileSync(path.join(__dirname, 'server.cjs'), path.join(STAGING_DIR, 'server.cjs'));
fs.copyFileSync(path.join(__dirname, 'package.json'), path.join(STAGING_DIR, 'package.json'));

if (fs.existsSync(path.join(__dirname, 'landscape.png'))) {
    fs.copyFileSync(path.join(__dirname, 'landscape.png'), path.join(STAGING_DIR, 'landscape.png'));
}
if (fs.existsSync(path.join(__dirname, 'package-lock.json'))) {
    fs.copyFileSync(path.join(__dirname, 'package-lock.json'), path.join(STAGING_DIR, 'package-lock.json'));
}

if (fs.existsSync(path.join(__dirname, 'themes'))) {
    fs.cpSync(path.join(__dirname, 'themes'), path.join(STAGING_DIR, 'themes'), { recursive: true });
}

console.log('Copying node executable...');
try {
    const nodeExePath = process.execPath;
    const destNodePath = path.join(STAGING_DIR, 'node');
    console.log(`Copying ${nodeExePath} to ${destNodePath}`);
    fs.copyFileSync(nodeExePath, destNodePath);
    fs.chmodSync(destNodePath, 0o755);
} catch (e) {
    console.error('Failed to copy node executable:', e);
    process.exit(1);
}

console.log('Installing production dependencies in staging...');
try {
    execSync('npm install --omit=dev --no-bin-links', { cwd: STAGING_DIR, stdio: 'inherit', env });
} catch (e) {
    console.error('Failed to install dependencies in staging.');
    process.exit(1);
}

console.log('Packaging application with caxa...');
try {
    const caxaCmd = `npx --yes caxa --input staging-linux --output tplanner-linux --no-include-node -- "{{caxa}}/node" "{{caxa}}/server.cjs" "--packaged"`;
    execSync(caxaCmd, { stdio: 'inherit', env });
    fs.chmodSync(path.join(__dirname, 'tplanner-linux'), 0o755);
    console.log('Packaging complete: tplanner-linux');
} catch (e) {
    console.error('Packaging failed.', e);
    process.exit(1);
}

console.log('Cleaning up...');
// fs.rmSync(STAGING_DIR, { recursive: true, force: true });
console.log('Done!');
