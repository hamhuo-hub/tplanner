import { useEffect, useRef } from 'react';
import { Copy, Trash2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';

export default function ContextMenu({ x, y, event, onClose, onCopy, onDelete }) {
    const { t } = useTranslation();
    const ref = useRef(null);

    // Close on outside click or Escape
    useEffect(() => {
        const onDown = (e) => { if (!ref.current?.contains(e.target)) onClose(); };
        const onKey  = (e) => { if (e.key === 'Escape') onClose(); };
        document.addEventListener('mousedown', onDown);
        document.addEventListener('keydown', onKey);
        return () => {
            document.removeEventListener('mousedown', onDown);
            document.removeEventListener('keydown', onKey);
        };
    }, [onClose]);

    // Keep menu inside viewport
    const menuW = 160, menuH = 80;
    const left = x + menuW > window.innerWidth  ? x - menuW : x;
    const top  = y + menuH > window.innerHeight ? y - menuH : y;

    return (
        <div
            ref={ref}
            style={{
                position: 'fixed',
                left, top,
                zIndex: 9999,
                background: 'var(--clr-surface, #1e1e1e)',
                border: '1px solid var(--clr-border, #333)',
                borderRadius: 6,
                padding: '4px 0',
                minWidth: menuW,
                boxShadow: '0 4px 16px rgba(0,0,0,0.5)',
                fontFamily: 'var(--font-display)',
                fontSize: 12,
            }}
        >
            <div style={{ padding: '4px 10px 6px', fontSize: 10, color: 'var(--clr-text-dim)', letterSpacing: '0.08em', textTransform: 'uppercase', borderBottom: '1px solid var(--clr-border, #333)' }}>
                {event?.title}
            </div>
            <MenuItem icon={<Copy size={13} />} label={t('contextMenu.copy')} onClick={() => { onCopy(event); onClose(); }} />
            <MenuItem icon={<Trash2 size={13} />} label={t('contextMenu.delete')} danger onClick={() => { onDelete(event); onClose(); }} />
        </div>
    );
}

function MenuItem({ icon, label, onClick, danger }) {
    return (
        <button
            onClick={onClick}
            style={{
                display: 'flex', alignItems: 'center', gap: 8,
                width: '100%', padding: '7px 14px',
                background: 'none', border: 'none', cursor: 'pointer',
                color: danger ? 'var(--clr-red, #C0392B)' : 'var(--clr-text, #e0e0e0)',
                textAlign: 'left', fontSize: 12,
                fontFamily: 'inherit',
            }}
            onMouseEnter={e => e.currentTarget.style.background = 'rgba(255,255,255,0.06)'}
            onMouseLeave={e => e.currentTarget.style.background = 'none'}
        >
            {icon}
            {label}
        </button>
    );
}
