import express from 'express';
import cors from 'cors';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = 3001;
const DATA_FILE = path.join(__dirname, 'data.json');

app.use(cors());
app.use(express.json());

// Initialize data file if not exists
if (!fs.existsSync(DATA_FILE)) {
    fs.writeFileSync(DATA_FILE, JSON.stringify([], null, 2));
}

// GET events
app.get('/api/events', (req, res) => {
    fs.readFile(DATA_FILE, 'utf8', (err, data) => {
        if (err) {
            console.error(err);
            return res.status(500).json({ error: 'Failed to read data' });
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
    console.log(`Server running on http://localhost:${PORT}`);
});
