import { useState, useEffect } from 'react';
import { Minus, Maximize2, Minimize2, X } from 'lucide-react';

/**
 * Custom frameless window title bar — Soviet Constructivism style.
 * Draggable, with minimize / maximize / close controls.
 * Only renders inside Electron (window.electronAPI must be present).
 */
export default function TitleBar({ title = 'TPLANNER' }) {
    const [isMaximized, setIsMaximized] = useState(false);

    useEffect(() => {
        if (!window.electronAPI) return;
        window.electronAPI.isMaximized().then(setIsMaximized);
        const cleanup = window.electronAPI.onMaximizeChange(setIsMaximized);
        return cleanup;
    }, []);

    if (!window.electronAPI) return null;

    return (
        <div className="titlebar" id="titlebar">
            {/* Draggable region */}
            <div className="titlebar-drag">
                <div className="titlebar-logo">
                    <div className="titlebar-accent-bar" />
                    <div className="titlebar-accent-bar titlebar-accent-bar--gold" />
                    <span className="titlebar-title">{title}</span>
                    <span className="titlebar-subtitle">TEMPORAL PLANNER</span>
                </div>
            </div>

            {/* Window controls — non-draggable */}
            <div className="titlebar-controls">
                <button
                    className="titlebar-btn titlebar-btn--minimize"
                    onClick={() => window.electronAPI.minimize()}
                    title="最小化"
                    aria-label="Minimize"
                >
                    <Minus size={12} strokeWidth={2.5} />
                </button>
                <button
                    className="titlebar-btn titlebar-btn--maximize"
                    onClick={() => window.electronAPI.maximize()}
                    title={isMaximized ? '还原' : '最大化'}
                    aria-label="Maximize"
                >
                    {isMaximized
                        ? <Minimize2 size={11} strokeWidth={2.5} />
                        : <Maximize2 size={11} strokeWidth={2.5} />
                    }
                </button>
                <button
                    className="titlebar-btn titlebar-btn--close"
                    onClick={() => window.electronAPI.close()}
                    title="关闭（最小化到托盘）"
                    aria-label="Close"
                >
                    <X size={12} strokeWidth={2.5} />
                </button>
            </div>
        </div>
    );
}
