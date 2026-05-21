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



console.log('Checking for icon...');
try {
    const scriptsDir = path.join(__dirname, 'scripts');
    if (fs.existsSync('icon.ico')) {
        console.log('Using existing icon.ico');
    } else if (fs.existsSync(path.join(scriptsDir, 'convert_icon.js'))) {
        console.log('Generating icon...');
        execSync('node scripts/convert_icon.js', { stdio: 'inherit' });
    } else {
        console.warn('scripts/convert_icon.js not found. Skipping icon generation.');
    }
} catch (e) {
    console.warn('Icon generation failed. Proceeding with existing icon if any.');
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
// Copy node executable to staging
console.log('Copying node executable...');
try {
    const nodeExePath = process.execPath;
    const destNodePath = path.join(STAGING_DIR, 'node.exe');
    console.log(`Copying ${nodeExePath} to ${destNodePath}`);
    fs.copyFileSync(nodeExePath, destNodePath);
} catch (e) {
    console.error('Failed to copy node executable: ', e);
    process.exit(1);
}
fs.copyFileSync(path.join(__dirname, 'package.json'), path.join(STAGING_DIR, 'package.json'));
fs.copyFileSync(path.join(__dirname, 'launcher.vbs'), path.join(STAGING_DIR, 'launcher.vbs'));
if (fs.existsSync(path.join(__dirname, 'landscape.png'))) {
    fs.copyFileSync(path.join(__dirname, 'landscape.png'), path.join(STAGING_DIR, 'landscape.png'));
}
if (fs.existsSync(path.join(__dirname, 'icon.ico'))) {
    fs.copyFileSync(path.join(__dirname, 'icon.ico'), path.join(STAGING_DIR, 'icon.ico'));
}

if (fs.existsSync(path.join(__dirname, 'package-lock.json'))) {
    fs.copyFileSync(path.join(__dirname, 'package-lock.json'), path.join(STAGING_DIR, 'package-lock.json'));
}

import { createRequire } from 'module';
const require = createRequire(import.meta.url);
const { rcedit } = require('rcedit');

console.log('Installing production dependencies in staging...');
try {
    execSync('npm install --omit=dev --no-bin-links', { cwd: STAGING_DIR, stdio: 'inherit' });
} catch (e) {
    console.error('Failed to install dependencies in staging.');
    process.exit(1);
}

console.log('Applying icon to staging node.exe...');
const stagingNodeExe = path.join(STAGING_DIR, 'node.exe');
if (fs.existsSync(stagingNodeExe) && fs.existsSync(path.join(STAGING_DIR, 'icon.ico'))) {
    try {
        await rcedit(stagingNodeExe, {
            icon: path.join(STAGING_DIR, 'icon.ico')
        });
        console.log('Icon successfully applied to node.exe in staging.');
    } catch (e) {
        console.error('Failed to apply icon via rcedit. Packaged app will have default icon.', e);
    }
} else {
    console.warn('node.exe or icon.ico missing in staging. Skipping rcedit.');
}

console.log('Packaging application with caxa...');
try {
    // New Strategy: Use launcher.vbs to run node hidden.
    // Cmd: "wscript" "{{caxa}}/launcher.vbs" "{{caxa}}/node.exe" "{{caxa}}/server.cjs" "--packaged"
    // IMPORTANT: We are now explicitly bundling node.exe into the package!

    // Note: The double quotes around arguments are critical for paths with spaces.
    // {{caxa}} is replaced by the temp directory.
    console.log('Packaging application...');
    const caxaCmd = `npx --yes caxa --input staging --output tplanner-win.exe --no-include-node -- "wscript" "{{caxa}}/launcher.vbs" "{{caxa}}/node.exe" "{{caxa}}/server.cjs" "--packaged"`;
    execSync(caxaCmd, { stdio: 'inherit' });

    console.log('Packaging complete: tplanner-win.exe');

} catch (e) {
    console.error('Packaging failed.', e);
    process.exit(1);
}

// Optional: cleanup staging
console.log('Cleaning up...');
// fs.rmSync(STAGING_DIR, { recursive: true, force: true });
console.log('Done!');
