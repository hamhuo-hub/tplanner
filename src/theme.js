import { createTheme } from '@mui/material/styles';
import { createLightThemeOptions } from '../design-assets/tokens/adapters/mui-light';
import { colors as c, lightTokens as t, platformProfile, typography } from './design-system/tokens';

const p = t.platform[platformProfile];
const radius = t.semantic.radius;
const spacing = t.semantic.spacing;
const focus = {
    outline: `${t.component.button.focus.width}px solid ${c.focus}`,
    outlineOffset: t.component.button.focus.offset,
};
const shadow = component => `${component.shadowX}px ${component.shadowY}px ${component.shadowBlur}px ${component.shadowSpread}px color-mix(in srgb, ${component.shadowColor} ${component.shadowOpacity * 100}%, transparent)`;
const panelShadow = shadow(t.component.panel);
const dialogShadow = shadow(t.component.dialog);
const controlSize = {
    minHeight: p.geometry.controlMinHeight,
    '@media (pointer: coarse)': { minHeight: p.geometry.touchTargetMin },
};
const selectedFill = {
    backgroundColor: c.accent,
    color: c.onAccent,
    border: `${t.semantic.stroke.control}px solid ${t.component.button.primary.border}`,
    '&:hover, &:focus': { backgroundColor: c.accentHover },
};
const inputRoot = {
    ...controlSize,
    backgroundColor: c.input,
    color: c.textPrimary,
    borderRadius: radius.control,
    '@media (max-width: 600px)': { fontSize: '1rem' },
};

