// @vitest-environment jsdom
import { render } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import EventBlock from './EventBlock';
import { event } from '../design-system';

vi.mock('react-i18next', () => ({
    useTranslation: () => ({ t: key => key }),
}));

const base = {
    id: 'ev-1',
    title: 'Test event',
    type: 'task',
    colorId: 0,
    start: new Date(2026, 8, 1, 9, 0),
    end: new Date(2026, 8, 1, 10, 0),
    checklist: [],
};

const accent = 'var(--clr-event-0, #5B8FCC)';

function block(props = {}) {
    const { container } = render(<EventBlock event={base} onClick={() => {}} {...props} />);
    return container.querySelector('.event-block');
}

describe('EventBlock state emphasis comes from design tokens', () => {
    test('normal state uses the token surface and full opacity', () => {
        const el = block();
        expect(el.style.backgroundColor).toBe(event.surface(accent, 'normal'));
        expect(el.style.opacity).toBe('1');
        expect(el.style.filter).toBe('');
    });

    test('completed state sinks via token opacity and de-saturation', () => {
        const el = block({ event: { ...base, completed: true } });
        expect(el.style.opacity).toBe(String(event.opacity.completed));
        expect(el.style.filter).toBe(event.completedFilter);
        expect(el.style.backgroundColor).toBe(event.surface(accent, 'completed'));
    });

    test('shadow state is dim but keeps the normal surface', () => {
        const el = block({ isShadow: true });
        expect(el.style.opacity).toBe(String(event.opacity.shadow));
        expect(el.style.backgroundColor).toBe(event.surface(accent, 'shadow'));
    });

    test('selected state raises the accent share and draws the token outline', () => {
        const el = block({ isSelected: true });
        expect(el.style.backgroundColor).toBe(event.surface(accent, 'selected'));
        expect(el.style.outline).toContain(event.outline.selected);
        expect(el.style.outline).toContain(event.outline.selectedWidth);
    });

    test('conflict overrides the border with the semantic conflict color', () => {
        const el = block({ isConflicting: true });
        // jsdom normalizes #C0392B to rgb(192, 57, 43)
        expect(el.style.borderColor).toBe('rgb(192, 57, 43)');
    });
});
