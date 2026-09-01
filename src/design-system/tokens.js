export const colors = Object.freeze({
    background: '#0E0E0E',
    surface: '#1A1A1A',
    surfaceRaised: '#222222',
    control: '#252525',
    border: '#2D2D2D',
    borderBright: '#383838',
    textPrimary: '#E0D8C8',
    textSecondary: '#7A7163',
    textMuted: '#3A342A',
    textOnAccent: '#0A0A0A',
    gold: '#C9A84C',
    goldBright: '#F0C040',
    goldDark: '#6B5928',
    goldGhost: 'rgba(201,168,76,0.08)',
    goldSubtle: 'rgba(201,168,76,0.06)',
    goldHover: 'rgba(201,168,76,0.15)',
    goldSelected: 'rgba(201,168,76,0.12)',
    goldSelectedHover: 'rgba(201,168,76,0.18)',
    goldGlow: 'rgba(201,168,76,0.30)',
    blue: '#5B8FCC',
    blueBright: '#8BB8E8',
    teal: '#4A9DA8',
    green: '#4A7C59',
    greenGhost: 'rgba(74,124,89,0.20)',
    red: '#C0392B',
});

export const eventColors = Object.freeze([
    '#5B8FCC',
    '#C9A84C',
    '#C0697A',
    '#5B9E72',
    '#8B6BAE',
    '#C87D5A',
    '#4A9DA8',
    '#8A8A8A',
]);

export const typography = Object.freeze({
    display: "'Oswald', 'Arial Narrow', sans-serif",
    mono: "'IBM Plex Mono', 'Courier New', monospace",
    body: "'IBM Plex Mono', monospace",
    task: "Arial, sans-serif",
    taskTitle: '15px',
    taskBadge: '11px',
});

export const geometry = Object.freeze({
    radiusSmall: '2px',
    radiusSmallNumber: 2,
    radiusMedium: '9px',
});

/**
 * Timeline layout tokens — the single source for the horizontal day row.
 *
 * Derivation chain (see TaskUnit + index.css):
 *   font-size 15px × line-height 1.2  → title line height 18px
 *   checkbox 15px / progress badge    → never exceed 18px
 *   unit padding 5px (top/bottom)     → header box = 5 + 18 + 5 = 28px
 *   → eventHeaderHeight = overlapReveal: the full title bar an overlaid
 *     column must leave visible. Changing the header design updates this
 *     single constant; the cascade algorithm has no magic numbers of its own.
 */
export const timeline = Object.freeze({
    // Status strip — px rows replace the old fixed 15% container whose px
    // children (16px rows, 18px pitch) could overflow into the event area.
    statusRowHeight: 16,
    statusRowGap: 2, // 18px pitch = 16 + 2
    statusStripGap: 2, // breathing room between strip and event area

    // Event cascade — conflict axis (top/height), never the time axis.
    eventHeaderHeight: 5 + 18 + 5, // full title bar = overlapReveal
    eventMinHeight: 36, // one column's minimum body (former LANE_HEIGHT_PX)
    eventAreaBaseHeight: 44, // event area with a single column
    eventGap: 2, // vertical inset around a block (former +2px / -4px)
});

/** Installs the canonical tokens while still allowing .tptheme packages to override them later. */
export function installDesignTokens(root = document.documentElement) {
    const variables = {
        '--clr-bg': colors.background,
        '--clr-surface': colors.surface,
        '--clr-raised': colors.surfaceRaised,
        '--clr-border': colors.border,
        '--clr-border-bright': colors.borderBright,
        '--clr-gold': colors.gold,
        '--clr-gold-bright': colors.goldBright,
        '--clr-gold-dim': colors.goldDark,
        '--clr-gold-ghost': colors.goldGhost,
        '--clr-gold-hover': colors.goldHover,
        '--clr-red': colors.red,
        '--clr-blue': colors.blue,
        '--clr-text': colors.textPrimary,
        '--clr-text-dim': colors.textSecondary,
        '--clr-text-mute': colors.textMuted,
        '--clr-success': colors.green,
        '--font-display': typography.display,
        '--font-mono': typography.mono,
        '--font-body': typography.body,
        '--font-task': typography.task,
        '--task-title-size': typography.taskTitle,
        '--task-badge-size': typography.taskBadge,
        '--radius-sm': geometry.radiusSmall,
    };
    eventColors.forEach((color, index) => {
        variables[`--clr-event-${index}`] = color;
    });
    Object.entries(variables).forEach(([name, value]) => root.style.setProperty(name, value));
}
