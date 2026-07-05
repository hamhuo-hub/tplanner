import { useState, useMemo, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { marked, Renderer } from 'marked';
import { Maximize2, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';

/* 自定义 renderer：去掉 checkbox 上的 disabled，使其可点击 */
const renderer = new Renderer();
renderer.checkbox = ({ checked }) =>
    `<input type="checkbox" ${checked ? 'checked' : ''} style="cursor:pointer;margin-right:6px">`;

/**
 * 点击预览区的 checkbox 时，切换 markdown 原文对应行的 [ ] ↔ [x]
 * container: 预览 div 元素; value: 当前 markdown 文本
 * 返回更新后的文本，若点击对象不是 checkbox 则返回 null
 */
function toggleCheckbox(e, container, value) {
    if (e.target.type !== 'checkbox') return null;
    e.preventDefault(); // 阻止浏览器默认行为（我们手动更新）

    const boxes = [...container.querySelectorAll('input[type="checkbox"]')];
    const idx   = boxes.indexOf(e.target);
    if (idx === -1) return null;

    const taskRe = /^(\s*[-*+] \[)([ xX])(\].*)/;
    let count = 0;
    const lines = value.split('\n').map(line => {
        if (taskRe.test(line)) {
            if (count === idx) {
                count++;
                const checked = RegExp.$2.trim().toLowerCase() === 'x';
                return line.replace(taskRe, (_, pre, _ch, post) =>
                    `${pre}${checked ? ' ' : 'x'}${post}`
                );
            }
            count++;
        }
        return line;
    });
    return lines.join('\n');
}

const BORDER_DIM  = '1px solid rgba(255,255,255,0.12)';
const BORDER_EDIT = '1px solid rgba(201,168,76,0.5)';
const BG          = 'rgba(255,255,255,0.04)';
const FONT        = 'IBM Plex Mono, monospace';

const previewStyle = {
    minHeight: 90,
    maxHeight: 240,
    overflowY: 'auto',
    padding: '10px 36px 10px 14px',
    background: BG,
    border: BORDER_DIM,
    borderRadius: 4,
    fontSize: 13,
    lineHeight: 1.65,
    color: '#E0D8C8',
    cursor: 'text',
    wordBreak: 'break-word',
};

const textareaStyle = {
    display: 'block',
    width: '100%',
    minHeight: 90,
    maxHeight: 240,
    overflowY: 'auto',
    padding: '10px 36px 10px 14px',
    background: BG,
    border: BORDER_EDIT,
    borderRadius: 4,
    fontFamily: FONT,
    fontSize: 13,
    lineHeight: 1.65,
    color: '#E0D8C8',
    caretColor: '#C9A84C',
    resize: 'none',
    outline: 'none',
};

const expandBtnStyle = {
    position: 'absolute',
    top: 6,
    right: 6,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: 22,
    height: 22,
    background: 'rgba(201,168,76,0.12)',
    border: '1px solid rgba(201,168,76,0.25)',
    borderRadius: 3,
    cursor: 'pointer',
    color: '#6B5928',
    padding: 0,
    transition: 'color 120ms, background 120ms',
};

/**
 * NoteEditor — 便签式 MD 编辑器（行内切换 + 全屏左右分栏）
 *
 * Props:
 *   value      string          当前值
 *   onChange   (val) => void   每次按键即时更新（受控）
 *   onCommit   (val) => void   失焦 / 关闭全屏时保存（可选）
 *   placeholder string
 */
export default function NoteEditor({ value = '', onChange, onCommit, placeholder }) {
    const { t } = useTranslation();
    const ph = placeholder ?? t('event.notePlaceholder');

    const [editing, setEditing]       = useState(false);
    const [fullscreen, setFullscreen] = useState(false);

    const rendered = useMemo(() => {
        const r = marked.parse(value || '', { async: false, breaks: true, renderer });
        return typeof r === 'string' ? r : '';
    }, [value]);

    const handlePreviewClick = (e) => {
        const newVal = toggleCheckbox(e, e.currentTarget, value);
        if (newVal !== null) {
            onChange?.(newVal);
            onCommit?.(newVal);
        }
    };

    const placeholderHtml = `<span style="color:#3A342A">${ph}</span>`;

    const handleBlur = () => {
        setEditing(false);
        onCommit?.(value);
    };

    const openFullscreen = () => setFullscreen(true);

    const closeFullscreen = () => {
        setFullscreen(false);
        setEditing(false);
        onCommit?.(value);
    };

    /* ESC 关闭全屏 — capture 阶段拦截，防止 MUI Dialog 先消费 */
    useEffect(() => {
        if (!fullscreen) return;
        const onKey = (e) => {
            if (e.key === 'Escape') {
                e.stopPropagation();
                closeFullscreen();
            }
        };
        document.addEventListener('keydown', onKey, true);
        return () => document.removeEventListener('keydown', onKey, true);
    }, [fullscreen, value]);

    /* ── Fullscreen overlay（portal 到 body，绕开所有 stacking context）── */
    const fullscreenNode = fullscreen && createPortal(
        <div
            style={{
                position: 'fixed', inset: 0, zIndex: 99999,
                display: 'flex', flexDirection: 'column',
                background: 'var(--clr-bg)',
                fontFamily: FONT,
            }}
            /* 阻止点击冒泡到 React 树上层的 modal onClose */
            onClick={e => e.stopPropagation()}
        >
            {/* Header */}
            <div style={{
                display: 'flex', alignItems: 'center',
                height: 40, padding: '0 16px', gap: 12,
                borderBottom: '1px solid var(--clr-border)',
                background: 'var(--clr-void)',
                flexShrink: 0,
            }}>
                <span style={{
                    fontSize: 10, letterSpacing: '0.18em', textTransform: 'uppercase',
                    color: 'var(--clr-gold-dim)', fontFamily: 'var(--font-display)',
                }}>
                    {t('event.note')}
                </span>

                <span style={{ fontSize: 10, color: 'var(--clr-text-mute)', letterSpacing: '0.08em' }}>
                    {t('note.editorLabel')}
                </span>

                <div style={{ flex: 1 }} />

                <button
                    onClick={closeFullscreen}
                    style={{
                        background: 'none', border: 'none', cursor: 'pointer',
                        color: 'var(--clr-text-dim)', display: 'flex',
                        alignItems: 'center', padding: 4,
                    }}
                    title={t('note.closeFullscreen')}
                >
                    <X size={16} />
                </button>
            </div>

            {/* Body — 左右分栏 */}
            <div style={{ flex: 1, overflow: 'hidden', display: 'flex' }}>
                {/* 左：原文编辑 */}
                <textarea
                    autoFocus
                    value={value}
                    onChange={e => onChange?.(e.target.value)}
                    spellCheck={false}
                    style={{
                        flex: 1,
                        height: '100%',
                        padding: '20px 28px',
                        background: 'transparent',
                        borderRight: '1px solid var(--clr-border)',
                        fontFamily: FONT,
                        fontSize: 14,
                        lineHeight: 1.75,
                        color: '#E0D8C8',
                        caretColor: '#C9A84C',
                        resize: 'none',
                        outline: 'none',
                        border: 'none',
                        borderRight: '1px solid var(--clr-border)',
                    }}
                />

                {/* 右：MD 渲染预览（checkbox 可点击） */}
                <div
                    className="journal-md-preview"
                    onClick={handlePreviewClick}
                    style={{
                        flex: 1,
                        height: '100%',
                        overflowY: 'auto',
                        padding: '20px 28px',
                        fontSize: 14,
                        lineHeight: 1.75,
                        color: '#E0D8C8',
                        wordBreak: 'break-word',
                    }}
                    dangerouslySetInnerHTML={{ __html: value ? rendered : placeholderHtml }}
                />
            </div>
        </div>,
        document.body
    );

    /* ── Inline editor ────────────────────────────────────────────────── */
    return (
        <>
            {fullscreenNode}

            <div style={{ position: 'relative' }}>
                {editing ? (
                    <textarea
                        autoFocus
                        value={value}
                        onChange={e => onChange?.(e.target.value)}
                        onBlur={handleBlur}
                        spellCheck={false}
                        style={textareaStyle}
                    />
                ) : (
                    <div
                        className="journal-md-preview"
                        onClick={e => {
                            const newVal = toggleCheckbox(e, e.currentTarget, value);
                            if (newVal !== null) {
                                onChange?.(newVal);
                                onCommit?.(newVal);
                            } else {
                                setEditing(true);
                            }
                        }}
                        style={previewStyle}
                        dangerouslySetInnerHTML={{ __html: value ? rendered : placeholderHtml }}
                    />
                )}

                {/* 全屏按钮 — preventDefault 防止点击时触发 textarea blur */}
                <button
                    onMouseDown={e => e.preventDefault()}
                    onClick={openFullscreen}
                    style={expandBtnStyle}
                    title={t('note.fullscreen')}
                >
                    <Maximize2 size={12} />
                </button>
            </div>
        </>
    );
}
