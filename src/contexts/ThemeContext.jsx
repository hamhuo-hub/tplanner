import { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { themeEngine, BUILTIN_ID } from '../theme-engine/ThemeEngine';

const ThemeContext = createContext(null);

/**
 * ThemeProvider — initializes the engine and broadcasts active theme ID
 * to all consumers via context.
 */
export function ThemeProvider({ children }) {
    const [activeId, setActiveId] = useState(BUILTIN_ID);
    const [themes, setThemes]     = useState([]);
    const [notification, setNotification] = useState(null); // { message, type }

    useEffect(() => {
        // Init engine (injects style elements, restores persisted theme)
        themeEngine.init();
        setActiveId(themeEngine.getActiveThemeId());
        setThemes(themeEngine.getInstalledThemes());

        // Listen for theme changes
        const unsub = themeEngine.onChange((newId) => {
            setActiveId(newId);
            setThemes(themeEngine.getInstalledThemes());
        });

        // Listen for theme installation from Electron (double-click .tptheme)
        if (window.electronAPI?.onThemeInstall) {
            window.electronAPI.onThemeInstall((jsonString) => {
                const result = themeEngine.installFromJSON(jsonString);
                showNotification(result.message, result.success ? 'success' : 'error');
                if (result.success) {
                    setThemes(themeEngine.getInstalledThemes());
                    // Auto-apply newly installed theme
                    themeEngine.applyTheme(result.theme.id);
                }
            });
        }

        return unsub;
    }, []);

    const applyTheme = useCallback((id) => {
        themeEngine.applyTheme(id);
    }, []);

    const installTheme = useCallback((jsonString) => {
        const result = themeEngine.installFromJSON(jsonString);
        showNotification(result.message, result.success ? 'success' : 'error');
        if (result.success) {
            setThemes(themeEngine.getInstalledThemes());
            themeEngine.applyTheme(result.theme.id);
        }
        return result;
    }, []);

    const uninstallTheme = useCallback((id) => {
        const ok = themeEngine.uninstall(id);
        if (ok) {
            setThemes(themeEngine.getInstalledThemes());
            showNotification('主题已卸载', 'success');
        }
    }, []);

    const showNotification = (message, type = 'success') => {
        setNotification({ message, type });
        setTimeout(() => setNotification(null), 3500);
    };

    return (
        <ThemeContext.Provider value={{ activeId, themes, applyTheme, installTheme, uninstallTheme, notification }}>
            {children}
            {/* Toast notification */}
            {notification && (
                <div className="tpt-toast" data-type={notification.type}>
                    {notification.type === 'success' ? '✓' : '✗'} {notification.message}
                </div>
            )}
        </ThemeContext.Provider>
    );
}

export function useTheme() {
    const ctx = useContext(ThemeContext);
    if (!ctx) throw new Error('useTheme must be used inside ThemeProvider');
    return ctx;
}
