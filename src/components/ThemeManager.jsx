import { useState, useRef } from 'react';
import { Palette, Check, Trash2, Upload, X } from 'lucide-react';
import { useTheme } from '../contexts/ThemeContext';

const BUILTIN_THEME = {
    id: 'soviet-constructivism',
    name: 'Soviet Constructivism',
    version: '1.0.0',
    author: 'tPlanner',
    description: '黑金苏联构成主义 — 默认内置主题',
    meta: {
        type: 'dark',
        preview: { bg: '#111111', surface: '#181818', accent: '#C9A84C', text: '#E0D8C8' }
    }
};

/**
 * ThemeManager — floating panel for browsing, applying, and installing themes.
 * Triggered by the palette icon in the app header.
 */
export default function ThemeManager() {
    const { activeId, themes, applyTheme, installTheme, uninstallTheme } = useTheme();
    const [isOpen, setIsOpen] = useState(false);
    const [dragOver, setDragOver] = useState(false);
    const fileInputRef = useRef(null);

    const allThemes = [BUILTIN_THEME, ...themes];

    // ── File Handling ──────────────────────────────────────────────────────

    const handleFileRead = (file) => {
        if (!file) return;
        if (!file.name.endsWith('.tptheme')) {
            alert('请选择有效的 .tptheme 文件');
            return;
        }
        const reader = new FileReader();
        reader.onload = (e) => installTheme(e.target.result);
        reader.readAsText(file);
    };

    const handleDrop = (e) => {
        e.preventDefault();
        setDragOver(false);
        const file = e.dataTransfer.files[0];
        handleFileRead(file);
    };

    // ── Preview Swatch ─────────────────────────────────────────────────────

    const Swatch = ({ theme }) => {
        const p = theme.meta?.preview || {};
        return (
            <div style={{ display: 'flex', gap: 3, alignItems: 'center' }}>
                {[p.bg, p.surface, p.accent, p.text].filter(Boolean).map((color, i) => (
                    <div key={i} style={{ width: i === 2 ? 18 : 10, height: 18, background: color, borderRadius: 2, border: '1px solid rgba(255,255,255,0.1)', flexShrink: 0 }} />
                ))}
            </div>
        );
    };

    // ── Theme Card ─────────────────────────────────────────────────────────

    const ThemeCard = ({ theme }) => {
        const isActive  = theme.id === activeId;
        const isBuiltin = theme.id === 'soviet-constructivism';
        const p = theme.meta?.preview || {};

        return (
            <div
                onClick={() => applyTheme(theme.id)}
                style={{
                    background: isActive ? 'var(--clr-gold-ghost)' : 'var(--clr-surface)',
                    border: `1px solid ${isActive ? 'var(--clr-gold)' : 'var(--clr-border)'}`,
                    borderRadius: 'var(--radius)',
                    padding: '10px 12px',
                    cursor: 'pointer',
                    transition: 'all var(--transition-fast)',
                    position: 'relative',
                    overflow: 'hidden',
                }}
            >
                {/* Color preview bar */}
                <div style={{ height: 4, borderRadius: 2, marginBottom: 8, background: `linear-gradient(90deg, ${p.bg || '#111'} 0%, ${p.accent || '#C9A84C'} 100%)`, opacity: 0.9 }} />

                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
                    <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
                            <span style={{ fontFamily: 'var(--font-display)', fontSize: '12px', fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--clr-text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                                {theme.name}
                            </span>
                            {isActive && (
                                <span style={{ fontSize: '9px', background: 'var(--clr-gold)', color: '#000', padding: '1px 5px', borderRadius: 2, fontFamily: 'var(--font-display)', letterSpacing: '0.1em', flexShrink: 0 }}>
                                    ACTIVE
                                </span>
                            )}
                        </div>
                        <div style={{ fontFamily: 'var(--font-mono)', fontSize: '10px', color: 'var(--clr-text-dim)', marginBottom: 6, lineHeight: 1.4 }}>
                            {theme.description || `v${theme.version} · ${theme.meta?.type || 'dark'}`}
                        </div>
                        <Swatch theme={theme} />
                    </div>

                    <div style={{ display: 'flex', gap: 4, flexShrink: 0 }}>
                        {isActive && (
                            <div style={{ width: 20, height: 20, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--clr-gold)' }}>
                                <Check size={13} strokeWidth={2.5} />
                            </div>
                        )}
                        {!isBuiltin && (
                            <button
                                onClick={e => { e.stopPropagation(); if (confirm(`卸载主题「${theme.name}」？`)) uninstallTheme(theme.id); }}
                                title="卸载"
                                style={{ width: 20, height: 20, background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--clr-text-dim)', borderRadius: 2, transition: 'color var(--transition-fast)', padding: 0 }}
                                onMouseEnter={e => e.currentTarget.style.color = 'var(--clr-red)'}
                                onMouseLeave={e => e.currentTarget.style.color = 'var(--clr-text-dim)'}
                            >
                                <Trash2 size={11} strokeWidth={2} />
                            </button>
                        )}
                    </div>
                </div>
            </div>
        );
    };

    // ── Panel ──────────────────────────────────────────────────────────────

    return (
        <>
            {/* Trigger Button */}
            <button
                onClick={() => setIsOpen(!isOpen)}
                className="btn btn--ghost"
                title="主题管理器"
                id="btn-theme-manager"
                style={{ position: 'relative' }}
            >
                <Palette size={13} />
            </button>

            {/* Backdrop */}
            {isOpen && (
                <div
                    style={{ position: 'fixed', inset: 0, zIndex: 8000 }}
                    onClick={() => setIsOpen(false)}
                />
            )}

            {/* Panel */}
            {isOpen && (
                <div
                    style={{
                        position: 'fixed',
                        top: '52px',  // below header
                        right: '12px',
                        width: '320px',
                        maxHeight: '70vh',
                        background: 'var(--clr-surface)',
                        border: '1px solid var(--clr-border-bright)',
                        borderTop: '3px solid var(--clr-gold)',
                        borderRadius: 'var(--radius)',
                        boxShadow: '0 16px 48px rgba(0,0,0,0.5)',
                        zIndex: 8001,
                        display: 'flex',
                        flexDirection: 'column',
                        overflow: 'hidden',
                    }}
                    onClick={e => e.stopPropagation()}
                >
                    {/* Panel Header */}
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 14px', borderBottom: '1px solid var(--clr-border)', background: 'var(--clr-void)' }}>
                        <div>
                            <div style={{ fontFamily: 'var(--font-display)', fontSize: '12px', fontWeight: 700, letterSpacing: '0.12em', textTransform: 'uppercase', color: 'var(--clr-gold)' }}>
                                THEME MANAGER
                            </div>
                            <div style={{ fontFamily: 'var(--font-mono)', fontSize: '9px', color: 'var(--clr-text-dim)', letterSpacing: '0.1em' }}>
                                {allThemes.length} 个主题已安装
                            </div>
                        </div>
                        <button onClick={() => setIsOpen(false)} className="btn btn--ghost" style={{ padding: '4px', border: 'none' }}>
                            <X size={13} />
                        </button>
                    </div>

                    {/* Theme List */}
                    <div style={{ flex: 1, overflowY: 'auto', padding: '10px', display: 'flex', flexDirection: 'column', gap: 6 }}>
                        {allThemes.map(theme => <ThemeCard key={theme.id} theme={theme} />)}
                    </div>

                    {/* Install Drop Zone */}
                    <div
                        onDragOver={e => { e.preventDefault(); setDragOver(true); }}
                        onDragLeave={() => setDragOver(false)}
                        onDrop={handleDrop}
                        style={{
                            margin: '0 10px 10px',
                            border: `1px dashed ${dragOver ? 'var(--clr-gold)' : 'var(--clr-border)'}`,
                            borderRadius: 'var(--radius)',
                            padding: '10px',
                            textAlign: 'center',
                            background: dragOver ? 'var(--clr-gold-ghost)' : 'transparent',
                            transition: 'all var(--transition-fast)',
                            cursor: 'pointer',
                        }}
                        onClick={() => fileInputRef.current?.click()}
                    >
                        <Upload size={14} style={{ color: 'var(--clr-text-dim)', marginBottom: 4 }} />
                        <div style={{ fontFamily: 'var(--font-display)', fontSize: '10px', color: 'var(--clr-text-dim)', letterSpacing: '0.1em', textTransform: 'uppercase' }}>
                            拖入或点击安装 .tptheme
                        </div>
                        <input
                            ref={fileInputRef}
                            type="file"
                            accept=".tptheme"
                            style={{ display: 'none' }}
                            onChange={e => handleFileRead(e.target.files[0])}
                        />
                    </div>

                    {/* Footer hint */}
                    <div style={{ padding: '6px 14px 10px', fontFamily: 'var(--font-mono)', fontSize: '9px', color: 'var(--clr-text-mute)', letterSpacing: '0.08em', borderTop: '1px solid var(--clr-border)' }}>
                        双击 .tptheme 文件可直接安装
                    </div>
                </div>
            )}
        </>
    );
}
