import { GridGenerator } from 'react-hexgrid';

export const GOAL_PALETTE = [
    '#5B8FCC', // Steel Blue
    '#C9A84C', // Antique Gold
    '#C0697A', // Dusty Rose
    '#5B9E72', // Sage Green
    '#8B6BAE', // Dusty Lavender
    '#C87D5A', // Burnt Sienna
    '#4A9DA8', // Steel Teal
    '#8A8A8A', // Stone Grey
];

// Default 4×3 rectangle grid positions (matches DecadePlan canvas layout)
export const DEFAULT_HEX_POSITIONS = GridGenerator.rectangle(4, 3);

export function makeGoal({ title = '新目标', color, order, q, r, s } = {}) {
    const pos = DEFAULT_HEX_POSITIONS[order] ?? { q: 0, r: 0, s: 0 };
    return {
        id:        crypto.randomUUID(),
        title,
        color:     color ?? GOAL_PALETTE[order % GOAL_PALETTE.length],
        note:      '',
        icon:      '',
        q:         q ?? pos.q,
        r:         r ?? pos.r,
        s:         s ?? pos.s,
        order:     order ?? 0,
        updatedAt: Date.now(),
        deletedAt: 0,
    };
}

export async function upsertGoal(db, goalData) {
    await db.goals.upsert({ ...goalData, updatedAt: Date.now() });
}

export async function deleteGoal(db, id) {
    const doc = await db.goals.findOne(id).exec();
    if (doc) await doc.update({ $set: { deletedAt: Date.now(), updatedAt: Date.now() } });
}
