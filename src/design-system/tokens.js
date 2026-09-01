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
    taskTitle: '25px',
    taskTime: '10px',
    taskBadge: '11px',
});

export const geometry = Object.freeze({
    radiusSmall: '2px',
    radiusSmallNumber: 2,
    radiusMedium: '9px',
});

/**
 * Semantic layer — State decides HOW prominent something is.
 * Type (task/reminder/status) only provides the accent hue; these tokens own
 * surface emphasis, text contrast, and border emphasis. Components must
 * reference them instead of computing their own brightness/contrast.
 */
export const semantic = Object.freeze({
    surface: {
        default: colors.surfaceRaised,
        selected: colors.control,
        disabled: colors.surface,
    },
    text: {
        primary: colors.textPrimary,
        secondary: colors.textSecondary,
        disabled: colors.textMuted,
        onAccent: colors.textOnAccent,
    },
    border: {
        default: colors.border,
        selected: colors.gold,
        conflict: colors.red,
    },
});

/**
 * Component layer — the event block multiplies TYPE (accent hue) by STATE
 * (emphasis). All color-mix / opacity / outline decisions live here; the
 * component only asks for `event.surface(accent, state)` and friends.
 *
 * The 50% mixes made every event gray; these numbers are the design system's
 * single answer for "how much accent per state" — components must not tune
 * them locally.
 */
const EVENT_SURFACE_MIX = Object.freeze({
    selected: 0.82,
    normal: 0.65,
    shadow: 0.65,
    completed: 0.30, // sinks toward the neutral surface (de-saturated)
});
const EVENT_BORDER_MIX = Object.freeze({
    selected: 0.95,
    normal: 0.85,
    shadow: 0.85,
    completed: 0.55,
});
const EVENT_OPACITY = Object.freeze({
    selected: 1,
    normal: 1,
    shadow: 0.25,
    completed: 0.45,
});

export const event = Object.freeze({
    surfaceAccentMix: EVENT_SURFACE_MIX,
    borderAccentMix: EVENT_BORDER_MIX,
    opacity: EVENT_OPACITY,
    // Named state tokens — the design system's vocabulary; components map a
    // state to these instead of inventing brightness/contrast values.
    surface: (accent) =>
        `color-mix(in srgb, ${accent} ${Math.round(EVENT_SURFACE_MIX.normal * 100)}%, var(--clr-raised))`,
    selectedSurface: (accent) =>
        `color-mix(in srgb, ${accent} ${Math.round(EVENT_SURFACE_MIX.selected * 100)}%, var(--clr-raised))`,
    completedSurface: (accent) =>
        `color-mix(in srgb, ${accent} ${Math.round(EVENT_SURFACE_MIX.completed * 100)}%, var(--clr-raised))`,
    border: (accent) =>
        `color-mix(in srgb, ${accent} ${Math.round(EVENT_BORDER_MIX.normal * 100)}%, rgba(255,255,255,0.18))`,
    selectedBorder: (accent) =>
        `color-mix(in srgb, ${accent} ${Math.round(EVENT_BORDER_MIX.selected * 100)}%, rgba(255,255,255,0.18))`,
    completedBorder: (accent) =>
        `color-mix(in srgb, ${accent} ${Math.round(EVENT_BORDER_MIX.completed * 100)}%, rgba(255,255,255,0.18))`,
    text: semantic.text.primary,
    completedOpacity: EVENT_OPACITY.completed,
    shadowOpacity: EVENT_OPACITY.shadow,
    completedFilter: 'saturate(0.3)',
    outline: Object.freeze({
        selected: colors.gold,
        selectedWidth: '2px',
        selectedOffset: '1px',
    }),
    // State dispatch used by EventBlock — values come from the tables above.
    surfaceFor: (accent, state) => {
        if (state === 'selected') return event.selectedSurface(accent);
        if (state === 'completed') return event.completedSurface(accent);
        return event.surface(accent);
    },
    borderFor: (accent, state) => {
        if (state === 'selected') return event.selectedBorder(accent);
        if (state === 'completed') return event.completedBorder(accent);
        return event.border(accent);
    },
    opacityFor: (state) => EVENT_OPACITY[state] ?? 1,
});

/**
 * Timeline layout tokens — the single source for the horizontal day row.
 *
 * Derivation chain (see TaskUnit + index.css):
 *   title row 25px (25px font × line-height 1, checkbox 15px stays smaller)
 *   + time row 10px (10px font × line-height 1)
 *   + 2px gap  → title+time fill the summary exactly: 25 + 2 + 10 = 37px
 *   → eventSummaryHeight = overlapReveal: the fixed recognition zone an
 *     overlaid column must leave visible. The cascade algorithm knows ONLY
 *     this number — content beyond 37px clips instead of growing the box.
 */
export const timeline = Object.freeze({
    // Status strip — px rows replace the old fixed 15% container whose px
    // children (16px rows, 18px pitch) could overflow into the event area.
    statusRowHeight: 16,
    statusRowGap: 2, // 18px pitch = 16 + 2
    statusStripGap: 2, // breathing room between strip and event area

    // Event cascade — conflict axis (top/height), never the time axis.
    eventSummaryHeight: 25 + 2 + 10, // 37px fixed recognition zone = overlapReveal
    eventMinHeight: 44, // one column's minimum height (≥ summary + a small body)
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
        '--task-title-size': typography.taskTitle,
        '--task-time-size': typography.taskTime,
        '--task-badge-size': typography.taskBadge,
        '--radius-sm': geometry.radiusSmall,
        // Semantic layer — state emphasis for CSS-side rules (glow, hover…)
        '--surface-default': semantic.surface.default,
        '--surface-selected': semantic.surface.selected,
        '--surface-disabled': semantic.surface.disabled,
        '--text-primary': semantic.text.primary,
        '--text-secondary': semantic.text.secondary,
        '--text-disabled': semantic.text.disabled,
        '--text-on-accent': semantic.text.onAccent,
        '--border-default': semantic.border.default,
        '--border-selected': semantic.border.selected,
        '--border-conflict': semantic.border.conflict,
        // Component layer — event block emphasis + the layout invariant that
        // the cascade algorithm depends on.
        '--event-text': event.text,
        '--event-summary-height': `${timeline.eventSummaryHeight}px`,
    };
    eventColors.forEach((color, index) => {
        variables[`--clr-event-${index}`] = color;
    });
    Object.entries(variables).forEach(([name, value]) => root.style.setProperty(name, value));
}
