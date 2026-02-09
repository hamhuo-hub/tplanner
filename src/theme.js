import { createTheme } from '@mui/material/styles';

const theme = createTheme({
    palette: {
        // Standard Google Material colors
        primary: {
            main: '#1976d2', // Blue 700
        },
        secondary: {
            main: '#9c27b0', // Purple 500
        },
        error: {
            main: '#d32f2f', // Red 700
        },
        warning: {
            main: '#ed6c02', // Orange 700
        },
        info: {
            main: '#0288d1', // Light Blue 700
        },
        success: {
            main: '#2e7d32', // Green 800
        },
        background: {
            default: '#f5f5f5', // Grey 100
            paper: '#ffffff',
        },
    },
    typography: {
        fontFamily: [
            '-apple-system',
            'BlinkMacSystemFont',
            '"Segoe UI"',
            'Roboto',
            '"Helvetica Neue"',
            'Arial',
            'sans-serif',
            '"Apple Color Emoji"',
            '"Segoe UI Emoji"',
            '"Segoe UI Symbol"',
        ].join(','),
    },
    components: {
        MuiCard: {
            styleOverrides: {
                root: {
                    borderRadius: 8,
                    boxShadow: '0 2px 4px rgba(0,0,0,0.1)',
                },
            },
        },
    },
});

export default theme;
