import { createTheme } from '@mui/material/styles';
import { colors, geometry, typography } from './design-system/tokens';

/**
 * MUI Black-Gold Dark Theme — Soviet Constructivism
 */
const theme = createTheme({
    palette: {
        mode: 'dark',
        primary: {
            main: colors.gold,
            light: colors.goldBright,
            dark: colors.goldDark,
            contrastText: colors.textOnAccent,
        },
        secondary: {
            main: colors.red,
            light: '#E74C3C',
            dark: '#922B21',
            contrastText: '#ffffff',
        },
        error:   { main: colors.red },
        warning: { main: colors.gold },
        success: { main: colors.green },
        info:    { main: colors.blue },
        background: {
            default: colors.background,
            paper: colors.surface,
        },
        text: {
            primary: colors.textPrimary,
            secondary: colors.textSecondary,
            disabled: colors.textMuted,
        },
        divider: colors.border,
    },

    typography: {
        fontFamily: typography.mono,
        h1: { fontFamily: typography.display, fontWeight: 700, letterSpacing: '0.06em' },
        h2: { fontFamily: typography.display, fontWeight: 700, letterSpacing: '0.05em' },
        h3: { fontFamily: typography.display, fontWeight: 600, letterSpacing: '0.05em' },
        h4: { fontFamily: typography.display, fontWeight: 600 },
        h5: { fontFamily: typography.display, fontWeight: 500 },
        h6: { fontFamily: typography.display, fontWeight: 500 },
        button: {
            fontFamily: typography.display,
            fontWeight: 500,
            letterSpacing: '0.1em',
            textTransform: 'uppercase',
        },
        caption: { fontFamily: typography.body, fontSize: '0.7rem', letterSpacing: '0.1em' },
        overline: { fontFamily: typography.body, letterSpacing: '0.2em' },
    },

    shape: { borderRadius: geometry.radiusSmallNumber },

    components: {
        // ── Dialog ──────────────────────────────────────────────────────────
        MuiDialog: {
            styleOverrides: {
                paper: {
                    background: colors.surface,
                    border: `1px solid ${colors.border}`,
                    borderTop: `3px solid ${colors.gold}`,
                    borderRadius: 2,
                    boxShadow: '0 24px 80px rgba(0,0,0,0.7)',
                    backgroundImage: 'none',
                },
            },
        },
        MuiDialogTitle: {
            styleOverrides: {
                root: {
                    fontFamily: typography.display,
                    fontWeight: 700,
                    fontSize: '1rem',
                    letterSpacing: '0.08em',
                    textTransform: 'uppercase',
                    color: colors.gold,
                    borderBottom: `1px solid ${colors.border}`,
                    padding: '14px 18px',
                    paddingLeft: '22px',
                    position: 'relative',
                    '&::before': {
                        content: '""',
                        position: 'absolute',
                        left: 0, top: 0, bottom: 0,
                        width: 3,
                        background: colors.gold,
                    },
                },
            },
        },
        MuiDialogContent: {
            styleOverrides: {
                root: {
                    padding: '18px',
                    background: colors.surface,
                    '&::-webkit-scrollbar': { width: 6 },
                    '&::-webkit-scrollbar-track': { background: '#060606' },
                    '&::-webkit-scrollbar-thumb': { background: colors.goldDark, borderRadius: 0 },
                },
            },
        },
        MuiDialogActions: {
            styleOverrides: {
                root: {
                    padding: '12px 18px',
                    borderTop: `1px solid ${colors.border}`,
                    background: colors.background,
                    gap: 8,
                },
            },
        },

        // ── TextField ────────────────────────────────────────────────────────
        MuiTextField: {
            defaultProps: { variant: 'outlined', size: 'small' },
        },
        MuiOutlinedInput: {
            styleOverrides: {
                root: {
                    fontFamily: typography.body,
                    fontSize: '0.8rem',
                    borderRadius: 2,
                    background: colors.background,
                    '& fieldset': { borderColor: colors.border },
                    '&:hover fieldset': { borderColor: `${colors.goldDark} !important` },
                    '&.Mui-focused fieldset': { borderColor: `${colors.gold} !important`, borderWidth: '1px !important' },
                },
                input: {
                    color: colors.textPrimary,
                    fontFamily: typography.body,
                    '&::placeholder': { color: colors.textMuted, opacity: 1 },
                },
            },
        },
        MuiInputLabel: {
            styleOverrides: {
                root: {
                    fontFamily: typography.body,
                    fontSize: '0.75rem',
                    letterSpacing: '0.08em',
                    color: colors.textSecondary,
                    '&.Mui-focused': { color: colors.gold },
                },
            },
        },
        MuiSelect: {
            styleOverrides: { icon: { color: colors.goldDark } },
        },

        // ── Button ────────────────────────────────────────────────────────────
        MuiButton: {
            styleOverrides: {
                root: {
                    borderRadius: 2,
                    fontFamily: typography.display,
                    letterSpacing: '0.1em',
                    fontSize: '0.75rem',
                    fontWeight: 500,
                    textTransform: 'uppercase',
                },
                contained: {
                    background: colors.gold,
                    color: colors.textOnAccent,
                    boxShadow: 'none',
                    '&:hover': { background: colors.goldBright, boxShadow: `0 0 16px ${colors.goldGlow}` },
                },
                outlined: {
                    borderColor: colors.border,
                    color: colors.textSecondary,
                    '&:hover': { borderColor: colors.gold, color: colors.gold, background: colors.goldSubtle },
                },
                text: {
                    color: colors.textSecondary,
                    '&:hover': { color: colors.gold, background: colors.goldSubtle },
                },
            },
        },
        MuiIconButton: {
            styleOverrides: {
                root: {
                    borderRadius: 2,
                    color: colors.textSecondary,
                    '&:hover': { color: colors.gold, background: colors.goldGhost },
                },
            },
        },

        // ── ToggleButton ─────────────────────────────────────────────────────
        MuiToggleButton: {
            styleOverrides: {
                root: {
                    fontFamily: typography.display,
                    fontSize: '0.72rem',
                    letterSpacing: '0.1em',
                    textTransform: 'uppercase',
                    fontWeight: 500,
                    borderRadius: 2,
                    color: colors.textSecondary,
                    borderColor: colors.border,
                    '&.Mui-selected': {
                        color: colors.textOnAccent,
                        background: colors.gold,
                        borderColor: colors.gold,
                        '&:hover': { background: colors.goldBright },
                    },
                    '&:hover': { background: colors.goldGhost, color: colors.gold },
                },
            },
        },
        MuiToggleButtonGroup: {
            styleOverrides: {
                root: {
                    gap: 0,
                    '& .MuiToggleButtonGroup-grouped': {
                        borderRadius: 0,
                        '&:first-of-type': { borderRadius: '2px 0 0 2px' },
                        '&:last-of-type':  { borderRadius: '0 2px 2px 0' },
                    },
                },
            },
        },

        // ── Date/Time Pickers ─────────────────────────────────────────────────
        MuiPickersDay: {
            styleOverrides: {
                root: {
                    borderRadius: 2,
                    fontFamily: typography.body,
                    fontSize: '0.75rem',
                    '&.Mui-selected': { background: `${colors.gold} !important`, color: colors.textOnAccent },
                    '&:hover': { background: colors.goldHover },
                },
            },
        },
        MuiPickersCalendarHeader: {
            styleOverrides: {
                label: {
                    fontFamily: typography.display,
                    letterSpacing: '0.05em',
                    textTransform: 'uppercase',
                    color: colors.gold,
                },
            },
        },
        MuiDateCalendar: {
            styleOverrides: { root: { background: colors.surface, borderRadius: geometry.radiusSmallNumber } },
        },
        MuiClock: {
            styleOverrides: {
                pin: { background: colors.gold },
                clock: { background: colors.background },
            },
        },
        MuiClockNumber: {
            styleOverrides: {
                root: {
                    fontFamily: typography.body,
                    fontSize: '0.7rem',
                    '&.Mui-selected': { background: colors.gold, color: colors.textOnAccent },
                },
            },
        },
        MuiClockPointer: {
            styleOverrides: {
                root: { background: colors.gold },
                thumb: { background: colors.gold, borderColor: colors.gold },
            },
        },

        // ── Paper / Popover ───────────────────────────────────────────────────
        MuiPaper: {
            styleOverrides: {
                root: {
                    backgroundImage: 'none',
                    background: colors.surface,
                    border: `1px solid ${colors.border}`,
                    borderRadius: 2,
                },
            },
        },
        MuiPopover: {
            styleOverrides: {
                paper: { border: '1px solid #383838', boxShadow: '0 8px 32px rgba(0,0,0,0.6)' },
            },
        },

        // ── Misc ──────────────────────────────────────────────────────────────
        MuiDivider: {
            styleOverrides: { root: { borderColor: colors.border } },
        },
        MuiTypography: {
            styleOverrides: { root: { fontFamily: typography.body } },
        },
        MuiMenuItem: {
            styleOverrides: {
                root: {
                    fontFamily: typography.body,
                    fontSize: '0.78rem',
                    color: colors.textPrimary,
                    '&:hover': { background: colors.goldGhost },
                    '&.Mui-selected': {
                        background: colors.goldSelected,
                        '&:hover': { background: colors.goldSelectedHover },
                    },
                },
            },
        },
    },
});

export default theme;
