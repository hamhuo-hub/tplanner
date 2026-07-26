import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';
import Timeline from './Timeline';

vi.mock('react-i18next', () => ({
    useTranslation: () => ({
        t: key => key,
        i18n: { language: 'en' },
    }),
}));

afterEach(cleanup);

describe('Timeline scroll layout', () => {
    it('keeps the hour header and event rows in the same horizontal scroll canvas', async () => {
        const date = new Date(2026, 6, 26);
        const { container } = render(
            <Timeline
                startDate={date}
                endDate={date}
                events={[]}
                onEventClick={vi.fn()}
                onAddEvent={vi.fn()}
            />,
        );

        await waitFor(() => {
            expect(container.querySelector('.event-row')).not.toBeNull();
        });

        const scrollArea = container.querySelector('.timeline-scroll-area');
        const canvas = scrollArea.querySelector('.timeline-canvas');
        const header = canvas.querySelector('.timeline-header');
        const row = canvas.querySelector('.event-row');

        expect(scrollArea.contains(header)).toBe(true);
        expect(scrollArea.contains(row)).toBe(true);
        expect(header.parentElement).toBe(canvas);
        expect(row.closest('.timeline-canvas')).toBe(canvas);
    });

    it('uses matching percentages for header ticks and row hour lines', async () => {
        const date = new Date(2026, 6, 26);
        const { container } = render(
            <Timeline
                startDate={date}
                endDate={date}
                events={[]}
                onEventClick={vi.fn()}
                onAddEvent={vi.fn()}
            />,
        );

        await waitFor(() => {
            expect(container.querySelectorAll('.hour-line')).toHaveLength(24);
        });

        const ticks = [...container.querySelectorAll('.timeline-hour-tick')];
        const lines = [...container.querySelectorAll('.hour-line')];

        expect(ticks).toHaveLength(25);
        lines.forEach((line, index) => {
            expect(ticks[index].style.left).toBe(line.style.left);
        });
    });

    it('does not override the sticky date gutter with an inline position', async () => {
        const date = new Date(2026, 6, 26);
        const { container } = render(
            <Timeline
                startDate={date}
                endDate={date}
                events={[]}
                onEventClick={vi.fn()}
                onAddEvent={vi.fn()}
            />,
        );

        await waitFor(() => {
            expect(container.querySelector('.event-row-date')).not.toBeNull();
        });

        const dateGutter = container.querySelector('.event-row-date');

        expect(dateGutter.style.position).toBe('');
    });

    it('does not load more dates when only the horizontal position changes', async () => {
        const date = new Date(2026, 6, 26);
        const onLoadPrev = vi.fn();
        const onLoadNext = vi.fn();
        const { container } = render(
            <Timeline
                startDate={date}
                endDate={date}
                events={[]}
                onEventClick={vi.fn()}
                onAddEvent={vi.fn()}
                onLoadPrev={onLoadPrev}
                onLoadNext={onLoadNext}
            />,
        );

        await waitFor(() => {
            expect(container.querySelector('.timeline-scroll-area')).not.toBeNull();
        });

        const scrollArea = container.querySelector('.timeline-scroll-area');
        Object.defineProperties(scrollArea, {
            scrollTop: { value: 0, writable: true },
            scrollLeft: { value: 240, writable: true },
            scrollHeight: { value: 1000 },
            clientHeight: { value: 300 },
        });

        fireEvent.scroll(scrollArea);

        expect(onLoadPrev).not.toHaveBeenCalled();
        expect(onLoadNext).not.toHaveBeenCalled();

        scrollArea.scrollTop = 10;
        fireEvent.scroll(scrollArea);

        expect(onLoadPrev).toHaveBeenCalledTimes(1);
        expect(onLoadNext).not.toHaveBeenCalled();
    });
});
