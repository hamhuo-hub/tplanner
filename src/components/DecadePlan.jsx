import { HexGrid, Layout, Hexagon } from 'react-hexgrid';
import GoalEditor from './GoalEditor';

const SIZE = 55;

// goals: array of { id, title, color, note, q, r, s, order }
export default function DecadePlan({ goals = [], selectedId, onSelect }) {
    return (
        <div style={{
            flex:         1,
            display:      'flex',
            gap:          12,
            padding:      12,
            minHeight:    0,
            background:   'linear-gradient(145deg, #0b0b12 0%, #11111c 100%)',
            borderRadius: 14,
            overflow:     'hidden',
        }}>

            {/* ── Left panel: Goal canvas ────────────────────────────────── */}
            <div style={{
                flex:           1,
                borderRadius:   12,
                background:     'rgba(255,255,255,0.025)',
                border:         '1px solid rgba(255,255,255,0.07)',
                position:       'relative',
                overflow:       'auto',
                display:        'flex',
                alignItems:     'center',
                justifyContent: 'center',
            }}>
                {/* Dot grid */}
                <div style={{
                    position:        'absolute',
                    inset:           0,
                    backgroundImage: 'radial-gradient(circle, rgba(255,255,255,0.11) 1.5px, transparent 1.5px)',
                    backgroundSize:  '28px 28px',
                    pointerEvents:   'none',
                    borderRadius:    12,
                }} />

                {goals.length === 0 ? null : (
                    <HexGrid
                        width="90%"
                        height="90%"
                        viewBox="-80 -75 500 360"
                        style={{ overflow: 'visible' }}
                    >
                        <Layout
                            size={{ x: SIZE, y: SIZE }}
                            flat={false}
                            spacing={0.95}
                            origin={{ x: 0, y: 0 }}
                        >
                            {goals.map(goal => (
                                <Hexagon
                                    key={goal.id}
                                    q={goal.q} r={goal.r} s={goal.s}
                                    onClick={() => onSelect?.(goal.id)}
                                    cellStyle={{
                                        fill:        goal.color,
                                        stroke:      selectedId === goal.id
                                            ? 'rgba(255,255,255,0.8)'
                                            : 'rgba(255,255,255,0.15)',
                                        strokeWidth: selectedId === goal.id ? 2 : 1,
                                        filter:      'drop-shadow(0px 8px 16px rgba(0,0,0,0.4))',
                                        transition:  'all 0.3s ease',
                                        cursor:      'pointer',
                                    }}
                                >
                                    <text
                                        x="0" y="-18"
                                        textAnchor="middle"
                                        dominantBaseline="middle"
                                        style={{
                                            fontSize:   22,
                                            fontWeight: 800,
                                            fill:       'rgba(255,255,255,0.95)',
                                            fontFamily: 'system-ui,-apple-system,sans-serif',
                                            userSelect: 'none',
                                        }}
                                    >
                                        {String(goal.order + 1).padStart(2, '0')}
                                    </text>

                                    <line
                                        x1="-38" y1="-4" x2="38" y2="-4"
                                        stroke="rgba(255,255,255,0.2)"
                                        strokeWidth="0.8"
                                    />

                                    <text
                                        x="0" y="18"
                                        textAnchor="middle"
                                        dominantBaseline="middle"
                                        style={{
                                            fontSize:      9,
                                            fontWeight:    600,
                                            fill:          'rgba(255,255,255,0.78)',
                                            fontFamily:    'system-ui,-apple-system,sans-serif',
                                            letterSpacing: '0.02em',
                                            userSelect:    'none',
                                        }}
                                    >
                                        {goal.title || `目标 ${goal.order + 1}`}
                                    </text>
                                </Hexagon>
                            ))}
                        </Layout>
                    </HexGrid>
                )}
            </div>

            {/* ── Right panel: Goal editor ──────────────────────────────── */}
            <div style={{
                flex:          1,
                borderRadius:  12,
                background:    'rgba(255,255,255,0.025)',
                border:        '1px solid rgba(255,255,255,0.07)',
                overflow:      'hidden',
                display:       'flex',
                flexDirection: 'column',
            }}>
                <GoalEditor
                    goal={goals.find(g => g.id === selectedId) ?? null}
                />
            </div>
        </div>
    );
}
