import { useState, useMemo, useEffect, useRef } from 'react';
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

/** Shared rendered Markdown and plain-text placeholder for notes and journals. */
export function MarkdownPreview({ html, placeholder, ...props }) {
    return html
        ? <div {...props} dangerouslySetInnerHTML={{ __html: html }} />
        : <div {...props}><span className="journal-placeholder">{placeholder}</span></div>;
}

/**
 * NoteEditor — 便签式 MD 编辑器（行内切换 + 全屏左右分栏）
 *
 * Props:
 *   value      string          当前值
 *   onChange   (val) => void   每次按键即时更新（受控）
 *   onCommit   (val) => void   失焦 / 关闭全屏时保存（可选）
 *   placeholder string
 *   readOnly   boolean         只展示渲染结果，不允许进入编辑模式
 */
export default function NoteEditor({ value = '', onChange, onCommit, placeholder, readOnly = false }) {
    const { t } = useTranslation();
    const ph = placeholder ?? t('event.notePlaceholder');

    const [editing, setEditing]       = useState(false);
    const [fullscreen, setFullscreen] = useState(false);
    const operationStartRef = useRef(value);
    const operationActiveRef = useRef(false);

    const beginTextOperation = () => {
        if (operationActiveRef.current) return;
        operationStartRef.current = value;
        operationActiveRef.current = true;
    };

    const commitTextOperation = () => {
        if (!operationActiveRef.current) return;
        operationActiveRef.current = false;
        if (value !== operationStartRef.current) onCommit?.(value);
    };

    const rendered = useMemo(() => {
        const r = marked.parse(value || '', {
            async: false,
            breaks: true,
            ...(readOnly ? {} : { renderer }),
        });
        return typeof r === 'string' ? r : '';
    }, [value, readOnly]);

    const handlePreviewClick = (e) => {
        const newVal = toggleCheckbox(e, e.currentTarget, value);
        if (newVal !== null) {
            onChange?.(newVal);
            onCommit?.(newVal);
        }
    };

    const handleBlur = () => {
        setEditing(false);
        commitTextOperation();
    };

    const openFullscreen = () => {
        if (!readOnly) {
            beginTextOperation();
            setFullscreen(true);
        }
    };

    const closeFullscreen = () => {
        setFullscreen(false);
        setEditing(false);
        commitTextOperation();
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
            className="note-editor__fullscreen"
            /* 阻止点击冒泡到 React 树上层的 modal onClose */
            onClick={e => e.stopPropagation()}
        >
            {/* Header */}
            <div className="note-editor__header">
                <span className="note-editor__label">
                    {t('event.note')}
                </span>

                <span className="note-editor__meta">
                    {t('note.editorLabel')}
                </span>

                <div style={{ flex: 1 }} />

                <button
                    type="button"
                    onClick={closeFullscreen}
                    className="note-editor__close"
                    aria-label={t('note.closeFullscreen')}
                    title={t('note.closeFullscreen')}
                >
                    <X size={16} />
                </button>
            </div>

            {/* Body — 左右分栏 */}
            <div className="note-editor__body">
                {/* 左：原文编辑 */}
                <textarea
                    autoFocus
                    value={value}
                    onChange={e => onChange?.(e.target.value)}
                    onFocus={beginTextOperation}
                    onBlur={commitTextOperation}
                    spellCheck={false}
                    className="note-editor__source"
                    aria-label={t('note.editorLabel')}
                />

                {/* 右：MD 渲染预览（checkbox 可点击） */}
                <MarkdownPreview
                    className="journal-md-preview note-editor__rendered"
                    onClick={handlePreviewClick}
                    html={rendered}
                    placeholder={ph}
                />
            </div>
        </div>,
        document.body
    );

    /* ── Inline editor ────────────────────────────────────────────────── */
    return (
        <>
            {fullscreenNode}

            <div className={`note-editor${readOnly ? ' note-editor--readonly' : ''}`}>
                {editing ? (
                    <textarea
                        autoFocus
                        value={value}
                        onChange={e => onChange?.(e.target.value)}
                        onFocus={beginTextOperation}
                        onBlur={handleBlur}
                        spellCheck={false}
                        className="note-editor__textarea"
                        aria-label={t('note.editorLabel')}
                    />
                ) : (
                    <MarkdownPreview
                        className="journal-md-preview note-editor__preview"
                        onClick={e => {
                            if (readOnly) {
                                if (e.target.type === 'checkbox') e.preventDefault();
                                return;
                            }
                            const newVal = toggleCheckbox(e, e.currentTarget, value);
                            if (newVal !== null) {
                                onChange?.(newVal);
                                onCommit?.(newVal);
                            } else {
                                beginTextOperation();
                                setEditing(true);
                            }
                        }}
                        tabIndex={readOnly ? undefined : 0}
                        onKeyDown={e => {
                            if (!readOnly && e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) {
                                e.preventDefault();
                                beginTextOperation();
                                setEditing(true);
                            }
                        }}
                        aria-readonly={readOnly}
                        html={rendered}
                        placeholder={ph}
                    />
                )}

                {/* 全屏按钮 — preventDefault 防止点击时触发 textarea blur */}
                {!readOnly && (
                    <button
                        type="button"
                        onMouseDown={e => e.preventDefault()}
                        onClick={openFullscreen}
                        className="note-editor__expand"
                        aria-label={t('note.fullscreen')}
                        title={t('note.fullscreen')}
                    >
                        <Maximize2 size={12} />
                    </button>
                )}
            </div>
        </>
    );
}
