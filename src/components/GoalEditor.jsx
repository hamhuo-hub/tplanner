import { useState, useMemo, useRef } from 'react';
import { marked } from 'marked';

import { GOAL_PALETTE as PALETTE } from '../utils/goalUtils';

export default function GoalEditor({ goal, onSave }) {
    const [title,   setTitle]   = useState(goal?.title ?? '');
    const [color,   setColor]   = useState(goal?.color ?? PALETTE[0]);
    const [note,    setNote]    = useState(goal?.note  ?? '');
    const [editing, setEditing] = useState(false);
    const textareaRef           = useRef(null);

    const rendered = useMemo(() => {
        const result = marked.parse(note || '', { async: false, breaks: true });
        return typeof result === 'string' ? result : '';
    }, [note]);

    return (
        <div style={{
            flex:          1,
            display:       'flex',
            flexDirection: 'column',
            gap:           0,
            height:        '100%',
            overflow:      'hidden',
            fontFamily:    'system-ui, -apple-system, sans-serif',
        }}>

            {/* ── Title ──────────────────────────────────────────────────── */}
            <input
                value={title}
                onChange={e => { setTitle(e.target.value); onSave?.({ title: e.target.value }); }}
                placeholder="目标标题…"
                style={{
                    background:    'transparent',
                    border:        'none',
                    outline:       'none',
                    color:         'rgba(255,255,255,0.92)',
                    fontSize:      22,
                    fontWeight:    700,
                    letterSpacing: '-0.01em',
                    padding:       '20px 24px 12px',
                    width:         '100%',
                    fontFamily:    'inherit',
                }}
            />

            {/* ── Color selector ─────────────────────────────────────────── */}
            <div style={{
                display:    'flex',
                alignItems: 'center',
                gap:        10,
                padding:    '0 24px 16px',
            }}>
                {PALETTE.map(c => (
                    <button
                        key={c}
                        onClick={() => { setColor(c); onSave?.({ color: c }); }}
                        style={{
                            width:        28,
                            height:       28,
                            borderRadius: '50%',
                            background:   c,
                            border:       color === c
                                ? '2px solid rgba(255,255,255,0.9)'
                                : '2px solid rgba(255,255,255,0.12)',
                            cursor:       'pointer',
                            flexShrink:   0,
                            boxShadow:    color === c
                                ? `0 0 0 2px ${c}55`
                                : 'none',
                            transition:   'border 150ms, box-shadow 150ms',
                            padding:      0,
                        }}
                    />
                ))}

            </div>

            {/* ── Divider ────────────────────────────────────────────────── */}
            <div style={{
                height:     1,
                margin:     '0 24px',
                background: 'rgba(255,255,255,0.08)',
                flexShrink: 0,
            }} />

            {/* ── Markdown editor: 聚焦→原文编辑，失焦→渲染预览 ──────────── */}
            <div style={{
                flex:     1,
                overflow: 'hidden',
                margin:   '0 8px 8px',
            }}>
                {editing ? (
                    <textarea
                        ref={textareaRef}
                        autoFocus
                        value={note}
                        onChange={e => { setNote(e.target.value); onSave?.({ note: e.target.value }); }}
                        onBlur={() => setEditing(false)}
                        spellCheck={false}
                        style={{
                            display:    'block',
                            width:      '100%',
                            height:     '100%',
                            padding:    '14px 16px',
                            fontSize:   13,
                            lineHeight: 1.75,
                            fontFamily: 'IBM Plex Mono, monospace',
                            background: 'transparent',
                            border:     'none',
                            outline:    'none',
                            resize:     'none',
                            color:      'rgba(255,255,255,0.75)',
                            caretColor: 'rgba(255,255,255,0.9)',
                            overflowY:  'auto',
                        }}
                    />
                ) : (
                    <div
                        className="goal-md-preview"
                        onClick={() => {
                            setEditing(true);
                        }}
                        dangerouslySetInnerHTML={{
                            __html: rendered || '<span style="color:rgba(255,255,255,0.18)">点击开始写作…</span>',
                        }}
                        style={{
                            height:     '100%',
                            padding:    '14px 16px',
                            fontSize:   13,
                            lineHeight: 1.75,
                            color:      'rgba(255,255,255,0.82)',
                            overflowY:  'auto',
                            cursor:     'text',
                            wordBreak:  'break-word',
                        }}
                    />
                )}
            </div>

            <style>{`
                .goal-md-preview h1,
                .goal-md-preview h2,
                .goal-md-preview h3 {
                    color: rgba(255,255,255,0.95);
                    font-weight: 700;
                    margin: 10px 0 4px;
                    letter-spacing: -0.01em;
                }
                .goal-md-preview h1 { font-size: 18px; }
                .goal-md-preview h2 { font-size: 15px; }
                .goal-md-preview h3 { font-size: 13px; }
                .goal-md-preview p  { margin: 4px 0; }
                .goal-md-preview ul,
                .goal-md-preview ol { padding-left: 18px; margin: 4px 0; }
                .goal-md-preview li { margin: 2px 0; }
                .goal-md-preview strong { color: rgba(255,255,255,0.95); font-weight: 700; }
                .goal-md-preview em    { color: rgba(255,255,255,0.6); }
                .goal-md-preview blockquote {
                    border-left: 3px solid rgba(255,255,255,0.2);
                    padding-left: 10px;
                    color: rgba(255,255,255,0.45);
                    margin: 6px 0;
                }
                .goal-md-preview code {
                    background: rgba(255,255,255,0.08);
                    border-radius: 3px;
                    padding: 1px 5px;
                    font-size: 12px;
                    font-family: 'IBM Plex Mono', monospace;
                }
            `}</style>
        </div>
    );
}
