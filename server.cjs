const express = require('express');
const cors    = require('cors');
const fs      = require('fs');
const path    = require('path');
const os      = require('os');
const multer  = require('multer');
const { exec } = require('child_process');

const app  = express();
const PORT = process.env.PORT || 3001;

// ── Path resolution ──────────────────────────────────────────────────────────
const isPackaged = process.argv.includes('--packaged');
const runDir     = process.cwd();
const DATA_FILE  = path.join(runDir, 'data.json');

// ── Multer (memory storage for JSON upload) ──────────────────────────────────
const upload = multer({
    storage: multer.memoryStorage(),
    limits: { fileSize: 50 * 1024 * 1024 }, // 50 MB max
    fileFilter: (_req, file, cb) => {
        if (file.mimetype === 'application/json' || file.originalname.endsWith('.json')) {
            cb(null, true);
        } else {
            cb(new Error('Only JSON files are allowed'));
        }
    },
});

// ── Middleware ───────────────────────────────────────────────────────────────
app.use(cors());
app.use(express.json({ limit: '50mb' }));
app.use((req, res, next) => {
    res.set('Cache-Control', 'no-store, no-cache, must-revalidate, private');
    res.set('Pragma', 'no-cache');
    res.set('Expires', '0');
    next();
});

// ── Static: main app (dist) ──────────────────────────────────────────────────
const distPath = path.join(__dirname, 'dist');
if (fs.existsSync(distPath)) {
    app.use(express.static(distPath));
}

// ── Initialize data file ─────────────────────────────────────────────────────
if (!fs.existsSync(DATA_FILE)) {
    try { fs.writeFileSync(DATA_FILE, JSON.stringify([], null, 2)); }
    catch (err) { console.error('Failed to create data.json:', err); }
}

// ── Helper: get LAN IP ───────────────────────────────────────────────────────
function getLanIp() {
    const interfaces = os.networkInterfaces();
    for (const name of Object.keys(interfaces)) {
        for (const iface of interfaces[name]) {
            if (iface.family === 'IPv4' && !iface.internal) {
                return iface.address;
            }
        }
    }
    return '127.0.0.1';
}

// ═══════════════════════════════════════════════════════════════════════════
//  API ROUTES
// ═══════════════════════════════════════════════════════════════════════════

// GET /api/sync-info — returns LAN IP + port for the TV overlay
app.get('/api/sync-info', (_req, res) => {
    res.json({ ip: getLanIp(), port: PORT });
});

// GET /api/events — read all events
app.get('/api/events', (_req, res) => {
    fs.readFile(DATA_FILE, 'utf8', (err, data) => {
        if (err) { console.error(err); return res.json([]); }
        try   { res.json(JSON.parse(data)); }
        catch { res.json([]); }
    });
});

// POST /api/events — overwrite all events (from TV pull or PC push)
app.post('/api/events', (req, res) => {
    const events = req.body;
    if (!Array.isArray(events)) return res.status(400).json({ error: 'Body must be a JSON array' });
    fs.writeFile(DATA_FILE, JSON.stringify(events, null, 2), (err) => {
        if (err) { console.error(err); return res.status(500).json({ error: 'Failed to save data' }); }
        console.log(`[sync] Saved ${events.length} events at ${new Date().toISOString()}`);
        res.json({ success: true, count: events.length, savedAt: new Date().toISOString() });
    });
});

// POST /api/upload — multipart JSON file upload from the /sync page
app.post('/api/upload', upload.single('file'), (req, res) => {
    if (!req.file) return res.status(400).json({ error: 'No file uploaded' });
    try {
        const text   = req.file.buffer.toString('utf8');
        const events = JSON.parse(text);
        if (!Array.isArray(events)) throw new Error('JSON must be an array');

        // Normalize dates
        const normalized = events.map(ev => ({
            ...ev,
            start:          new Date(ev.start).toISOString(),
            end:            new Date(ev.end).toISOString(),
            updatedAt:      Date.now(),
            note:           ev.note           ?? '',
            timezone:       ev.timezone       ?? '',
            groupId:        ev.groupId        ?? '',
            completed:      ev.completed      ?? false,
            checklist:      ev.checklist      ?? [],
            recurrenceType: ev.recurrenceType ?? 'none',
            recurrenceCount: ev.recurrenceCount ?? 1,
        }));

        fs.writeFileSync(DATA_FILE, JSON.stringify(normalized, null, 2));
        console.log(`[upload] Received ${normalized.length} events at ${new Date().toISOString()}`);
        res.json({ success: true, count: normalized.length, savedAt: new Date().toISOString() });
    } catch (err) {
        console.error('[upload] Error:', err.message);
        res.status(400).json({ error: err.message });
    }
});

