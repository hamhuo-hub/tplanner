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

// Round fractional hex coordinates to nearest integer hex
function roundHex(q, r, s) {
    let rq = Math.round(q), rr = Math.round(r), rs = Math.round(s);
    const dq = Math.abs(rq - q), dr = Math.abs(rr - r), ds = Math.abs(rs - s);
    if      (dq > dr && dq > ds) rq = -rr - rs;
    else if (dr > ds)             rr = -rq - rs;
    else                          rs = -rq - rr;
    return { q: rq, r: rr, s: rs };
}

// Convert SVG viewBox-space pixel coords → hex grid coords (pointy-top layout)
export function pixelToHex(x, y, size) {
    const q = (Math.sqrt(3) / 3 * x - 1 / 3 * y) / size;
    const r = (2 / 3 * y) / size;
    return roundHex(q, r, -q - r);
}

export function makeGoal({ title = '新目标', color, order = 0, q = 0, r = 0, s } = {}) {
    return {
        id:        crypto.randomUUID(),
        title,
        color:     color ?? GOAL_PALETTE[order % GOAL_PALETTE.length],
        note:      '',
        icon:      '',
        q,
        r,
        s:         s ?? -q - r,
        order,
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
