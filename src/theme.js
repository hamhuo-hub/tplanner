import { createTheme } from '@mui/material/styles';

/**
 * MUI Black-Gold Dark Theme — Soviet Constructivism
 */
const theme = createTheme({
    palette: {
        mode: 'dark',
        primary: {
            main: '#C9A84C',
            light: '#F0C040',
            dark: '#6B5928',
            contrastText: '#0A0A0A',
        },
        secondary: {
            main: '#C0392B',
            light: '#E74C3C',
            dark: '#922B21',
            contrastText: '#ffffff',
        },
        error:   { main: '#C0392B' },
        warning: { main: '#C9A84C' },
        success: { main: '#4A7C59' },
        info:    { main: '#3B6B8F' },
        background: {
            default: '#111111',
            paper:   '#181818',
        },
        text: {
            primary:   '#E0D8C8',
            secondary: '#6B6355',
            disabled:  '#3A342A',
        },
        divider: '#272727',
    },

    typography: {
        fontFamily: "'IBM Plex Mono', 'Courier New', monospace",
        h1: { fontFamily: "'Oswald', 'Arial Narrow', sans-serif", fontWeight: 700, letterSpacing: '0.06em' },
        h2: { fontFamily: "'Oswald', 'Arial Narrow', sans-serif", fontWeight: 700, letterSpacing: '0.05em' },
        h3: { fontFamily: "'Oswald', 'Arial Narrow', sans-serif", fontWeight: 600, letterSpacing: '0.05em' },
        h4: { fontFamily: "'Oswald', 'Arial Narrow', sans-serif", fontWeight: 600 },
        h5: { fontFamily: "'Oswald', 'Arial Narrow', sans-serif", fontWeight: 500 },
        h6: { fontFamily: "'Oswald', 'Arial Narrow', sans-serif", fontWeight: 500 },
        button: {
            fontFamily: "'Oswald', 'Arial Narrow', sans-serif",
            fontWeight: 500,
            letterSpacing: '0.1em',
            textTransform: 'uppercase',
        },
        caption: { fontFamily: "'IBM Plex Mono', monospace", fontSize: '0.7rem', letterSpacing: '0.1em' },
        overline: { fontFamily: "'IBM Plex Mono', monospace", letterSpacing: '0.2em' },
    },

    shape: { borderRadius: 2 },

    components: {
        // ── Dialog ──────────────────────────────────────────────────────────
        MuiDialog: {
            styleOverrides: {
                paper: {
                    background: '#181818',
                    border: '1px solid #272727',
                    borderTop: '3px solid #C9A84C',
                    borderRadius: 2,
                    boxShadow: '0 24px 80px rgba(0,0,0,0.7)',
                    backgroundImage: 'none',
                },
            },
        },
        MuiDialogTitle: {
            styleOverrides: {
                root: {
                    fontFamily: "'Oswald', 'Arial Narrow', sans-serif",
                    fontWeight: 700,
                    fontSize: '1rem',
                    letterSpacing: '0.08em',
                    textTransform: 'uppercase',
                    color: '#C9A84C',
                    borderBottom: '1px solid #272727',
                    padding: '14px 18px',
                    paddingLeft: '22px',
                    position: 'relative',
                    '&::before': {
                        content: '""',
                        position: 'absolute',
                        left: 0, top: 0, bottom: 0,
                        width: 3,
                        background: '#C9A84C',
                    },
                },
            },
        },
        MuiDialogContent: {
            styleOverrides: {
                root: {
                    padding: '18px',
                    background: '#181818',
                    '&::-webkit-scrollbar': { width: 6 },
                    '&::-webkit-scrollbar-track': { background: '#060606' },
                    '&::-webkit-scrollbar-thumb': { background: '#6B5928', borderRadius: 0 },
                },
            },
        },
        MuiDialogActions: {
            styleOverrides: {
                root: {
                    padding: '12px 18px',
                    borderTop: '1px solid #272727',
                    background: '#111111',
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
                    fontFamily: "'IBM Plex Mono', monospace",
                    fontSize: '0.8rem',
                    borderRadius: 2,
                    background: '#0E0E0E',
                    '& fieldset': { borderColor: '#272727' },
                    '&:hover fieldset': { borderColor: '#6B5928 !important' },
                    '&.Mui-focused fieldset': { borderColor: '#C9A84C !important', borderWidth: '1px !important' },
                },
                input: {
                    color: '#E0D8C8',
                    fontFamily: "'IBM Plex Mono', monospace",
                    '&::placeholder': { color: '#3A342A', opacity: 1 },
                },
            },
        },
        MuiInputLabel: {
            styleOverrides: {
                root: {
                    fontFamily: "'IBM Plex Mono', monospace",
                    fontSize: '0.75rem',
                    letterSpacing: '0.08em',
                    color: '#6B6355',
                    '&.Mui-focused': { color: '#C9A84C' },
                },
            },
        },
        MuiSelect: {
            styleOverrides: { icon: { color: '#6B5928' } },
        },

        // ── Button ────────────────────────────────────────────────────────────
        MuiButton: {
            styleOverrides: {
                root: {
                    borderRadius: 2,
                    fontFamily: "'Oswald', 'Arial Narrow', sans-serif",
                    letterSpacing: '0.1em',
                    fontSize: '0.75rem',
                    fontWeight: 500,
                    textTransform: 'uppercase',
                },
                contained: {
                    background: '#C9A84C',
                    color: '#0A0A0A',
                    boxShadow: 'none',
                    '&:hover': { background: '#F0C040', boxShadow: '0 0 16px rgba(201,168,76,0.3)' },
                },
                outlined: {
                    borderColor: '#272727',
                    color: '#6B6355',
                    '&:hover': { borderColor: '#C9A84C', color: '#C9A84C', background: 'rgba(201,168,76,0.06)' },
                },
                text: {
                    color: '#6B6355',
                    '&:hover': { color: '#C9A84C', background: 'rgba(201,168,76,0.06)' },
                },
            },
        },
        MuiIconButton: {
            styleOverrides: {
                root: {
                    borderRadius: 2,
                    color: '#6B6355',
                    '&:hover': { color: '#C9A84C', background: 'rgba(201,168,76,0.08)' },
                },
            },
        },

        // ── ToggleButton ─────────────────────────────────────────────────────
        MuiToggleButton: {
            styleOverrides: {
                root: {
                    fontFamily: "'Oswald', 'Arial Narrow', sans-serif",
                    fontSize: '0.72rem',
                    letterSpacing: '0.1em',
                    textTransform: 'uppercase',
                    fontWeight: 500,
                    borderRadius: 2,
                    color: '#6B6355',
                    borderColor: '#272727',
                    '&.Mui-selected': {
                        color: '#0A0A0A',
                        background: '#C9A84C',
                        borderColor: '#C9A84C',
                        '&:hover': { background: '#F0C040' },
                    },
                    '&:hover': { background: 'rgba(201,168,76,0.08)', color: '#C9A84C' },
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
                    fontFamily: "'IBM Plex Mono', monospace",
                    fontSize: '0.75rem',
                    '&.Mui-selected': { background: '#C9A84C !important', color: '#0A0A0A' },
                    '&:hover': { background: 'rgba(201,168,76,0.15)' },
                },
            },
        },
        MuiPickersCalendarHeader: {
            styleOverrides: {
                label: {
                    fontFamily: "'Oswald', sans-serif",
                    letterSpacing: '0.05em',
                    textTransform: 'uppercase',
                    color: '#C9A84C',
                },
            },
        },
        MuiDateCalendar: {
            styleOverrides: { root: { background: '#181818', borderRadius: 2 } },
        },
        MuiClock: {
            styleOverrides: {
                pin: { background: '#C9A84C' },
                clock: { background: '#0E0E0E' },
            },
        },
        MuiClockNumber: {
            styleOverrides: {
                root: {
                    fontFamily: "'IBM Plex Mono', monospace",
                    fontSize: '0.7rem',
                    '&.Mui-selected': { background: '#C9A84C', color: '#0A0A0A' },
                },
            },
        },
        MuiClockPointer: {
            styleOverrides: {
                root: { background: '#C9A84C' },
                thumb: { background: '#C9A84C', borderColor: '#C9A84C' },
            },
        },

        // ── Paper / Popover ───────────────────────────────────────────────────
        MuiPaper: {
            styleOverrides: {
                root: {
                    backgroundImage: 'none',
                    background: '#181818',
                    border: '1px solid #272727',
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
            styleOverrides: { root: { borderColor: '#272727' } },
        },
        MuiTypography: {
            styleOverrides: { root: { fontFamily: "'IBM Plex Mono', monospace" } },
        },
        MuiMenuItem: {
            styleOverrides: {
                root: {
                    fontFamily: "'IBM Plex Mono', monospace",
                    fontSize: '0.78rem',
                    color: '#E0D8C8',
                    '&:hover': { background: 'rgba(201,168,76,0.08)' },
                    '&.Mui-selected': {
                        background: 'rgba(201,168,76,0.12)',
                        '&:hover': { background: 'rgba(201,168,76,0.18)' },
                    },
                },
            },
        },
    },
});

export default theme;