// GET /api/export — download current data.json as a file
app.get('/api/export', (_req, res) => {
    if (!fs.existsSync(DATA_FILE)) return res.json([]);
    res.setHeader('Content-Disposition', 'attachment; filename="tplanner-data.json"');
    res.setHeader('Content-Type', 'application/json');
    res.sendFile(DATA_FILE);
});

// ═══════════════════════════════════════════════════════════════════════════
//  SYNC PAGE  — served at /sync
//  A standalone HTML page that LAN devices can open to upload JSON data.
// ═══════════════════════════════════════════════════════════════════════════
app.get('/sync', (_req, res) => {
    res.setHeader('Content-Type', 'text/html; charset=utf-8');
    res.send(SYNC_PAGE_HTML);
});

// ── SPA fallback (main app) ──────────────────────────────────────────────────
app.get('/{*path}', (_req, res) => {
    const index = path.join(distPath, 'index.html');
    if (fs.existsSync(index)) {
        res.sendFile(index);
    } else {
        res.status(404).send('App not built yet. Run: npm run build');
    }
});

// ── Start ────────────────────────────────────────────────────────────────────
app.listen(PORT, '0.0.0.0', () => {
    const ip  = getLanIp();
    const url = `http://${ip}:${PORT}`;
    console.log(`\n╔═══════════════════════════════════════╗`);
    console.log(`║  tPlanner Server                      ║`);
    console.log(`║  Local  : http://localhost:${PORT}        ║`);
    console.log(`║  LAN    : ${url.padEnd(28)}║`);
    console.log(`║  Sync   : ${url}/sync${' '.repeat(23)}║`);
    console.log(`╚═══════════════════════════════════════╝\n`);

    if (isPackaged && process.env.HEADLESS !== 'true') {
        setupTray();
        const startCmd = process.platform === 'win32' ? 'start' : 'open';
        exec(`${startCmd} http://localhost:${PORT}`);
    }
});

// ── System Tray ──────────────────────────────────────────────────────────────
function setupTray() {
    try {
        const SysTray = require('systray2').default;
        let iconBase64 = '';
        try {
            const iconPath = path.join(__dirname, 'icon.ico');
            if (fs.existsSync(iconPath)) {
                iconBase64 = fs.readFileSync(iconPath).toString('base64');
            }
        } catch {}

        const tray = new SysTray({
            menu: {
                icon: iconBase64, title: 'tPlanner', tooltip: 'Planner',
                items: [
                    { title: 'Open tPlanner',   tooltip: 'Open in Browser', checked: false, enabled: true },
                    { title: 'Open Sync Page',  tooltip: 'Open /sync',      checked: false, enabled: true },
                    SysTray.separator,
                    { title: 'Exit',            tooltip: 'Stop Server',     checked: false, enabled: true },
                ],
            },
            debug: false, copyDir: true,
        });

        tray.onClick(action => {
            const startCmd = process.platform === 'win32' ? 'start' : 'open';
            if (action.item.title === 'Open tPlanner') {
                exec(`${startCmd} http://localhost:${PORT}`);
            } else if (action.item.title === 'Open Sync Page') {
                exec(`${startCmd} http://localhost:${PORT}/sync`);
            } else if (action.item.title === 'Exit') {
                tray.kill(false);
                process.exit(0);
            }
        });
    } catch (err) {
        console.log('systray2 not available (OK in headless/Linux):', err.message);
    }
}

process.on('SIGINT', () => process.exit());
process.on('exit', code => console.log(`Exit with code ${code}`));

