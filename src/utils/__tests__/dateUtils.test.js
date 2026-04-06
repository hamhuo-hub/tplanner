import { describe, it, expect } from 'vitest';
import { checkForClashes } from '../dateUtils.js';

describe('dateUtils: checkForClashes', () => {
    it('returns empty array for no events or single event', () => {
        expect(checkForClashes([])).toHaveLength(0);
        expect(checkForClashes([
            { id: '1', start: new Date('2026-01-01T10:00'), end: new Date('2026-01-01T11:00'), type: 'event' }
        ])).toHaveLength(0);
    });

    it('detects overlap between two events of same type', () => {
        const events = [
            { id: '1', start: new Date('2026-01-01T10:00'), end: new Date('2026-01-01T12:00'), type: 'event' },
            { id: '2', start: new Date('2026-01-01T11:00'), end: new Date('2026-01-01T13:00'), type: 'event' }
        ];
        
        const clashes = checkForClashes(events);
        
        // Both get an entry pointing to each other
        expect(clashes).toHaveLength(2);
        expect(clashes.some(c => c.eventId === '1' && c.clashWithId === '2')).toBe(true);
        expect(clashes.some(c => c.eventId === '2' && c.clashWithId === '1')).toBe(true);
    });

    it('ignores clashes between different event types (event vs task)', () => {
        const events = [
            { id: '1', start: new Date('2026-01-01T10:00'), end: new Date('2026-01-01T12:00'), type: 'event' },
            { id: '2', start: new Date('2026-01-01T11:00'), end: new Date('2026-01-01T13:00'), type: 'task' }
        ];
        const clashes = checkForClashes(events);
        expect(clashes).toHaveLength(0);
    });

    it('ignores clashes entirely for status type events', () => {
        const events = [
            { id: '1', start: new Date('2026-01-01T10:00'), end: new Date('2026-01-01T12:00'), type: 'status' },
            { id: '2', start: new Date('2026-01-01T11:00'), end: new Date('2026-01-01T13:00'), type: 'status' }
        ];
        expect(checkForClashes(events)).toHaveLength(0);
    });
});
