import { describe, it, expect } from 'vitest';
import {
    colors,
    event,
    installDesignTokens,
    semantic,
    timeline,
    typography,
} from './tokens';

describe('design tokens — Type vs State separation', () => {
    it('typography keeps only the three shared faces (no per-component font)', () => {
        expect(Object.keys(typography).sort()).toEqual(['body', 'display', 'mono', 'taskBadge', 'taskTitle']);
        expect(typography).not.toHaveProperty('task');
    });

    it('semantic layer references primitive colors only', () => {
        expect(semantic.surface.default).toBe(colors.surfaceRaised);
        expect(semantic.text.primary).toBe(colors.textPrimary);
        expect(semantic.text.disabled).toBe(colors.textMuted);
        expect(semantic.border.selected).toBe(colors.gold);
        expect(semantic.border.conflict).toBe(colors.red);
    });

    it('state emphasis is ordered: selected > normal > completed', () => {
        const { selected, normal, completed } = event.surfaceAccentMix;
        expect(selected).toBeGreaterThan(normal);
        expect(normal).toBeGreaterThan(completed);
    });

    it('surface/border mix encode the state weight', () => {
        const accent = 'var(--clr-event-0)';
        expect(event.surface(accent, 'selected')).toContain('72%');
        expect(event.surface(accent, 'normal')).toContain('50%');
        expect(event.surface(accent, 'completed')).toContain('30%');
        expect(event.border(accent, 'selected')).toContain('90%');
        expect(event.border(accent, 'completed')).toContain('55%');
    });

    it('only state decides opacity; normal and selected are fully opaque', () => {
        expect(event.opacity.normal).toBe(1);
        expect(event.opacity.selected).toBe(1);
        expect(event.opacity.shadow).toBeLessThan(1);
        expect(event.opacity.completed).toBeLessThan(1);
    });

    it('installDesignTokens registers semantic vars and no per-component font', () => {
        const set = new Map();
        const root = { style: { setProperty: (name, value) => set.set(name, value) } };
        installDesignTokens(root);

        expect(set.has('--font-task')).toBe(false);
        expect(set.get('--border-conflict')).toBe(colors.red);
        expect(set.get('--border-selected')).toBe(colors.gold);
        expect(set.get('--surface-selected')).toBe(colors.control);
        expect(set.get('--text-disabled')).toBe(colors.textMuted);
        expect(set.get('--clr-event-0')).toBeTruthy();
    });
});

describe('design tokens — timeline layout layer stays intact', () => {
    it('overlapReveal equals the full title-bar height', () => {
        expect(timeline.eventHeaderHeight).toBe(5 + 18 + 5);
    });
});