// ═══════════════════════════════════════════════════════════════════════════
//  SYNC PAGE HTML (inline template)
// ═══════════════════════════════════════════════════════════════════════════
const SYNC_PAGE_HTML = `<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>tPlanner — LAN Sync</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Oswald:wght@400;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
  :root {
    --clr-void:    #060606;
    --clr-bg:      #111111;
    --clr-surface: #181818;
    --clr-raised:  #1E1E1E;
    --clr-border:  #272727;
    --clr-border-bright: #383838;
    --clr-gold:        #C9A84C;
    --clr-gold-bright: #F0C040;
    --clr-gold-dim:    #6B5928;
    --clr-gold-ghost:  rgba(201,168,76,0.08);
    --clr-red:       #C0392B;
    --clr-red-dim:   rgba(192,57,43,0.15);
    --clr-success:   #4A7C59;
    --clr-text:      #E0D8C8;
    --clr-text-dim:  #6B6355;
    --font-display: 'Oswald', sans-serif;
    --font-mono:    'IBM Plex Mono', monospace;
  }
  html, body {
    min-height: 100vh;
    background: var(--clr-bg);
    color: var(--clr-text);
    font-family: var(--font-mono);
    font-size: 14px;
    line-height: 1.6;
    -webkit-font-smoothing: antialiased;
  }
  body { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 24px; }

  .card {
    width: 100%;
    max-width: 560px;
    background: var(--clr-surface);
    border: 1px solid var(--clr-border-bright);
    border-top: 3px solid var(--clr-gold);
    border-radius: 3px;
    overflow: hidden;
    box-shadow: 0 24px 80px rgba(0,0,0,0.6);
  }
  .card-header {
    display: flex; align-items: center; gap: 12px;
    padding: 18px 22px;
    border-bottom: 1px solid var(--clr-border);
    background: var(--clr-void);
    position: relative;
  }
  .card-header::before {
    content: '';
    position: absolute;
    left: 0; top: 0; bottom: 0;
    width: 3px;
    background: var(--clr-red);
  }
  .card-title {
    font-family: var(--font-display);
    font-size: 20px;
    font-weight: 700;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--clr-gold);
  }
  .card-subtitle {
    font-size: 11px;
    color: var(--clr-text-dim);
    letter-spacing: 0.08em;
    text-transform: uppercase;
    margin-top: 2px;
  }
  .card-body { padding: 24px 22px; display: flex; flex-direction: column; gap: 20px; }

  .section-label {
    font-size: 9px;
    letter-spacing: 0.18em;
    text-transform: uppercase;
    color: var(--clr-gold-dim);
    margin-bottom: 8px;
    display: block;
  }

  .drop-zone {
    border: 2px dashed var(--clr-border-bright);
    border-radius: 3px;
    padding: 36px 20px;
    text-align: center;
    cursor: pointer;
    transition: all 200ms ease;
    background: var(--clr-void);
    position: relative;
  }
  .drop-zone:hover, .drop-zone.dragover {
    border-color: var(--clr-gold);
    background: var(--clr-gold-ghost);
  }
  .drop-zone input[type="file"] {
    position: absolute; inset: 0; opacity: 0; cursor: pointer; width: 100%; height: 100%;
  }
  .drop-zone-icon { font-size: 36px; margin-bottom: 12px; color: var(--clr-gold-dim); }
  .drop-zone-text { font-family: var(--font-display); font-size: 14px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; color: var(--clr-text-dim); }
  .drop-zone-hint { font-size: 11px; color: var(--clr-text-dim); margin-top: 6px; }
  .drop-zone.has-file .drop-zone-icon { color: var(--clr-gold); }
  .drop-zone.has-file .drop-zone-text { color: var(--clr-text); }

  .btn {
    display: inline-flex; align-items: center; justify-content: center; gap: 8px;
    padding: 10px 22px;
    font-family: var(--font-display);
    font-size: 13px; font-weight: 600;
    letter-spacing: 0.1em; text-transform: uppercase;
    border: 1px solid var(--clr-border-bright);
    background: transparent; color: var(--clr-text-dim);
    cursor: pointer; border-radius: 2px;
    transition: all 150ms ease; width: 100%;
  }
  .btn:hover:not(:disabled) { border-color: var(--clr-gold); color: var(--clr-gold); background: var(--clr-gold-ghost); }
  .btn-primary { background: var(--clr-gold); color: #0A0A0A; border-color: var(--clr-gold); }
  .btn-primary:hover:not(:disabled) { background: var(--clr-gold-bright); border-color: var(--clr-gold-bright); }
  .btn:disabled { opacity: 0.4; cursor: not-allowed; }

  .status-box {
    border-radius: 2px;
    padding: 12px 16px;
    font-size: 12px;
    line-height: 1.6;
    display: none;
    border: 1px solid;
  }
  .status-box.visible { display: block; }
  .status-box.success { background: rgba(74,124,89,0.12); border-color: rgba(74,124,89,0.35); color: #7EC897; }
  .status-box.error   { background: var(--clr-red-dim); border-color: rgba(192,57,43,0.4); color: #F0A090; }
  .status-box.info    { background: var(--clr-gold-ghost); border-color: rgba(201,168,76,0.25); color: var(--clr-gold); }

  .divider {
    height: 1px;
    background: linear-gradient(90deg, var(--clr-gold-dim) 0%, transparent 70%);
  }

  .download-row {
    display: flex; align-items: center; gap: 10px;
  }
  .download-row .btn { flex: 1; }

  .meta-info {
    display: flex; flex-wrap: wrap; gap: 8px;
    font-size: 10px; color: var(--clr-text-dim);
    letter-spacing: 0.06em;
  }
  .meta-info span {
    background: var(--clr-raised);
    border: 1px solid var(--clr-border);
    padding: 2px 8px; border-radius: 2px;
  }

  @keyframes spin { to { transform: rotate(360deg); } }
  .spin { display: inline-block; animation: spin 1s linear infinite; }

  /* Scrollbar */
  ::-webkit-scrollbar { width: 6px; }
  ::-webkit-scrollbar-track { background: var(--clr-void); }
  ::-webkit-scrollbar-thumb { background: var(--clr-gold-dim); }
</style>
</head>
<body>

<div class="card">
  <div class="card-header">
    <div>
      <div class="card-title">📡 LAN Sync</div>
      <div class="card-subtitle">tPlanner — 局域网数据同步</div>
    </div>
  </div>

  <div class="card-body">

    <!-- Status -->
    <div id="status-box" class="status-box"></div>

    <!-- Upload Section -->
    <div>
      <span class="section-label">上传 JSON 数据文件</span>
      <div class="drop-zone" id="drop-zone">
        <input type="file" id="file-input" accept=".json,application/json" />
        <div class="drop-zone-icon">📂</div>
        <div class="drop-zone-text" id="drop-label">拖拽文件到这里 或 点击选择</div>
        <div class="drop-zone-hint">仅支持 .json 格式，文件来自 tPlanner 导出</div>
      </div>
    </div>

    <!-- Upload button -->
    <button class="btn btn-primary" id="btn-upload" disabled>
      <span id="btn-upload-icon">⬆</span>
      <span id="btn-upload-text">上传并同步到电视</span>
    </button>

    <div class="divider"></div>

    <!-- Download current data -->
    <div>
      <span class="section-label">下载当前数据</span>
      <div class="download-row">
        <button class="btn" id="btn-download" onclick="window.location='/api/export'">
          ⬇ 下载 tplanner-data.json
        </button>
      </div>
    </div>

    <!-- Server meta -->
    <div class="meta-info" id="meta-info">
      <span>正在加载…</span>
    </div>

  </div>
</div>

<script>
  const dropZone   = document.getElementById('drop-zone');
  const fileInput  = document.getElementById('file-input');
  const btnUpload  = document.getElementById('btn-upload');
  const statusBox  = document.getElementById('status-box');
  const dropLabel  = document.getElementById('drop-label');
  const btnIcon    = document.getElementById('btn-upload-icon');
  const btnText    = document.getElementById('btn-upload-text');
  const metaInfo   = document.getElementById('meta-info');

  let selectedFile = null;

  // Load event count
  fetch('/api/events')
    .then(r => r.json())
    .then(data => {
      metaInfo.innerHTML =
        '<span>当前事件数: ' + data.length + '</span>' +
        '<span>服务端数据: data.json</span>';
    })
    .catch(() => { metaInfo.innerHTML = '<span>无法读取服务端数据</span>'; });

  // File selection
  fileInput.addEventListener('change', (e) => {
    const file = e.target.files[0];
    if (file) setFile(file);
  });

  function setFile(file) {
    selectedFile = file;
    dropLabel.textContent = '已选择: ' + file.name + ' (' + (file.size / 1024).toFixed(1) + ' KB)';
    dropZone.classList.add('has-file');
    btnUpload.disabled = false;
    showStatus('info', '已选择文件，点击"上传并同步"按钮继续。');
  }

  // Drag & Drop
  dropZone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropZone.classList.add('dragover');
  });
  dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragover'));
  dropZone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropZone.classList.remove('dragover');
    const file = e.dataTransfer.files[0];
    if (file) setFile(file);
  });

  // Upload
  btnUpload.addEventListener('click', async () => {
    if (!selectedFile) return;
    btnUpload.disabled = true;
    btnIcon.className  = 'spin';
    btnIcon.textContent = '↻';
    btnText.textContent = '上传中…';
    showStatus('info', '正在上传并验证文件…');

    try {
      const form = new FormData();
      form.append('file', selectedFile);

      const res  = await fetch('/api/upload', { method: 'POST', body: form });
      const data = await res.json();

      if (res.ok && data.success) {
        showStatus('success',
          '✓ 同步成功！共导入 ' + data.count + ' 个事件。\\n' +
          '保存时间: ' + new Date(data.savedAt).toLocaleString('zh-CN') + '\\n\\n' +
          '电视端请刷新页面或按 SYNC 按钮拉取最新数据。'
        );
        metaInfo.innerHTML = '<span>当前事件数: ' + data.count + '</span><span>最后同步: ' + new Date(data.savedAt).toLocaleTimeString('zh-CN') + '</span>';
      } else {
        showStatus('error', '上传失败: ' + (data.error || '未知错误'));
      }
    } catch (err) {
      showStatus('error', '网络错误: ' + err.message);
    } finally {
      btnIcon.className  = '';
      btnIcon.textContent = '⬆';
      btnText.textContent = '上传并同步到电视';
      btnUpload.disabled = false;
    }
  });

  function showStatus(type, msg) {
    statusBox.className = 'status-box visible ' + type;
    statusBox.style.whiteSpace = 'pre-line';
    statusBox.textContent = msg;
  }
</script>
</body>
</html>`;
