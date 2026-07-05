import { useRef, useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Layout, Hexagon } from 'react-hexgrid';
import { Trash2 } from 'lucide-react';
import GoalEditor from './GoalEditor';
import { pixelToHex } from '../utils/goalUtils';

const SIZE = 55;

export default function DecadePlan({ goals = [], selectedId, onSelect, onAddGoal, onUpdateGoal, onDeleteGoal }) {
    const svgRef       = useRef(null);
    const containerRef = useRef(null);

    // ── transform (pan + zoom) ───────────────────────────────────────────
    const [xfm, setXfm]          = useState({ x: 0, y: 0, scale: 1 });
    const xfmRef                  = useRef(xfm);
    useEffect(() => { xfmRef.current = xfm; }, [xfm]);

    // ── canvas pan ───────────────────────────────────────────────────────
    const panRef     = useRef(null);   // { startX, startY, tx, ty }
    const didMoveRef = useRef(false);

    // ── goal drag ────────────────────────────────────────────────────────
    const goalDragRef    = useRef(null);  // { goalId, origQ, origR, origS, moved }
    const previewPosRef  = useRef(null);  // latest { q,r,s } while dragging
    const [dragPreview, setDragPreview] = useState(null); // { q,r,s } | null

    // ── context menu ─────────────────────────────────────────────────────
    const [ctxMenu, setCtxMenu] = useState(null); // { x, y, goalId }

    // ── cursor state ─────────────────────────────────────────────────────
    const [cursor, setCursor] = useState('crosshair');

    // ── stable prop refs (for window-level closures) ─────────────────────
    const onAddGoalRef    = useRef(onAddGoal);
    const onUpdateGoalRef = useRef(onUpdateGoal);
    const onSelectRef     = useRef(onSelect);
    const selectedIdRef   = useRef(selectedId);
    const goalsRef        = useRef(goals);
    useEffect(() => { onAddGoalRef.current    = onAddGoal;    }, [onAddGoal]);
    useEffect(() => { onUpdateGoalRef.current = onUpdateGoal; }, [onUpdateGoal]);
    useEffect(() => { onSelectRef.current     = onSelect;     }, [onSelect]);
    useEffect(() => { selectedIdRef.current   = selectedId;   }, [selectedId]);
    useEffect(() => { goalsRef.current        = goals;        }, [goals]);

    const occupied = (q, r, excludeId) =>
        goalsRef.current.some(g => g.id !== excludeId && g.q === q && g.r === r);

    const selectedGoal = goals.find(g => g.id === selectedId) ?? null;

    // ── helpers ──────────────────────────────────────────────────────────
    const screenToHex = (clientX, clientY) => {
        const svg = svgRef.current;
        if (!svg) return null;
        const pt = svg.createSVGPoint();
        pt.x = clientX; pt.y = clientY;
        const sp = pt.matrixTransform(svg.getScreenCTM().inverse());
        const t  = xfmRef.current;
        return pixelToHex((sp.x - t.x) / t.scale, (sp.y - t.y) / t.scale, SIZE);
    };

    // ── center on first render ───────────────────────────────────────────
    useEffect(() => {
        if (!containerRef.current) return;
        const { width, height } = containerRef.current.getBoundingClientRect();
        setXfm({ x: width / 2, y: height / 2, scale: 1 });
    }, []);

    // ── wheel zoom (non-passive) ─────────────────────────────────────────
    useEffect(() => {
        const el = svgRef.current;
        if (!el) return;
        const onWheel = (e) => {
            e.preventDefault();
            const factor = e.deltaY < 0 ? 1.1 : 0.9;
            const pt = el.createSVGPoint();
            pt.x = e.clientX; pt.y = e.clientY;
            const sp = pt.matrixTransform(el.getScreenCTM().inverse());
            setXfm(prev => {
                const ns = Math.max(0.15, Math.min(6, prev.scale * factor));
                const r  = ns / prev.scale;
                return { x: sp.x + (prev.x - sp.x) * r, y: sp.y + (prev.y - sp.y) * r, scale: ns };
            });
        };
        el.addEventListener('wheel', onWheel, { passive: false });
        return () => el.removeEventListener('wheel', onWheel);
    }, []);

    // ── global mousemove / mouseup ────────────────────────────────────────
    useEffect(() => {
        const onMove = (e) => {
            if (goalDragRef.current) {
                // drag goal
                goalDragRef.current.moved = true;
                const hex = screenToHex(e.clientX, e.clientY);
                if (hex) { previewPosRef.current = hex; setDragPreview(hex); }
            } else if (panRef.current) {
                // pan canvas
                const dx = e.clientX - panRef.current.startX;
                const dy = e.clientY - panRef.current.startY;
                if (Math.abs(dx) > 4 || Math.abs(dy) > 4) didMoveRef.current = true;
                setXfm({ x: panRef.current.tx + dx, y: panRef.current.ty + dy, scale: xfmRef.current.scale });
            }
        };

        const onUp = () => {
            if (goalDragRef.current) {
                const { goalId, origQ, origR, origS, moved } = goalDragRef.current;
                const pos = previewPosRef.current;
                if (moved && pos && !occupied(pos.q, pos.r, goalId)) {
                    onUpdateGoalRef.current?.(goalId, { q: pos.q, r: pos.r, s: pos.s });
                } else if (!moved) {
                    // click → select / deselect
                    onSelectRef.current?.(goalId === selectedIdRef.current ? null : goalId);
                }
                goalDragRef.current   = null;
                previewPosRef.current = null;
                setDragPreview(null);
            } else if (panRef.current) {
                panRef.current = null;
            }
            setCursor('crosshair');
        };

        window.addEventListener('mousemove', onMove);
        window.addEventListener('mouseup',   onUp);
        return () => {
            window.removeEventListener('mousemove', onMove);
            window.removeEventListener('mouseup',   onUp);
        };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // ── SVG event handlers ───────────────────────────────────────────────
    const handleSvgMouseDown = (e) => {
        if (e.button !== 0) return;
        if (e.target.closest('g[data-goal]')) return; // goal handles its own mousedown
        didMoveRef.current = false;
        panRef.current = { startX: e.clientX, startY: e.clientY, tx: xfmRef.current.x, ty: xfmRef.current.y };
        setCursor('grabbing');
    };

    const handleSvgClick = (e) => {
        if (didMoveRef.current) return;
        if (e.target.closest('g[data-goal]')) return;
        const hex = screenToHex(e.clientX, e.clientY);
        if (!hex) return;
        if (occupied(hex.q, hex.r, null)) return;
        onAddGoalRef.current?.(hex);
    };

    const handleGoalMouseDown = (e, goal) => {
        if (e.button !== 0) return;
        e.stopPropagation();
        goalDragRef.current = { goalId: goal.id, origQ: goal.q, origR: goal.r, origS: goal.s, moved: false };
        previewPosRef.current = { q: goal.q, r: goal.r, s: goal.s };
        setCursor('grabbing');
    };

    const handleGoalContextMenu = (e, goal) => {
        e.preventDefault();
        e.stopPropagation();
        setCtxMenu({ x: e.clientX, y: e.clientY, goalId: goal.id });
    };

    return (
        <div style={{
            flex: 1, display: 'flex', gap: 12, padding: 12,
            minHeight: 0,
            background: 'linear-gradient(145deg, #0b0b12 0%, #11111c 100%)',
            borderRadius: 14, overflow: 'hidden',
        }}>
            {/* ── Left: free-form canvas ───────────────────────────────── */}
            <div ref={containerRef} style={{
                flex: 1, borderRadius: 12, position: 'relative', overflow: 'hidden',
                background: 'rgba(255,255,255,0.025)',
                border: '1px solid rgba(255,255,255,0.07)',
                cursor,
            }}>
                <div style={{
                    position: 'absolute', inset: 0, borderRadius: 12, pointerEvents: 'none',
                    backgroundImage: 'radial-gradient(circle, rgba(255,255,255,0.11) 1.5px, transparent 1.5px)',
                    backgroundSize: '28px 28px',
                }} />

                <svg
                    ref={svgRef}
                    width="100%" height="100%"
                    style={{ display: 'block' }}
                    onMouseDown={handleSvgMouseDown}
                    onClick={handleSvgClick}
                >
                    <g transform={`translate(${xfm.x},${xfm.y}) scale(${xfm.scale})`}>
                        <Layout size={{ x: SIZE, y: SIZE }} flat={false} spacing={0.95} origin={{ x: 0, y: 0 }}>

                            {/* Drag preview ghost */}
                            {dragPreview && goalDragRef.current && (() => {
                                const canDrop = !occupied(dragPreview.q, dragPreview.r, goalDragRef.current.goalId);
                                const dragged = goalsRef.current.find(g => g.id === goalDragRef.current.goalId);
                                return (
                                    <Hexagon
                                        key="drag-preview"
                                        q={dragPreview.q} r={dragPreview.r} s={dragPreview.s}
                                        cellStyle={{
                                            fill:            canDrop ? (dragged?.color ?? 'rgba(255,255,255,0.15)') : 'rgba(255,60,60,0.25)',
                                            stroke:          canDrop ? 'rgba(255,255,255,0.6)' : 'rgba(255,80,80,0.6)',
                                            strokeWidth:     1.5,
                                            strokeDasharray: '5 3',
                                            opacity:         0.6,
                                        }}
                                    />
                                );
                            })()}

                            {/* Goals */}
                            {goals.map(goal => {
                                const isBeingDragged = goalDragRef.current?.goalId === goal.id && goalDragRef.current?.moved;
                                return (
                                    <Hexagon
                                        key={goal.id}
                                        q={goal.q} r={goal.r} s={goal.s}
                                        data-goal={goal.id}
                                        className="hexagon-goal"
                                        onMouseDown={e => handleGoalMouseDown(e, goal)}
                                        onContextMenu={e => handleGoalContextMenu(e, goal)}
                                        onClick={e => e.stopPropagation()}
                                        cellStyle={{
                                            fill:        goal.color,
                                            stroke:      selectedId === goal.id
                                                ? 'rgba(255,255,255,0.85)' : 'rgba(255,255,255,0.15)',
                                            strokeWidth: selectedId === goal.id ? 2.5 : 1,
                                            opacity:     isBeingDragged ? 0.35 : 1,
                                            transition:  'opacity 0.15s, stroke 0.2s',
                                            cursor:      'grab',
                                        }}
                                    >
                                        <text x="0" y="-18" textAnchor="middle" dominantBaseline="middle"
                                            style={{ fontSize: 22, fontWeight: 800, fill: 'rgba(255,255,255,0.95)',
                                                fontFamily: 'system-ui,sans-serif', userSelect: 'none', pointerEvents: 'none' }}>
                                            {String(goal.order + 1).padStart(2, '0')}
                                        </text>
                                        <line x1="-38" y1="-4" x2="38" y2="-4"
                                            stroke="rgba(255,255,255,0.2)" strokeWidth="0.8" />
                                        <text x="0" y="18" textAnchor="middle" dominantBaseline="middle"
                                            style={{ fontSize: 9, fontWeight: 600, fill: 'rgba(255,255,255,0.78)',
                                                fontFamily: 'system-ui,sans-serif', letterSpacing: '0.02em',
                                                userSelect: 'none', pointerEvents: 'none' }}>
                                            {goal.title || t('decade.goalFallback', { n: goal.order + 1 })}
                                        </text>
                                    </Hexagon>
                                );
                            })}
                        </Layout>
                    </g>
                </svg>
            </div>

            {/* ── Goal context menu ────────────────────────────────────── */}
            {ctxMenu && (
                <GoalContextMenu
                    x={ctxMenu.x} y={ctxMenu.y}
                    onClose={() => setCtxMenu(null)}
                    onDelete={() => {
                        onDeleteGoal?.(ctxMenu.goalId);
                        setCtxMenu(null);
                    }}
                />
            )}

            {/* ── Right: Goal editor ───────────────────────────────────── */}
            <div style={{
                flex: 1, borderRadius: 12, overflow: 'hidden',
                background: 'rgba(255,255,255,0.025)',
                border: '1px solid rgba(255,255,255,0.07)',
                display: 'flex', flexDirection: 'column',
            }}>
                <GoalEditor
                    key={selectedGoal?.id ?? 'empty'}
                    goal={selectedGoal}
                    onSave={patch => selectedGoal && onUpdateGoal?.(selectedGoal.id, patch)}
                />
            </div>
        </div>
    );
}

function GoalContextMenu({ x, y, onClose, onDelete }) {
    const { t } = useTranslation();
    const ref = useRef(null);

    useEffect(() => {
        const onDown = (e) => { if (!ref.current?.contains(e.target)) onClose(); };
        const onKey  = (e) => { if (e.key === 'Escape') onClose(); };
        document.addEventListener('mousedown', onDown);
        document.addEventListener('keydown',   onKey);
        return () => {
            document.removeEventListener('mousedown', onDown);
            document.removeEventListener('keydown',   onKey);
        };
    }, [onClose]);

    const W = 140, H = 44;
    const left = x + W > window.innerWidth  ? x - W : x;
    const top  = y + H > window.innerHeight ? y - H : y;

    return (
        <div ref={ref} style={{
            position: 'fixed', left, top, zIndex: 9999,
            background: '#161620',
            border: '1px solid rgba(255,255,255,0.10)',
            borderRadius: 8,
            padding: '4px 0',
            minWidth: W,
            boxShadow: '0 8px 24px rgba(0,0,0,0.6)',
            fontFamily: 'system-ui,-apple-system,sans-serif',
        }}>
            <button
                onClick={onDelete}
                style={{
                    display: 'flex', alignItems: 'center', gap: 8,
                    width: '100%', padding: '9px 14px',
                    background: 'none', border: 'none', cursor: 'pointer',
                    color: '#e05555', fontSize: 13, textAlign: 'left',
                    fontFamily: 'inherit', borderRadius: 6,
                }}
                onMouseEnter={e => e.currentTarget.style.background = 'rgba(224,85,85,0.10)'}
                onMouseLeave={e => e.currentTarget.style.background = 'none'}
            >
                <Trash2 size={14} />
                {t('decade.deleteGoal')}
            </button>
        </div>
    );
}
