import { lightTokens as t } from '../generated/tplanner-light';

/** Pass to createTheme(). Component overrides here cover the shared baseline, not every app screen. */
export function createLightThemeOptions(platform: 'web' | 'desktop' = 'web') {
  const c = t.semantic.color;
  const p = t.platform[platform];
  const button = t.component.button;
  const family = p.typography.body.fontFamily.map(name =>
    name.includes(' ') ? JSON.stringify(name) : name).join(', ');
  const type = (role: keyof typeof p.typography) => ({
    fontFamily: family,
    fontSize: `${p.typography[role].fontSize / 16}rem`,
    fontWeight: p.typography[role].fontWeight,
    lineHeight: p.typography[role].lineHeight,
    letterSpacing: `${p.typography[role].letterSpacing / 16}rem`,
  });
  const focus = {
    outline: `${button.focus.width}px solid ${button.focus.color}`,
    outlineOffset: button.focus.offset,
  };

  return {
    palette: {
      mode: 'light' as const,
      primary: { main: c.accent, light: c.accentHover, dark: c.accentPressed, contrastText: c.onAccent },
      secondary: { main: c.accentText, contrastText: c.raised },
      background: { default: c.canvas, paper: c.surface },
      text: { primary: c.textPrimary, secondary: c.textSecondary, disabled: c.disabledForeground },
      divider: c.borderSubtle,
      error: { main: c.error }, success: { main: c.success },
      info: { main: c.info }, warning: { main: c.warning },
      action: { hover: c.hoverBackground, selected: c.selectedBackground,
        disabled: c.disabledForeground, disabledBackground: c.disabledBackground },
    },
    shape: { borderRadius: t.semantic.radius.control },
    typography: {
      fontFamily: family,
      h1: type('heading'), h2: type('heading'), h3: type('title'),
      h4: type('title'), h5: type('title'), h6: type('title'),
      body1: type('body'), body2: type('meta'), subtitle1: type('taskTitle'),
      caption: type('meta'), button: { ...type('body'), textTransform: 'none' as const },
    },
    components: {
      MuiCssBaseline: { styleOverrides: { body: { backgroundColor: c.canvas, color: c.textPrimary } } },
      MuiButton: {
        styleOverrides: {
          root: {
            minHeight: p.geometry.controlMinHeight,
            borderRadius: button.radius, boxShadow: 'none',
            '@media (pointer: coarse)': { minHeight: p.geometry.touchTargetMin },
            '&.Mui-focusVisible': focus,
            '&.Mui-disabled': { color: button.disabled.foreground,
              backgroundColor: button.disabled.background, opacity: 1 },
          },
          containedPrimary: {
            backgroundColor: button.primary.background, color: button.primary.foreground,
            border: `${t.semantic.stroke.control}px solid ${button.primary.border}`,
            '&:hover': { backgroundColor: button.primary.hoverBackground, boxShadow: 'none' },
            '&:active': { backgroundColor: button.primary.pressedBackground, boxShadow: 'none' },
          },
          outlinedPrimary: {
            color: c.accentText, borderColor: c.borderControl,
            '&:hover': { backgroundColor: c.hoverBackground, borderColor: c.focus },
          },
          textPrimary: { color: c.accentText, '&:hover': { backgroundColor: c.selectedBackground } },
        },
      },
      MuiLink: { styleOverrides: { root: { color: c.accentText, '&:focus-visible': focus } } },
      MuiOutlinedInput: {
        styleOverrides: {
          root: {
            ...type('body'), backgroundColor: c.input, color: c.textPrimary,
            minHeight: p.geometry.controlMinHeight,
            '& .MuiOutlinedInput-notchedOutline': { borderColor: c.borderControl },
            '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: c.focus },
            '&.Mui-focused .MuiOutlinedInput-notchedOutline': { borderColor: c.focus },
            '&.Mui-error .MuiOutlinedInput-notchedOutline': { borderColor: c.error },
          },
          input: { '&::placeholder': { color: c.textMuted, opacity: 1 } },
        },
      },
      MuiInputLabel: { styleOverrides: { root: { color: c.textSecondary,
        '&.Mui-focused': { color: c.focus }, '&.Mui-error': { color: c.error } } } },
      MuiCheckbox: { styleOverrides: { root: { color: c.borderControl,
        '&.Mui-checked': { color: c.accentText }, '&.Mui-focusVisible': focus } } },
      MuiDialog: { styleOverrides: { paper: {
        backgroundColor: t.component.dialog.background,
        borderRadius: t.component.dialog.radius,
        border: `${t.component.panel.edgeWidth}px solid ${t.component.panel.edge}`,
        boxShadow: `${t.component.dialog.shadowX}px ${t.component.dialog.shadowY}px ${t.component.dialog.shadowBlur}px ${t.component.dialog.shadowSpread}px color-mix(in srgb, ${t.component.dialog.shadowColor} ${t.component.dialog.shadowOpacity * 100}%, transparent)`,
      } } },
    },
  };
}

/** Keep persisted colorId values and their order. Prefer foreground/background together. */
export const categoryTokens = [
  t.semantic.category.id0, t.semantic.category.id1, t.semantic.category.id2, t.semantic.category.id3,
  t.semantic.category.id4, t.semantic.category.id5, t.semantic.category.id6, t.semantic.category.id7,
] as const;

export function categoryForId(colorId: number) {
  return categoryTokens[Number.isInteger(colorId) && colorId >= 0 && colorId < 8 ? colorId : 0];
}
