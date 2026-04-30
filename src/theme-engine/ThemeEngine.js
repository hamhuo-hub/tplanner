/**
 * ThemeEngine — tPlanner CSS Design Token Injection Engine
 *
 * Three-layer token architecture:
 *   Primitive tokens  (.tptheme file raw values)
 *   Semantic tokens   (mapped to --clr-*, --font-*, --radius-* CSS vars)
 *   Effect tokens     (film grain, vignette, grid lines)
 *
 * The engine injects a single <style> element into <head> that overrides
 * the built-in variables from index.css, without touching any component code.
 */

const STORAGE_KEY_THEMES  = 'tplanner_installed_themes';
const STORAGE_KEY_ACTIVE  = 'tplanner_active_theme';
const BUILTIN_ID          = 'soviet-constructivism';
const STYLE_ELEMENT_ID    = 'tpt-theme-override';
const EFFECTS_ELEMENT_ID  = 'tpt-theme-effects';

class ThemeEngine {
    constructor() {
        /** @type {Map<string, object>} */
        this.themes = new Map();
        this.activeId = BUILTIN_ID;
        this._styleEl = null;
        this._effectsEl = null;
        this._listeners = new Set();
    }

    // ── Init ────────────────────────────────────────────────────────────────

    init() {
        // Inject style element for token overrides
        this._styleEl = this._getOrCreateStyle(STYLE_ELEMENT_ID);
        this._effectsEl = this._getOrCreateStyle(EFFECTS_ELEMENT_ID);

        // Load persisted themes
        this._loadFromStorage();

        // Restore previously active theme
        const saved = localStorage.getItem(STORAGE_KEY_ACTIVE);
        if (saved && saved !== BUILTIN_ID && this.themes.has(saved)) {
            this._applyThemeData(this.themes.get(saved));
            this.activeId = saved;
        }
    }

    // ── Theme Installation ──────────────────────────────────────────────────

    /**
     * Install a parsed .tptheme JSON object.
     * Returns { success, message, theme }
     * @param {object} themeData
     */
    install(themeData) {
        if (!themeData?.id || !themeData?.name) {
            return { success: false, message: '无效的主题文件：缺少 id 或 name 字段' };
        }
        const isUpdate = this.themes.has(themeData.id);
        this.themes.set(themeData.id, themeData);
        this._saveToStorage();
        return {
            success: true,
            message: isUpdate
                ? `主题「${themeData.name}」已更新至 v${themeData.version}`
                : `主题「${themeData.name}」安装成功`,
            theme: themeData,
            isUpdate,
        };
    }

    /**
     * Install from a JSON string (e.g. from file read).
     */
    installFromJSON(jsonString) {
        try {
            const data = JSON.parse(jsonString);
            return this.install(data);
        } catch (e) {
            return { success: false, message: `主题文件解析失败：${e.message}` };
        }
    }

    // ── Theme Activation ────────────────────────────────────────────────────

    /**
     * Apply a theme by ID. Use BUILTIN_ID to reset to default.
     * @param {string} themeId
     */
    applyTheme(themeId) {
        if (themeId === BUILTIN_ID) {
            this.resetToBuiltin();
            return;
        }
        const theme = this.themes.get(themeId);
        if (!theme) return;
        this._applyThemeData(theme);
        this.activeId = themeId;
        localStorage.setItem(STORAGE_KEY_ACTIVE, themeId);
        this._notify();
    }

    resetToBuiltin() {
        this._styleEl.textContent = '';
        this._effectsEl.textContent = '';
        this.activeId = BUILTIN_ID;
        localStorage.setItem(STORAGE_KEY_ACTIVE, BUILTIN_ID);
        this._notify();
    }

    // ── Theme Management ────────────────────────────────────────────────────

    uninstall(themeId) {
        if (!this.themes.has(themeId)) return false;
        if (this.activeId === themeId) this.resetToBuiltin();
        this.themes.delete(themeId);
        this._saveToStorage();
        this._notify();
        return true;
    }

    getInstalledThemes() {
        return [...this.themes.values()];
    }

    getActiveThemeId() {
        return this.activeId;
    }

    getActiveTheme() {
        if (this.activeId === BUILTIN_ID) return null;
        return this.themes.get(this.activeId) ?? null;
    }

    // ── Change Listeners ────────────────────────────────────────────────────

    onChange(fn) {
        this._listeners.add(fn);
        return () => this._listeners.delete(fn);
    }

