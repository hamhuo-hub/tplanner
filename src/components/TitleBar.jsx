import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Minus, Maximize2, Minimize2, X } from 'lucide-react';

/**
 * Custom frameless window title bar — Soviet Constructivism style.
 * Draggable, with minimize / maximize / close controls.
 * Only renders inside Electron (window.electronAPI must be present).
 */
export default function TitleBar({ title = 'TPLANNER' }) {
    const { t } = useTranslation();
    const [isMaximized, setIsMaximized] = useState(false);

    useEffect(() => {
        if (!window.electronAPI) return;
        window.electronAPI.isMaximized().then(setIsMaximized);
        const cleanup = window.electronAPI.onMaximizeChange(setIsMaximized);
        return cleanup;
    }, []);

    // Detect mobile or browser environment - TitleBar should only ever show in Electron
    const isMobile = typeof window !== 'undefined' && /Mobi|Android/i.test(navigator.userAgent);
    if (!window.electronAPI || isMobile) return null;

    return (
        <div className="titlebar" id="titlebar">
            {/* Draggable region */}
            <div className="titlebar-drag">
                <div className="titlebar-logo">
                    <div className="titlebar-accent-bar" />
                    <div className="titlebar-accent-bar titlebar-accent-bar--gold" />
                    <span className="titlebar-title">{title}</span>
                    <span className="titlebar-subtitle">{t('window.subtitle')}</span>
                </div>
            </div>

            {/* Window controls — non-draggable */}
            <div className="titlebar-controls">
                <button
                    className="titlebar-btn titlebar-btn--minimize"
                    onClick={() => window.electronAPI.minimize()}
                    title={t('window.minimize')}
                    aria-label={t('window.minimize')}
                >
                    <Minus size={12} strokeWidth={2.5} />
                </button>
                <button
                    className="titlebar-btn titlebar-btn--maximize"
                    onClick={() => window.electronAPI.maximize()}
                    title={isMaximized ? t('window.restore') : t('window.maximize')}
                    aria-label={isMaximized ? t('window.restore') : t('window.maximize')}
                >
                    {isMaximized
                        ? <Minimize2 size={11} strokeWidth={2.5} />
                        : <Maximize2 size={11} strokeWidth={2.5} />
                    }
                </button>
                <button
                    className="titlebar-btn titlebar-btn--close"
                    onClick={() => window.electronAPI.close()}
                    title={t('window.closeToTray')}
                    aria-label={t('actions.close')}
                >
                    <X size={12} strokeWidth={2.5} />
                </button>
            </div>
        </div>
    );
}
