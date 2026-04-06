import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ClashBanner from '../ClashBanner';

// Mock react-i18next
vi.mock('react-i18next', () => ({
    useTranslation: () => ({
        t: (key, options) => {
            if (key === 'actions.showAll') return `Show all (${options?.count})`;
            if (key === 'actions.showLess') return 'Show less';
            return key; // Returns 'clash.detected'
        }
    }),
}));

describe('ClashBanner Component', () => {
    const mockEvents = [
        { id: '1', title: 'Meeting A' },
        { id: '2', title: 'Meeting B' },
        { id: '3', title: 'Event C' },
        { id: '4', title: 'Event D' },
        { id: '5', title: 'Event E' }
    ];

    it('returns null if no clashes provided', () => {
        const { container } = render(<ClashBanner clashes={[]} events={mockEvents} />);
        expect(container.firstChild).toBeNull();
    });

    it('renders unique clashes and limits initial display to 3', () => {
        const mockClashes = [
            { eventId: '1', clashWithId: '2', start: new Date(), end: new Date() },
            { eventId: '2', clashWithId: '1', start: new Date(), end: new Date() }, // duplicate pair
            { eventId: '1', clashWithId: '3', start: new Date(), end: new Date() },
            { eventId: '4', clashWithId: '5', start: new Date(), end: new Date() },
            { eventId: '2', clashWithId: '4', start: new Date(), end: new Date() }
        ];

        render(<ClashBanner clashes={mockClashes} events={mockEvents} />);

        // Should render main alert text mapping to 'clash.detected'
        expect(screen.getByText('clash.detected')).toBeInTheDocument();

        // Should render string connecting Meeting A and Meeting B
        // The display logic says `<span className="font-medium">{clash.eventA}</span> - <span className="font-medium">{clash.eventB}</span>`
        // We can just look for Meeting A and Meeting B rendered as text
        expect(screen.getAllByText('Meeting A').length).toBeGreaterThan(0);
        expect(screen.getAllByText('Meeting B').length).toBeGreaterThan(0);

        // Given 4 unique pairs, isExpanded is false, so it shows 3 items initially
        const listItems = screen.getAllByRole('listitem');
        expect(listItems.length).toBe(3);

        // Verify "Show all" button is present
        const showAllButton = screen.getByText('Show all (4)');
        expect(showAllButton).toBeInTheDocument();

        // Click to expand
        fireEvent.click(showAllButton);

        // Now all 4 items should be visible
        const expandedItems = screen.getAllByRole('listitem');
        expect(expandedItems.length).toBe(4);

        // Verify "Show less" button is present
        expect(screen.getByText('Show less')).toBeInTheDocument();
    });

    it('calls onHighlight when a clash item is clicked', () => {
        const mockOnHighlight = vi.fn();
        const start = new Date('2026-01-01T10:00:00Z');
        const end = new Date('2026-01-01T11:00:00Z');

        const mockClashes = [
            { eventId: '1', clashWithId: '2', start, end }
        ];

        render(<ClashBanner clashes={mockClashes} events={mockEvents} onHighlight={mockOnHighlight} />);

        const button = screen.getByRole('button', { name: /Meeting A - Meeting B/ });
        fireEvent.click(button);

        expect(mockOnHighlight).toHaveBeenCalledTimes(1);
        expect(mockOnHighlight).toHaveBeenCalledWith({
            type: 'clash',
            start,
            end
        });
    });
});
