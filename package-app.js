import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';

console.log('Building frontend...');
// Run Vite build
try {
    execSync('npm run build', { stdio: 'inherit' });
} catch (e) {
    console.error('Frontend build failed.');
    process.exit(1);
}

console.log('Packaging application...');
// Run pkg
// We use npx to run pkg from local modules
try {
    execSync('npx pkg . --target node18-win-x64 --output tplanner-win.exe', { stdio: 'inherit' });
    console.log('Packaging complete: tplanner-win.exe');
} catch (e) {
    console.error('Packaging failed.', e);
    process.exit(1);
}
