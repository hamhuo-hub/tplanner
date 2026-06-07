import { useState, useRef } from 'react';
import { Languages, Printer, Download, Upload, MoreHorizontal } from 'lucide-react';
import ZoomControl from './ZoomControl';

export default function DecadeFab({ onToggleLanguage, lang, onPrint, onExport, onImport }) {
    const [open, setOpen] = useState(false);
    const fileRef = useRef(null);

    // Visual top-index: 0=Import(top/farthest), 4=Zoom(bottom/closest)
    // Open:  cascade bottom→top  → delay = (4 - topIdx) * 30
    // Close: cascade top→bottom  → delay = topIdx * 30
    const delay = (topIdx) => open ? (4 - topIdx) * 30 : topIdx * 30;

    const pill = (topIdx, onClick) => ({
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        padding: '6px 14px 6px 10px',
        background: 'var(--clr-surface)',
        border: '1px solid var(--clr-border)',
        borderRadius: 20,
        color: 'var(--clr-text)',
        fontFamily: 'var(--font-display)',
        fontSize: 11,
        letterSpacing: '0.08em',
        textTransform: 'uppercase',
        cursor: 'pointer',
        whiteSpace: 'nowrap',
        boxShadow: '0 2px 10px rgba(0,0,0,0.35)',
        opacity: open ? 1 : 0,
        transform: open ? 'translateY(0)' : 'translateY(10px)',
        transition: `opacity 180ms ease ${delay(topIdx)}ms, transform 180ms ease ${delay(topIdx)}ms`,
        pointerEvents: open ? 'auto' : 'none',
    });

    const zoomWrap = {
        opacity: open ? 1 : 0,
        transform: open ? 'translateY(0)' : 'translateY(10px)',
        transition: `opacity 180ms ease ${delay(4)}ms, transform 180ms ease ${delay(4)}ms`,
        pointerEvents: open ? 'auto' : 'none',
    };

    return (
        <div
            style={{ position: 'fixed', bottom: 36, right: 24, zIndex: 1000 }}
            onMouseEnter={() => setOpen(true)}
            onMouseLeave={() => setOpen(false)}
        >
            {/* Items — float above the FAB button */}
            <div style={{
                position: 'absolute',
                bottom: 'calc(100% + 10px)',
                right: 0,
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'flex-end',
                gap: 8,
            }}>
                {/* topIdx 0 — Import (farthest, last to appear) */}
                <button style={pill(0)} onClick={() => fileRef.current?.click()}>
                    <Upload size={13} /> 导入
                </button>

                {/* topIdx 1 — Export */}
                <button style={pill(1)} onClick={onExport}>
                    <Download size={13} /> 导出
                </button>

                {/* topIdx 2 — Print */}
                <button style={pill(2)} onClick={onPrint}>
                    <Printer size={13} /> 打印
                </button>

                {/* topIdx 3 — Language */}
                <button style={pill(3)} onClick={onToggleLanguage}>
                    <Languages size={13} /> {lang === 'en' ? '中文' : 'EN'}
                </button>

                {/* topIdx 4 — Zoom (closest, first to appear) */}
                <div style={zoomWrap}>
                    <ZoomControl />
                </div>
            </div>

            {/* Main FAB circle */}
            <button style={{
                width: 40,
                height: 40,
                borderRadius: '50%',
                background: 'var(--clr-surface)',
                border: `1px solid ${open ? 'var(--clr-gold)' : 'var(--clr-border-bright)'}`,
                color: open ? 'var(--clr-gold)' : 'var(--clr-text-dim)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                cursor: 'pointer',
                boxShadow: open
                    ? '0 4px 20px rgba(201,168,76,0.25)'
                    : '0 2px 12px rgba(0,0,0,0.45)',
                transition: 'border-color 180ms, color 180ms, box-shadow 180ms',
            }}>
                <MoreHorizontal size={16} />
            </button>

            <input
                ref={fileRef}
                type="file"
                accept=".json"
                style={{ display: 'none' }}
                onChange={onImport}
            />
        </div>
    );
}