    _notify() {
        this._listeners.forEach(fn => fn(this.activeId));
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    _applyThemeData(theme) {
        // 1. Build :root { } variable block
        const vars = [];

        // Tokens (--clr-* etc.)
        if (theme.tokens) {
            Object.entries(theme.tokens).forEach(([k, v]) => vars.push(`  ${k}: ${v};`));
        }
        // Typography
        if (theme.typography) {
            Object.entries(theme.typography).forEach(([k, v]) => vars.push(`  ${k}: ${v};`));
        }
        // Shape
        if (theme.shape) {
            Object.entries(theme.shape).forEach(([k, v]) => vars.push(`  ${k}: ${v};`));
        }
        // Animations
        if (theme.animations) {
            Object.entries(theme.animations).forEach(([k, v]) => vars.push(`  ${k}: ${v};`));
        }

        this._styleEl.textContent = `:root {\n${vars.join('\n')}\n}`;

        // 2. Apply effects
        this._applyEffects(theme);

        // 3. Apply light-mode body class if needed
        if (theme.meta?.type === 'light') {
            document.body.classList.add('tpt-light-mode');
        } else {
            document.body.classList.remove('tpt-light-mode');
        }
    }

    _applyEffects(theme) {
        const fx = theme.effects || {};
        const rules = [];

        // ── Vignette + Film Grain (Reverse: 1999) ────────────────────────
        if (fx.vignette || fx.filmGrain) {
            const vigColor = fx.vignetteColor || 'rgba(14, 13, 12, 0.85)';
            const grainOpacity = fx.grainOpacity ?? 0.06;

            // Noise SVG via data URI
            const noiseSVG = encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200"><filter id="n"><feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="4" stitchTiles="stitch"/><feColorMatrix type="saturate" values="0"/></filter><rect width="200" height="200" filter="url(#n)" opacity="${grainOpacity * 10}"/></svg>`);

            let bgLayers = [];
            if (fx.vignette) {
                bgLayers.push(`radial-gradient(ellipse at center, transparent 38%, ${vigColor} 130%)`);
            }
            if (fx.filmGrain) {
                bgLayers.push(`url("data:image/svg+xml,${noiseSVG}") repeat`);
            }

            if (bgLayers.length > 0) {
                rules.push(`
body::after {
  content: "";
  position: fixed;
  inset: 0;
  pointer-events: none;
  background: ${bgLayers.join(',\n             ')};
  mix-blend-mode: multiply;
  opacity: ${fx.filmGrain ? grainOpacity + 0.5 : 0.7};
  z-index: 99999;
  animation: tpt-grain-flicker 0.15s steps(2) infinite;
}
@keyframes tpt-grain-flicker {
  0%   { transform: translate(0, 0); }
  50%  { transform: translate(-1px, 1px); }
  100% { transform: translate(1px, -1px); }
}`);
            }
        }

        // ── Grid Lines (Blue Archive) ─────────────────────────────────────
        if (fx.gridLines) {
            const gridColor = fx.gridColor || 'rgba(191, 223, 255, 0.3)';
            const gridSize  = fx.gridSize  || '28px';
            rules.push(`
body::before {
  content: "";
  position: fixed;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(0deg,   ${gridColor} 1px, transparent 1px),
    linear-gradient(90deg,  ${gridColor} 1px, transparent 1px);
  background-size: ${gridSize} ${gridSize};
  z-index: 0;
}`);
        }

        this._effectsEl.textContent = rules.join('\n');
    }

    _getOrCreateStyle(id) {
        let el = document.getElementById(id);
        if (!el) {
            el = document.createElement('style');
            el.id = id;
            document.head.appendChild(el);
        }
        return el;
    }

    _saveToStorage() {
        const obj = {};
        this.themes.forEach((v, k) => { obj[k] = v; });
        localStorage.setItem(STORAGE_KEY_THEMES, JSON.stringify(obj));
    }

    _loadFromStorage() {
        try {
            const raw = localStorage.getItem(STORAGE_KEY_THEMES);
            if (!raw) return;
            const obj = JSON.parse(raw);
            Object.entries(obj).forEach(([k, v]) => this.themes.set(k, v));
        } catch (e) {
            console.warn('[ThemeEngine] Failed to load saved themes:', e);
        }
    }
}

// Singleton export
export const themeEngine = new ThemeEngine();
export { BUILTIN_ID };
