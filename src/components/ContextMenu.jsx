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
    const menuW = 220, menuH = 160;
    const left = Math.max(8, Math.min(x, window.innerWidth - menuW - 8));
    const top = Math.max(8, Math.min(y, window.innerHeight - menuH - 8));

    return (
        <div
            ref={ref}
            className="tp-popover"
            style={{
                position: 'fixed',
                left, top,
                zIndex: 9999,
                padding: 4,
                minWidth: menuW,
                fontFamily: 'var(--font-display)',
                fontSize: 'var(--tp-profile-body-font-size)',
            }}
        >
            <div style={{ padding: '4px 10px 6px', fontSize: 'var(--tp-profile-meta-font-size)', color: 'var(--clr-text-dim)', letterSpacing: '0.08em', textTransform: 'uppercase', borderBottom: '1px solid var(--clr-border)' }}>
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
            type="button"
            className={`tp-menu-item${danger ? ' tp-menu-item--danger' : ''}`}
            onClick={onClick}
        >
            {icon}
            {label}
        </button>
    );
}