/** The canonical factory provides the baseline; this layer covers actual app components. */
const theme = createTheme(createLightThemeOptions(platformProfile), {
    typography: { htmlFontSize: 16 },
    components: {
        MuiBackdrop: {
            styleOverrides: {
                root: { backgroundColor: 'var(--tp-overlay-background)' },
                invisible: { backgroundColor: 'transparent' },
            },
        },
        MuiCssBaseline: {
            styleOverrides: {
                html: { fontSize: '16px', colorScheme: 'light' },
                '@media (prefers-reduced-motion: reduce)': {
                    '*, *::before, *::after': { animationDuration: '0s !important', transitionDuration: '0s !important' },
                },
            },
        },
        MuiDialog: {
            styleOverrides: { paper: { backgroundImage: 'none' } },
        },
        MuiDialogTitle: {
            styleOverrides: {
                root: {
                    fontFamily: typography.body,
                    fontSize: `${p.typography.title.fontSize / 16}rem`,
                    fontWeight: p.typography.title.fontWeight,
                    lineHeight: p.typography.title.lineHeight,
                    color: c.textPrimary,
                    borderBottom: `1px solid ${c.borderSubtle}`,
                    padding: `${spacing.block}px ${spacing.section}px`,
                },
            },
        },
        MuiDialogContent: {
            styleOverrides: {
                root: {
                    padding: spacing.section,
                    color: c.textPrimary,
                    '&::-webkit-scrollbar': { width: 6 },
                    '&::-webkit-scrollbar-track': { background: c.surface },
                    '&::-webkit-scrollbar-thumb': { background: c.borderControl, borderRadius: radius.pill },
                },
            },
        },
        MuiDialogActions: {
            styleOverrides: {
                root: {
                    padding: `${spacing.block}px ${spacing.section}px`,
                    borderTop: `1px solid ${c.borderSubtle}`,
                    backgroundColor: c.surface,
                    gap: spacing.inline,
                    flexWrap: 'wrap',
                },
            },
        },
        MuiTextField: { defaultProps: { variant: 'outlined', size: 'small' } },
        MuiOutlinedInput: {
            styleOverrides: {
                root: {
                    ...inputRoot,
                    '&&.Mui-focused:not(.Mui-error) .MuiOutlinedInput-notchedOutline': { borderColor: c.focus },
                    '&&.Mui-error .MuiOutlinedInput-notchedOutline': { borderColor: c.error },
                },
            },
        },
        MuiPickersOutlinedInput: {
            styleOverrides: {
                root: {
                    ...inputRoot,
                    '& .MuiPickersOutlinedInput-notchedOutline': { borderColor: c.borderControl },
                    '&:hover .MuiPickersOutlinedInput-notchedOutline': { borderColor: c.focus },
                    // Picker color variants include :not(.Mui-error); match that
                    // condition and outrank the palette.primary accent fill.
                    '&&.Mui-focused:not(.Mui-error) .MuiPickersOutlinedInput-notchedOutline': { borderColor: c.focus },
                    '&&.Mui-error .MuiPickersOutlinedInput-notchedOutline': { borderColor: c.error },
                },
                input: { color: c.textPrimary },
            },
        },
        MuiInputLabel: {
            styleOverrides: {
                root: {
                    '&&.Mui-focused:not(.Mui-error)': { color: c.focus },
                    '&&.Mui-error': { color: c.error },
                },
            },
        },
        MuiSelect: { styleOverrides: { icon: { color: c.textSecondary } } },
        MuiIconButton: {
            styleOverrides: {
                root: {
                    ...controlSize,
                    minWidth: p.geometry.controlMinHeight,
                    borderRadius: radius.control,
                    color: c.textSecondary,
                    '@media (pointer: coarse)': { minWidth: p.geometry.touchTargetMin, minHeight: p.geometry.touchTargetMin },
                    '&:hover': { color: c.accentText, backgroundColor: c.hoverBackground },
                    '&.Mui-focusVisible': focus,
                    '&.Mui-disabled': { color: c.disabledForeground, opacity: 1 },
                },
            },
        },
        MuiToggleButton: {
            styleOverrides: {
                root: {
                    ...controlSize,
                    borderRadius: radius.control,
                    fontFamily: typography.body,
                    fontSize: `${p.typography.body.fontSize / 16}rem`,
                    fontWeight: p.typography.taskTitle.fontWeight,
                    textTransform: 'none',
                    color: c.textSecondary,
                    borderColor: c.borderControl,
                    '&.Mui-selected': {
                        color: c.accentText, backgroundColor: c.selectedBackground, borderColor: c.focus,
                        '&:hover': { backgroundColor: c.hoverBackground },
                    },
                    '&:hover': { backgroundColor: c.hoverBackground, color: c.accentText },
                    '&.Mui-focusVisible': focus,
                },
            },
        },
        MuiPickersDay: {
            styleOverrides: {
                root: {
                    borderRadius: radius.control,
                    color: c.textPrimary,
                    fontSize: `${p.typography.meta.fontSize / 16}rem`,
                    '&.Mui-selected': selectedFill,
                    '&.MuiPickersDay-today': { borderColor: c.focus },
                    '&:hover': { backgroundColor: c.hoverBackground },
                    '&.Mui-focusVisible': focus,
                },
            },
        },
        MuiPickersCalendarHeader: {
            styleOverrides: { label: { fontFamily: typography.body, color: c.textPrimary } },
        },
        MuiDateCalendar: {
            styleOverrides: { root: { backgroundColor: c.raised, borderRadius: radius.card } },
        },
        MuiClock: {
            styleOverrides: { pin: { backgroundColor: c.accentText }, clock: { backgroundColor: c.canvas } },
        },
        MuiClockNumber: {
            styleOverrides: {
                root: {
                    color: c.textPrimary,
                    fontFamily: typography.body,
                    fontSize: `${p.typography.meta.fontSize / 16}rem`,
                    '&.Mui-selected': { backgroundColor: c.accent, color: c.onAccent },
                },
            },
        },
        MuiClockPointer: {
            styleOverrides: {
                root: { backgroundColor: c.accentText },
                thumb: { backgroundColor: c.accent, borderColor: c.accentText },
            },
        },
        MuiPaper: {
            styleOverrides: {
                root: { backgroundImage: 'none', color: c.textPrimary },
                rounded: { borderRadius: radius.card },
                elevation1: { boxShadow: panelShadow },
            },
        },
        MuiPopover: {
            styleOverrides: {
                paper: {
                    backgroundColor: c.raised,
                    border: `${t.component.panel.edgeWidth}px solid ${t.component.panel.edge}`,
                    borderRadius: radius.card,
                    boxShadow: dialogShadow,
                },
            },
        },
        MuiMenuItem: {
            styleOverrides: {
                root: {
                    ...controlSize,
                    fontFamily: typography.body,
                    fontSize: `${p.typography.body.fontSize / 16}rem`,
                    color: c.textPrimary,
                    '&:hover': { backgroundColor: c.hoverBackground },
                    '&.Mui-selected': {
                        backgroundColor: c.selectedBackground, color: c.accentText,
                        '&:hover': { backgroundColor: c.hoverBackground },
                    },
                    '&.Mui-focusVisible': { ...focus, outlineOffset: -2 },
                },
            },
        },
        MuiTooltip: {
            styleOverrides: {
                tooltip: {
                    backgroundColor: c.raised,
                    color: c.textPrimary,
                    border: `1px solid ${c.borderSubtle}`,
                    borderRadius: radius.control,
                    boxShadow: panelShadow,
                    fontSize: `${p.typography.meta.fontSize / 16}rem`,
                    lineHeight: p.typography.meta.lineHeight,
                },
                arrow: { color: c.raised },
            },
        },
        MuiSwitch: {
            styleOverrides: {
                switchBase: {
                    color: c.textSecondary,
                    '&:hover': { backgroundColor: c.hoverBackground },
                    '&.Mui-checked': { color: c.accentText },
                    '&.Mui-checked + .MuiSwitch-track': { backgroundColor: c.selectedBackground, opacity: 1 },
                    '&.Mui-disabled': { color: c.disabledForeground },
                    '&.Mui-disabled + .MuiSwitch-track': { backgroundColor: c.disabledBackground, opacity: 1 },
                    '&.Mui-focusVisible': focus,
                },
                track: { backgroundColor: c.disabledBackground, border: `1px solid ${c.borderControl}`, opacity: 1 },
                thumb: { boxShadow: 'none' },
            },
        },
        MuiDivider: { styleOverrides: { root: { borderColor: c.borderSubtle } } },
    },
});

export default theme;
