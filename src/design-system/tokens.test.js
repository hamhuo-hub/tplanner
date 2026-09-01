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

    it('named surface/border tokens encode the design-system mix values', () => {
        const accent = 'var(--clr-event-0)';
        expect(event.surface(accent)).toContain('65%');
        expect(event.selectedSurface(accent)).toContain('82%');
        expect(event.completedSurface(accent)).toContain('30%');
        expect(event.border(accent)).toContain('85%');
        expect(event.selectedBorder(accent)).toContain('95%');
        expect(event.completedBorder(accent)).toContain('55%');
        // Dispatch helpers stay consistent with the named tokens.
        expect(event.surfaceFor(accent, 'selected')).toBe(event.selectedSurface(accent));
        expect(event.surfaceFor(accent, 'completed')).toBe(event.completedSurface(accent));
        expect(event.surfaceFor(accent, 'shadow')).toBe(event.surface(accent));
    });

    it('only state decides opacity; normal and selected are fully opaque', () => {
        expect(event.opacity.normal).toBe(1);
        expect(event.opacity.selected).toBe(1);
        expect(event.shadowOpacity).toBeLessThan(1);
        expect(event.completedOpacity).toBeLessThan(1);
        expect(event.opacityFor('completed')).toBe(event.completedOpacity);
    });

    it('event text is an explicit semantic token', () => {
        expect(event.text).toBe(semantic.text.primary);
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
        expect(set.get('--event-text')).toBe(semantic.text.primary);
        expect(set.get('--event-header-height')).toBe(`${timeline.eventHeaderHeight}px`);
    });
});

describe('design tokens — timeline layout layer stays intact', () => {
    it('overlapReveal equals the full title-bar height', () => {
        expect(timeline.eventHeaderHeight).toBe(5 + 18 + 5);
    });
});
