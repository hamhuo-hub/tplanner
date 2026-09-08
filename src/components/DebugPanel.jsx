import { useState, useEffect, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Terminal, X, RefreshCw, Cpu, MemoryStick } from 'lucide-react';

function fmt(bytes) {
    if (bytes == null) return '—';
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

export default function DebugPanel() {
    const { t } = useTranslation();
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
            width: 'min(520px, 100vw)', height: 'min(340px, 70dvh)',
            background: 'var(--tp-semantic-color-surface)', border: '1px solid var(--tp-semantic-color-border-subtle)',
            borderRadius: 'var(--tp-semantic-radius-card)',
            display: 'flex', flexDirection: 'column',
            fontFamily: 'var(--font-mono, monospace)',
            fontSize: 'var(--tp-profile-meta-font-size)',
            boxShadow: 'var(--tp-shadow-dialog)',
        }}>
            {/* Header */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', borderBottom: '1px solid var(--tp-semantic-color-border-subtle)', background: 'var(--tp-semantic-color-canvas)', borderRadius: 'var(--tp-semantic-radius-card)' }}>
                <Terminal size={12} style={{ color: 'var(--tp-semantic-color-text-secondary)' }} />
                <span style={{ color: 'var(--tp-semantic-color-text-secondary)', fontSize: 'var(--tp-profile-meta-font-size)', letterSpacing: '0.08em', textTransform: 'uppercase' }}>{t('debug.title')}</span>
                <span style={{ color: 'var(--tp-semantic-color-text-muted)', fontSize: 'var(--tp-profile-meta-font-size)', marginLeft: 2 }}>F12</span>

                {/* Perf badges */}
                {perf && (
                    <div style={{ display: 'flex', gap: 6, marginLeft: 8 }}>
                        <Badge icon={<MemoryStick size={9} />} label={fmt(perf.processMemory?.rss)} color="var(--tp-semantic-color-info)" title="Main process RSS" />
                        <Badge icon={<Cpu size={9} />} label={`${Math.round(perf.uptime)}s`} color="var(--tp-semantic-color-success)" title="Uptime" />
                    </div>
                )}

                <div style={{ marginLeft: 'auto', display: 'flex', gap: 4 }}>
                    <IconBtn onClick={refreshPerf} title={t('debug.refreshPerf')}><RefreshCw size={11} /></IconBtn>
                    <IconBtn onClick={openDevTools} title={t('debug.openDevTools')}>
                        <span style={{ fontSize: 'var(--tp-profile-meta-font-size)', letterSpacing: 0 }}>DevTools</span>
                    </IconBtn>
                    <IconBtn onClick={clearLogs} title={t('debug.clearLogs')}>
                        <span style={{ fontSize: 'var(--tp-profile-meta-font-size)' }}>{t('debug.clearLogs')}</span>
                    </IconBtn>
                    <IconBtn onClick={() => setOpen(false)} title={t('debug.close')}><X size={11} /></IconBtn>
                </div>
            </div>

            {/* Perf detail row */}
            {perf && (
                <div style={{ display: 'flex', gap: 12, padding: '4px 10px', borderBottom: '1px solid var(--tp-semantic-color-border-subtle)', background: 'var(--tp-semantic-color-surface)', flexWrap: 'wrap' }}>
                    <PerfItem label="Main RSS"    value={fmt(perf.processMemory?.rss)} />
                    <PerfItem label="Heap used"   value={fmt(perf.processMemory?.heapUsed)} />
                    <PerfItem label="Heap total"  value={fmt(perf.processMemory?.heapTotal)} />
                    <PerfItem label="External"    value={fmt(perf.processMemory?.external)} />
                    <PerfItem label="Node"        value={perf.nodeVersion} />
                    <PerfItem label="Electron"    value={perf.electronVersion} />
                </div>
            )}

            {/* Filter */}
            <div style={{ padding: '4px 10px', borderBottom: '1px solid var(--tp-semantic-color-border-subtle)' }}>
                <input
                    type="text"
                    placeholder={t('debug.filterPlaceholder')}
                    value={filter}
                    onChange={e => setFilter(e.target.value)}
                    style={{ width: '100%', background: 'transparent', border: 'none', outline: 'none', color: 'var(--tp-semantic-color-text-secondary)', fontSize: 'var(--tp-profile-meta-font-size)', fontFamily: 'inherit' }}
                />
            </div>

            {/* Log list */}
            <div style={{ flex: 1, overflow: 'auto', padding: '4px 0' }}>
                {filtered.length === 0 ? (
                    <div style={{ color: 'var(--tp-semantic-color-text-muted)', textAlign: 'center', marginTop: 20, fontSize: 'var(--tp-profile-meta-font-size)' }}>{t('debug.noLogs')}</div>
                ) : (
                    filtered.map(entry => (
                        <LogLine key={entry.id} entry={entry} />
                    ))
                )}
                <div ref={endRef} />
            </div>

            {/* Footer: log count */}
            <div style={{ padding: '3px 10px', borderTop: '1px solid var(--tp-semantic-color-border-subtle)', color: 'var(--tp-semantic-color-text-muted)', fontSize: 'var(--tp-profile-meta-font-size)', display: 'flex', justifyContent: 'space-between' }}>
                <span>{filter ? t('debug.logCountFiltered', { total: logs.length, filtered: filtered.length }) : t('debug.logCount', { total: logs.length })}</span>
                <span style={{ color: 'var(--tp-semantic-color-text-muted)' }}>{t('debug.maxRetention')}</span>
            </div>
        </div>
    );
}

