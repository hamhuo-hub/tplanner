import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Root dir is one level up from scripts/
const rootDir = path.join(__dirname, '..');
const landscapePath = path.join(rootDir, 'landscape.png');
const iconPath = path.join(rootDir, 'icon.ico');

console.log('Generating icon...');

// Simple python script content
const pythonScriptContent = `
from PIL import Image
import sys
import os

try:
    img_path = r"${landscapePath.replace(/\\/g, '\\\\')}"
    icon_dest = r"${iconPath.replace(/\\/g, '\\\\')}"
    
    if not os.path.exists(img_path):
        print(f"Error: {img_path} not found")
        sys.exit(1)
        
    img = Image.open(img_path)
    # Save as .ico with multiple sizes
    img.save(icon_dest, format="ICO", sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
    print(f"Icon created at {icon_dest}")
except Exception as e:
    print(f"Error: {e}")
    sys.exit(1)
`;

const tempScriptPath = path.join(__dirname, 'temp_convert.py');

try {
    fs.writeFileSync(tempScriptPath, pythonScriptContent);
    execSync(`python "${tempScriptPath}"`, { stdio: 'inherit' });
} catch (e) {
    console.error('Failed to generate icon:', e);
    process.exit(1);
} finally {
    if (fs.existsSync(tempScriptPath)) {
        fs.unlinkSync(tempScriptPath);
    }
}
