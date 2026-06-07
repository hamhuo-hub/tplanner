import { useState, useEffect, useRef, useCallback } from 'react';
import { Terminal, X, RefreshCw, Cpu, MemoryStick } from 'lucide-react';

function fmt(bytes) {
    if (bytes == null) return '—';
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

export default function DebugPanel() {
    const [open, setOpen]     = useState(false);
    const [perf, setPerf]     = useState(null);
    const [logs, setLogs]     = useState([]);      // captured console logs
    const [filter, setFilter] = useState('');
    const logsRef             = useRef([]);
    const endRef              = useRef(null);
    const isElectron          = typeof window !== 'undefined' && !!window.electronAPI;

    // F12 global shortcut
    useEffect(() => {
        const handler = (e) => {
            if (e.key === 'F12') { e.preventDefault(); setOpen(v => !v); }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, []);

    // Intercept console methods
    useEffect(() => {
        const original = { log: console.log, warn: console.warn, error: console.error, info: console.info };
        const levels = { log: 'log', warn: 'warn', error: 'error', info: 'info' };

        // Patterns already handled by suppressLogs.js at startup, but DebugPanel
        // also skips them so the panel stays clean.
        const SKIP = [
            /i18next is made possible/,
            /RxDB Open Core/,
            /free Dexie\.js/,
            /setPremiumFlag/,
            /Blocked aria-hidden/,
        ];

        function intercept(level) {
            return (...args) => {
                original[level](...args);
                const msg = args.map(a => typeof a === 'string' ? a : '').join(' ');
                if (SKIP.some(p => p.test(msg))) return;
                const entry = {
                    id:   Date.now() + Math.random(),
                    time: new Date().toLocaleTimeString('zh', { hour12: false }),
                    level,
                    msg:  args.map(a => {
                        if (typeof a === 'string') return a;
                        try { return JSON.stringify(a, null, 0); } catch { return String(a); }
                    }).join(' '),
                };
                logsRef.current = [...logsRef.current.slice(-499), entry];
                setLogs([...logsRef.current]);
            };
        }

        for (const level of Object.keys(levels)) console[level] = intercept(level);
        return () => { for (const level of Object.keys(original)) console[level] = original[level]; };
    }, []);

    // Auto-scroll to bottom
    useEffect(() => {
        if (open) endRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [logs, open]);

    // Fetch perf info
    const refreshPerf = useCallback(async () => {
        if (!isElectron || !window.electronAPI?.getPerfInfo) return;
        try {
            const info = await window.electronAPI.getPerfInfo();
            setPerf(info);
        } catch (_) {}
    }, [isElectron]);

    useEffect(() => {
        if (!open) return;
        refreshPerf();
        const t = setInterval(refreshPerf, 3000);
        return () => clearInterval(t);
    }, [open, refreshPerf]);

    const openDevTools = () => {
        if (isElectron) window.electronAPI.toggleDevTools?.();
    };

    const clearLogs = () => { logsRef.current = []; setLogs([]); };

    const filtered = filter
        ? logs.filter(l => l.msg.toLowerCase().includes(filter.toLowerCase()) || l.level.includes(filter))
        : logs;

    if (!open) return null;

    return (
        <div style={{
            position: 'fixed', bottom: 0, right: 0, zIndex: 9000,
            width: 520, height: 340,
            background: '#0d0d0d', border: '1px solid #2a2a2a',
            borderRadius: '8px 0 0 0',
            display: 'flex', flexDirection: 'column',
            fontFamily: 'var(--font-mono, monospace)',
            fontSize: 11,
            boxShadow: '0 -4px 20px rgba(0,0,0,0.7)',
        }}>
            {/* Header */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', borderBottom: '1px solid #222', background: '#111', borderRadius: '8px 0 0 0' }}>
                <Terminal size={12} style={{ color: '#666' }} />
                <span style={{ color: '#888', fontSize: 10, letterSpacing: '0.08em', textTransform: 'uppercase' }}>Debug Console</span>
                <span style={{ color: '#444', fontSize: 10, marginLeft: 2 }}>F12</span>

                {/* Perf badges */}
                {perf && (
                    <div style={{ display: 'flex', gap: 6, marginLeft: 8 }}>
                        <Badge icon={<MemoryStick size={9} />} label={fmt(perf.processMemory?.rss)} color="#5B8FCC" title="Main process RSS" />
                        <Badge icon={<Cpu size={9} />} label={`${Math.round(perf.uptime)}s`} color="#4A9DA8" title="Uptime" />
                    </div>
                )}

                <div style={{ marginLeft: 'auto', display: 'flex', gap: 4 }}>
                    <IconBtn onClick={refreshPerf} title="刷新性能信息"><RefreshCw size={11} /></IconBtn>
                    <IconBtn onClick={openDevTools} title="打开 Electron DevTools (独立窗口)">
                        <span style={{ fontSize: 10, letterSpacing: 0 }}>DevTools</span>
                    </IconBtn>
                    <IconBtn onClick={clearLogs} title="清空日志">
                        <span style={{ fontSize: 10 }}>清空</span>
                    </IconBtn>
                    <IconBtn onClick={() => setOpen(false)} title="关闭"><X size={11} /></IconBtn>
                </div>
            </div>

            {/* Perf detail row */}
            {perf && (
                <div style={{ display: 'flex', gap: 12, padding: '4px 10px', borderBottom: '1px solid #1a1a1a', background: '#0f0f0f', flexWrap: 'wrap' }}>
                    <PerfItem label="Main RSS"    value={fmt(perf.processMemory?.rss)} />
                    <PerfItem label="Heap used"   value={fmt(perf.processMemory?.heapUsed)} />
                    <PerfItem label="Heap total"  value={fmt(perf.processMemory?.heapTotal)} />
                    <PerfItem label="External"    value={fmt(perf.processMemory?.external)} />
                    <PerfItem label="Node"        value={perf.nodeVersion} />
                    <PerfItem label="Electron"    value={perf.electronVersion} />
                </div>
            )}

            {/* Filter */}
            <div style={{ padding: '4px 10px', borderBottom: '1px solid #1a1a1a' }}>
                <input
                    type="text"
                    placeholder="过滤日志…"
                    value={filter}
                    onChange={e => setFilter(e.target.value)}
                    style={{ width: '100%', background: 'transparent', border: 'none', outline: 'none', color: '#888', fontSize: 11, fontFamily: 'inherit' }}
                />
            </div>

            {/* Log list */}
            <div style={{ flex: 1, overflow: 'auto', padding: '4px 0' }}>
                {filtered.length === 0 ? (
                    <div style={{ color: '#333', textAlign: 'center', marginTop: 20, fontSize: 10 }}>暂无日志</div>
                ) : (
                    filtered.map(entry => (
                        <LogLine key={entry.id} entry={entry} />
                    ))
                )}
                <div ref={endRef} />
            </div>

            {/* Footer: log count */}
            <div style={{ padding: '3px 10px', borderTop: '1px solid #1a1a1a', color: '#444', fontSize: 10, display: 'flex', justifyContent: 'space-between' }}>
                <span>{logs.length} 条日志{filter ? `（过滤后 ${filtered.length} 条）` : ''}</span>
                <span style={{ color: '#333' }}>最多保留 500 条</span>
            </div>
        </div>
    );
}

function LogLine({ entry }) {
    const colors = { log: '#ccc', info: '#5B8FCC', warn: '#C9A84C', error: '#C0392B' };
    const bgColors = { error: 'rgba(192,57,43,0.06)', warn: 'rgba(201,168,76,0.04)' };
    return (
        <div style={{
            display: 'flex', gap: 8, padding: '2px 10px',
            background: bgColors[entry.level] || 'transparent',
            borderLeft: `2px solid ${entry.level === 'error' ? '#C0392B' : entry.level === 'warn' ? '#C9A84C' : 'transparent'}`,
        }}>
            <span style={{ color: '#444', flexShrink: 0 }}>{entry.time}</span>
            <span style={{ color: colors[entry.level] ?? '#ccc', wordBreak: 'break-all', whiteSpace: 'pre-wrap' }}>{entry.msg}</span>
        </div>
    );
}

function Badge({ icon, label, color, title }) {
    return (
        <span title={title} style={{ display: 'inline-flex', alignItems: 'center', gap: 3, background: `${color}18`, color, padding: '1px 5px', borderRadius: 3, fontSize: 10 }}>
            {icon}{label}
        </span>
    );
}

function PerfItem({ label, value }) {
    return (
        <span style={{ fontSize: 10, color: '#555' }}>
            <span style={{ color: '#444' }}>{label} </span>
            <span style={{ color: '#777' }}>{value}</span>
        </span>
    );
}

function IconBtn({ onClick, title, children }) {
    return (
        <button onClick={onClick} title={title} style={{
            background: 'none', border: 'none', cursor: 'pointer',
            color: '#555', padding: '2px 5px', borderRadius: 3,
            display: 'inline-flex', alignItems: 'center',
        }}
            onMouseEnter={e => e.currentTarget.style.color = '#aaa'}
            onMouseLeave={e => e.currentTarget.style.color = '#555'}
        >
            {children}
        </button>
    );
}
