import { lightTokens } from '../../design-assets/tokens/generated/tplanner-light.mjs';

export { lightTokens };

// Both entry points select the same explicit light baseline.
export const platformProfile = typeof window !== 'undefined' && window.electronAPI ? 'desktop' : 'web';
const profile = lightTokens.platform[platformProfile];
const c = lightTokens.semantic.color;
const family = names => names.map(name => name.includes(' ') ? JSON.stringify(name) : name).join(', ');
const px = value => `${value}px`;
const rem = value => `${value / 16}rem`;

/** Legacy names remain aliases only. New consumers use semantic roles. */
export const colors = Object.freeze({
    ...c,
    background: c.canvas,
    surfaceRaised: c.raised,
    control: c.input,
    border: c.borderSubtle,
    borderBright: c.borderControl,
    textOnAccent: c.onAccent,
    // Historical gold names appear mostly as text. Filled actions use accent.
    gold: c.accentText,
    goldBright: c.accentText,
    goldDark: c.accentText,
    goldGhost: c.selectedBackground,
    goldSubtle: c.selectedBackground,
    goldHover: c.hoverBackground,
    goldSelected: c.selectedBackground,
    goldSelectedHover: c.hoverBackground,
    goldGlow: c.selectedBackground,
    blue: c.info,
    blueBright: c.info,
    teal: c.success,
    green: c.success,
    greenGhost: c.successBackground,
    red: c.error,
});

/** IDs represent persisted user categories, never type or interaction state. */
export const categoryTokens = Object.freeze(Array.from({ length: 8 }, (_, id) => lightTokens.semantic.category[`id${id}`]));
export function categoryForId(colorId) {
    return categoryTokens[Number.isInteger(colorId) && colorId >= 0 && colorId < 8 ? colorId : 0];
}
export const eventColors = Object.freeze(categoryTokens.map(category => category.accent));

export const typography = Object.freeze({
    display: family(profile.typography.heading.fontFamily),
    mono: family(lightTokens.primitive.font.mono),
    body: family(profile.typography.body.fontFamily),
    // Compact values are coupled to the timeline cascade geometry.
    taskTitle: '15px',
    taskTime: '10px',
    taskBadge: '10px',
});

export const geometry = Object.freeze({
    radiusSmall: px(lightTokens.semantic.radius.small),
    radiusSmallNumber: lightTokens.semantic.radius.small,
    radiusMedium: px(lightTokens.semantic.radius.control),
});

export const semantic = Object.freeze({
    surface: { default: c.surface, selected: c.selectedBackground, disabled: c.disabledBackground },
    text: { primary: c.textPrimary, secondary: c.textSecondary, disabled: c.disabledForeground, onAccent: c.onAccent },
    border: { default: c.borderSubtle, selected: c.focus, conflict: c.error },
});

// Accept historical accent strings during migration; all paints still resolve
// to canonical category pairs. Unknown input uses the first category.
const resolveCategory = value => {
    if (typeof value === 'number') return categoryForId(value);
    if (value && typeof value === 'object' && Number.isInteger(value.id)) return categoryForId(value.id);
    return categoryTokens.find(category => category.accent.toLowerCase() === String(value).toLowerCase()) || categoryForId(0);
};
const isCompleted = state => state === 'completed' || state === 'shadow';
const opacity = Object.freeze({ selected: 1, normal: 1, shadow: 1, completed: 1 });

/** Explicit category surfaces and foregrounds replace dark-theme color mixing. */
export const event = Object.freeze({
    opacity,
    surface: value => resolveCategory(value).background,
    selectedSurface: value => resolveCategory(value).background,
    completedSurface: () => lightTokens.component.task.normalBackground,
    border: value => resolveCategory(value).border,
    selectedBorder: () => c.focus,
    completedBorder: () => c.borderSubtle,
    text: c.textPrimary,
    completedOpacity: lightTokens.component.task.completedOpacity,
    shadowOpacity: lightTokens.semantic.state.normalOpacity,
    completedFilter: 'none',
    outline: Object.freeze({ selected: c.focus, selectedWidth: px(lightTokens.semantic.stroke.focus), selectedOffset: '1px' }),
    surfaceFor: (value, state) => isCompleted(state) ? lightTokens.component.task.normalBackground : resolveCategory(value).background,
    borderFor: (value, state) => state === 'selected' ? c.focus : isCompleted(state) ? c.borderSubtle : resolveCategory(value).border,
    foregroundFor: (value, state) => isCompleted(state) ? lightTokens.component.task.completedForeground : resolveCategory(value).foreground,
    opacityFor: state => opacity[state] ?? 1,
});

/** Geometry is an algorithm input: changing fonts alone breaks overlap reveals. */
export const timeline = Object.freeze({
    statusRowHeight: 16,
    statusRowGap: 2,
    statusStripGap: 2,
    eventSummaryHeight: 15 + 2 + 10,
    eventMinHeight: 34,
    eventAreaBaseHeight: 34,
    eventGap: 2,
});

/** Install compatibility and active-platform aliases from the local package. */
export function installDesignTokens(root = document.documentElement) {
    root.dataset.tpTheme = 'light';
    root.dataset.tpPlatform = platformProfile;
    const variables = {
        '--clr-bg': c.canvas,
        '--clr-surface': c.surface,
        '--clr-raised': c.raised,
        '--clr-control': c.input,
        '--clr-border': c.borderSubtle,
        '--clr-border-bright': c.borderControl,
        '--clr-gold': c.accentText,
        '--clr-gold-bright': c.accentText,
        '--clr-gold-dim': c.accentText,
        '--clr-gold-ghost': c.selectedBackground,
        '--clr-gold-hover': c.hoverBackground,
        '--clr-accent': c.accent,
        '--clr-accent-hover': c.accentHover,
        '--clr-accent-pressed': c.accentPressed,
        '--clr-on-accent': c.onAccent,
        '--clr-focus': c.focus,
        '--clr-red': c.error,
        '--clr-blue': c.info,
        '--clr-text': c.textPrimary,
        '--clr-text-dim': c.textSecondary,
        '--clr-text-mute': c.textMuted,
        '--clr-success': c.success,
        '--font-display': typography.display,
        '--font-mono': typography.mono,
        '--font-body': typography.body,
        '--task-title-size': typography.taskTitle,
        '--task-time-size': typography.taskTime,
        '--task-badge-size': typography.taskBadge,
        '--radius-sm': geometry.radiusSmall,
        '--radius': px(lightTokens.semantic.radius.control),
        '--radius-lg': px(lightTokens.semantic.radius.card),
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
        '--event-text': event.text,
        '--event-summary-height': px(timeline.eventSummaryHeight),
        '--tp-profile-control-min-height': px(profile.geometry.controlMinHeight),
        '--tp-profile-touch-target-min': px(profile.geometry.touchTargetMin),
        '--tp-profile-task-row-min-height': px(profile.geometry.taskRowMinHeight),
        '--tp-profile-page-padding': px(profile.geometry.pageInset),
    };
    Object.entries(profile.typography).forEach(([role, type]) => {
        const name = role.replace(/[A-Z]/g, letter => `-${letter.toLowerCase()}`);
        variables[`--tp-profile-${name}-font-size`] = rem(type.fontSize);
        variables[`--tp-profile-${name}-line-height`] = String(type.lineHeight);
        variables[`--tp-profile-${name}-font-weight`] = String(type.fontWeight);
    });
    eventColors.forEach((color, index) => { variables[`--clr-event-${index}`] = color; });
    Object.entries(variables).forEach(([name, value]) => root.style.setProperty(name, value));
}
