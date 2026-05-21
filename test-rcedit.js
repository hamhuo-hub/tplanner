
import { createRequire } from 'module';
const require = createRequire(import.meta.url);
const rcedit = require('rcedit');

async function run() {
    try {
        console.log('Attempting to set icon...');
        await rcedit('tplanner-win.exe', {
            icon: 'icon.ico'
        });
        console.log('Icon set successfully!');
    } catch (error) {
        console.error('Error setting icon:', error);
    }
}

run();
