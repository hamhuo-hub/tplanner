import { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { ZoomIn, ZoomOut, RotateCcw } from 'lucide-react';

const PRESETS = [75, 85, 100, 110, 125, 150];
const STORAGE_KEY = 'tplanner_zoom_factor';

/**
 * ZoomControl — GUI scale adjustment button for the header.
 *
 * Uses Electron's webContents.setZoomFactor via IPC when available.
 * Falls back to document.body.style.zoom for browser mode.
 * Persists selection to localStorage.
 */
export default function ZoomControl() {
    const { t } = useTranslation();
    const [zoom, setZoom]       = useState(() => {
        const saved = parseFloat(localStorage.getItem(STORAGE_KEY) || '1');
        return isNaN(saved) ? 1 : saved;
    });
    const [isOpen, setIsOpen]   = useState(false);
    const panelRef              = useRef(null);

    // Apply zoom on mount and changes
    useEffect(() => {
        applyZoom(zoom);
    }, [zoom]);

    // Close panel on outside click
    useEffect(() => {
        if (!isOpen) return;
        const handler = (e) => {
            if (!panelRef.current?.contains(e.target)) setIsOpen(false);
        };
        document.addEventListener('mousedown', handler);
        return () => document.removeEventListener('mousedown', handler);
    }, [isOpen]);

    const applyZoom = (factor) => {
        const clamped = Math.max(0.5, Math.min(2.0, factor));
        localStorage.setItem(STORAGE_KEY, String(clamped));

        if (window.electronAPI?.setZoom) {
            // Electron: delegate to main process (webContents.setZoomFactor)
            // Main process also persists to userData/zoom.json for next startup
            window.electronAPI.setZoom(clamped);
        } else {
            // Browser fallback — CSS zoom
            document.documentElement.style.zoom = clamped;
        }
    };

    const step = (delta) => {
        setZoom(prev => {
            const next = Math.round((prev + delta) * 100) / 100;
            return Math.max(0.5, Math.min(2.0, next));
        });
    };

    const reset = () => setZoom(1);

    const displayPct = Math.round(zoom * 100);

    return (
        <div style={{ position: 'relative' }} ref={panelRef}>
            {/* Trigger */}
            <button
                onClick={() => setIsOpen(v => !v)}
                className="btn btn--ghost"
                title={t('zoom.title')}
                id="btn-zoom-control"
                style={{
                    fontFamily:    'var(--font-mono)',
                    fontSize:      '11px',
                    letterSpacing: '0.04em',
                    minWidth:      44,
                    padding:       '3px 7px',
                    gap:           3,
                    color:         zoom !== 1 ? 'var(--clr-gold)' : undefined,
                    borderColor:   zoom !== 1 ? 'var(--clr-gold-dim)' : undefined,
                }}
            >
                {displayPct}%
            </button>

            {/* Panel */}
            {isOpen && (
                <div
                    style={{
                        position:     'absolute',
                        top:          'calc(100% + 6px)',
                        right:        0,
                        width:        210,
                        background:   'var(--clr-surface)',
                        border:       '1px solid var(--clr-border-bright)',
                        borderTop:    '2px solid var(--clr-gold)',
                        borderRadius: 'var(--radius)',
                        boxShadow:    '0 12px 40px rgba(0,0,0,0.5)',
                        zIndex:       9000,
                        overflow:     'hidden',
                    }}
                >
                    {/* Header */}
                    <div style={{ padding: '8px 12px 6px', borderBottom: '1px solid var(--clr-border)', background: 'var(--clr-void)' }}>
                        <div style={{ fontFamily: 'var(--font-display)', fontSize: '11px', fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', color: 'var(--clr-gold)' }}>
                            {t('zoom.guiScale')}
                        </div>
                    </div>

                    {/* Stepper row */}
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '10px 12px 6px' }}>
                        <button
                            onClick={() => step(-0.05)}
                            className="btn btn--ghost"
                            disabled={zoom <= 0.5}
                            title={t('zoom.zoomOut')}
                            style={{ padding: '4px 8px', opacity: zoom <= 0.5 ? 0.35 : 1 }}
                        >
                            <ZoomOut size={13} />
                        </button>

                        {/* Slider */}
                        <input
                            type="range"
                            min={50} max={200} step={5}
                            value={displayPct}
                            onChange={e => setZoom(parseInt(e.target.value, 10) / 100)}
                            style={{
                                flex:        1,
                                accentColor: 'var(--clr-gold)',
                                cursor:      'pointer',
                            }}
                        />

                        <button
                            onClick={() => step(0.05)}
                            className="btn btn--ghost"
                            disabled={zoom >= 2.0}
                            title={t('zoom.zoomIn')}
                            style={{ padding: '4px 8px', opacity: zoom >= 2.0 ? 0.35 : 1 }}
                        >
                            <ZoomIn size={13} />
                        </button>
                    </div>

                    {/* Current value display */}
                    <div style={{ textAlign: 'center', fontFamily: 'var(--font-mono)', fontSize: '18px', fontWeight: 600, color: 'var(--clr-gold)', letterSpacing: '0.04em', paddingBottom: 2 }}>
                        {displayPct}<span style={{ fontSize: '11px', color: 'var(--clr-text-dim)' }}>%</span>
                    </div>

                    {/* Preset grid */}
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 4, padding: '8px 12px' }}>
                        {PRESETS.map(pct => (
                            <button
                                key={pct}
                                onClick={() => setZoom(pct / 100)}
                                style={{
                                    background:    pct === displayPct ? 'var(--clr-gold)' : 'var(--clr-raised)',
                                    border:        `1px solid ${pct === displayPct ? 'var(--clr-gold)' : 'var(--clr-border)'}`,
                                    borderRadius:  'var(--radius-sm)',
                                    color:         pct === displayPct ? '#0A0A0A' : 'var(--clr-text-dim)',
                                    fontFamily:    'var(--font-mono)',
                                    fontSize:      '11px',
                                    fontWeight:    pct === displayPct ? 700 : 400,
                                    letterSpacing: '0.06em',
                                    padding:       '5px 0',
                                    cursor:        'pointer',
                                    transition:    'all var(--transition-fast)',
                                    textAlign:     'center',
                                }}
                                onMouseEnter={e => { if (pct !== displayPct) { e.currentTarget.style.borderColor = 'var(--clr-gold-dim)'; e.currentTarget.style.color = 'var(--clr-text)'; }}}
                                onMouseLeave={e => { if (pct !== displayPct) { e.currentTarget.style.borderColor = 'var(--clr-border)'; e.currentTarget.style.color = 'var(--clr-text-dim)'; }}}
                            >
                                {pct}%
                            </button>
                        ))}
                    </div>

                    {/* Reset */}
                    <div style={{ padding: '0 12px 10px' }}>
                        <button
                            onClick={reset}
                            disabled={zoom === 1}
                            style={{
                                width:         '100%',
                                display:       'flex',
                                alignItems:    'center',
                                justifyContent:'center',
                                gap:           5,
                                padding:       '5px',
                                background:    'none',
                                border:        '1px solid var(--clr-border)',
                                borderRadius:  'var(--radius-sm)',
                                cursor:        zoom === 1 ? 'default' : 'pointer',
                                fontFamily:    'var(--font-display)',
                                fontSize:      '10px',
                                letterSpacing: '0.12em',
                                textTransform: 'uppercase',
                                color:         zoom === 1 ? 'var(--clr-text-mute)' : 'var(--clr-text-dim)',
                                opacity:       zoom === 1 ? 0.4 : 1,
                                transition:    'all var(--transition-fast)',
                            }}
                        >
                            <RotateCcw size={10} />
                            {t('zoom.reset')}
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
}
