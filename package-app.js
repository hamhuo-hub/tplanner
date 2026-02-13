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

console.log('Generating icon...');
try {
    const scriptsDir = path.join(__dirname, 'scripts');
    if (fs.existsSync(path.join(scriptsDir, 'convert_icon.js'))) {
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
    // New Strategy: Use launcher.vbs to run node hidden.
    // Cmd: "wscript" "{{caxa}}/launcher.vbs" "{{caxa}}/node.exe" "{{caxa}}/server.cjs" "--packaged"
    // IMPORTANT: We are now explicitly bundling node.exe into the package!

    // Note: The double quotes around arguments are critical for paths with spaces.
    // {{caxa}} is replaced by the temp directory.
    const caxaCmd = 'npx --yes caxa --input staging --output tplanner-win.exe -- "wscript" "{{caxa}}/launcher.vbs" "{{caxa}}/node.exe" "{{caxa}}/server.cjs" "--packaged"';
    execSync(caxaCmd, { stdio: 'inherit' });

    console.log('Packaging complete: tplanner-win.exe');

    // Post-processing: Set icon for the executable
    // caxa doesn't support setting the icon for the stub natively in all versions/configurations easily without external tools.
    // We use rcedit to set the icon.
    console.log('Setting executable icon...');
    try {
        // Ensure icon.ico exists
        if (fs.existsSync('icon.ico')) {
            execSync('npx --yes rcedit "tplanner-win.exe" --set-icon "icon.ico"', { stdio: 'inherit' });
            console.log('Icon applied to tplanner-win.exe');
        } else {
            console.warn('icon.ico not found, skipping icon application.');
        }
    } catch (e) {
        console.warn('Failed to set executable icon with rcedit:', e.message);
        console.warn('The executable works but might not have the correct icon.');
    }

} catch (e) {
    console.error('Packaging failed.', e);
    process.exit(1);
}

// Optional: cleanup staging
console.log('Cleaning up...');
// fs.rmSync(STAGING_DIR, { recursive: true, force: true });
console.log('Done!');
