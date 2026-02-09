const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const { exec } = require('child_process');

const app = express();
const PORT = 3001;

// Determine if running inside pkg (executable) or dev (node)
const isPkg = typeof process.pkg !== 'undefined';
// For data.json: use process.cwd() or path.dirname(process.execPath) if in pkg
const runDir = isPkg ? path.dirname(process.execPath) : __dirname;
const DATA_FILE = path.join(runDir, 'data.json');

app.use(cors());
app.use(express.json());

// Middleware to prevent caching
app.use((req, res, next) => {
    res.set('Cache-Control', 'no-store, no-cache, must-revalidate, private');
    res.set('Pragma', 'no-cache');
    res.set('Expires', '0');
    next();
});

// Serve static files from 'dist' directory
// In dev, we might not have dist or we run vite separately.
// This is mainly for the packaged app or production preview.
const distPath = path.join(__dirname, 'dist');
if (fs.existsSync(distPath)) {
    app.use(express.static(distPath));
}

// Initialize data file if not exists
if (!fs.existsSync(DATA_FILE)) {
    try {
        fs.writeFileSync(DATA_FILE, JSON.stringify([], null, 2));
    } catch (err) {
        console.error('Failed to create data.json:', err);
    }
}

// GET events
app.get('/api/events', (req, res) => {
    fs.readFile(DATA_FILE, 'utf8', (err, data) => {
        if (err) {
            console.error(err);
            // If file doesn't exist or error, return empty array
            return res.json([]);
        }
        try {
            const events = JSON.parse(data);
            res.json(events);
        } catch (e) {
            res.json([]);
        }
    });
});

// POST events (save all)
app.post('/api/events', (req, res) => {
    const events = req.body;
    fs.writeFile(DATA_FILE, JSON.stringify(events, null, 2), (err) => {
        if (err) {
            console.error(err);
            return res.status(500).json({ error: 'Failed to save data' });
        }
        res.json({ success: true });
    });
});

app.listen(PORT, () => {
    const url = `http://localhost:${PORT}`;
    console.log(`Server running on ${url}`);

    // Auto-open browser if in production/pkg
    if (isPkg) {
        const startCmd = process.platform === 'win32' ? 'start' : 'open';
        exec(`${startCmd} ${url}`);
    }

    // Keep alive debug
    setInterval(() => {
        // console.log('Server heartbeat');
    }, 10000);
});

process.on('exit', (code) => {
    console.log(`About to exit with code: ${code}`);
});

process.on('SIGINT', () => {
    console.log('Received SIGINT. Press Control-D to exit.');
    process.exit();
});
