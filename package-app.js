import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const STAGING_DIR = path.join(__dirname, 'staging');

console.log('Building frontend...');
// Run Vite build
try {
    execSync('npm run build', { stdio: 'inherit' });
} catch (e) {
    console.error('Frontend build failed.');
    process.exit(1);
}

console.log('Preparing staging environment...');
if (fs.existsSync(STAGING_DIR)) {
    fs.rmSync(STAGING_DIR, { recursive: true, force: true });
}
fs.mkdirSync(STAGING_DIR);

// Copy necessary files
console.log('Copying files...');
fs.cpSync(path.join(__dirname, 'dist'), path.join(STAGING_DIR, 'dist'), { recursive: true });
fs.copyFileSync(path.join(__dirname, 'server.cjs'), path.join(STAGING_DIR, 'server.cjs'));
fs.copyFileSync(path.join(__dirname, 'package.json'), path.join(STAGING_DIR, 'package.json'));

if (fs.existsSync(path.join(__dirname, 'package-lock.json'))) {
    fs.copyFileSync(path.join(__dirname, 'package-lock.json'), path.join(STAGING_DIR, 'package-lock.json'));
}

console.log('Installing production dependencies in staging...');
try {
    execSync('npm install --omit=dev --no-bin-links', { cwd: STAGING_DIR, stdio: 'inherit' });
} catch (e) {
    console.error('Failed to install dependencies in staging.');
    process.exit(1);
}

console.log('Packaging application with caxa...');
try {
    // We use npx to run caxa. 
    // caxa --input <dir> --output <exe> -- "{{caxa}}/node" <script>
    // Note: we use "{{caxa}}/node" to find the node binary bundled with caxa.

    // Use cmd /c to keep window open on error (for debugging) and ensure we use bundled server
    execSync('npx --yes caxa --input staging --output tplanner-win.exe -- "cmd" "/c" "node \"{{caxa}}/server.cjs\" --packaged || pause"', { stdio: 'inherit' });

    console.log('Packaging complete: tplanner-win.exe');
} catch (e) {
    console.error('Packaging failed.', e);
    process.exit(1);
}

// Optional: cleanup staging
console.log('Cleaning up...');
// fs.rmSync(STAGING_DIR, { recursive: true, force: true });
console.log('Done!');