function LogLine({ entry }) {
    const colors = { log: 'var(--tp-semantic-color-text-primary)', info: 'var(--tp-semantic-color-info)', warn: 'var(--tp-semantic-color-warning)', error: 'var(--tp-semantic-color-error)' };
    const bgColors = { error: 'var(--tp-semantic-color-error-background)', warn: 'var(--tp-semantic-color-warning-background)' };
    return (
        <div style={{
            display: 'flex', gap: 8, padding: '2px 10px',
            background: bgColors[entry.level] || 'transparent',
            borderLeft: `2px solid ${entry.level === 'error' ? 'var(--tp-semantic-color-error)' : entry.level === 'warn' ? 'var(--tp-semantic-color-warning)' : 'transparent'}`,
        }}>
            <span style={{ color: 'var(--tp-semantic-color-text-muted)', flexShrink: 0 }}>{entry.time}</span>
            <span style={{ color: colors[entry.level] ?? 'var(--tp-semantic-color-text-primary)', wordBreak: 'break-all', whiteSpace: 'pre-wrap' }}>{entry.msg}</span>
        </div>
    );
}

function Badge({ icon, label, color, title }) {
    return (
        <span title={title} style={{ display: 'inline-flex', alignItems: 'center', gap: 3, background: 'var(--tp-semantic-color-raised)', color, padding: '1px 5px', borderRadius: 3, fontSize: 'var(--tp-profile-meta-font-size)' }}>
            {icon}{label}
        </span>
    );
}

function PerfItem({ label, value }) {
    return (
        <span style={{ fontSize: 'var(--tp-profile-meta-font-size)', color: 'var(--tp-semantic-color-text-secondary)' }}>
            <span style={{ color: 'var(--tp-semantic-color-text-muted)' }}>{label} </span>
            <span style={{ color: 'var(--tp-semantic-color-text-secondary)' }}>{value}</span>
        </span>
    );
}

function IconBtn({ onClick, title, children }) {
    return (
        <button onClick={onClick} title={title} style={{
            background: 'none', border: 'none', cursor: 'pointer',
            color: 'var(--tp-semantic-color-text-secondary)', padding: '2px 5px', borderRadius: 3,
            display: 'inline-flex', alignItems: 'center',
        }}
            onMouseEnter={e => e.currentTarget.style.color = 'var(--tp-semantic-color-accent-text)'}
            onMouseLeave={e => e.currentTarget.style.color = 'var(--tp-semantic-color-text-secondary)'}
        >
            {children}
        </button>
    );
}
