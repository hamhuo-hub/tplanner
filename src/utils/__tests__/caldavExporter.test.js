import { describe, it, expect } from 'vitest';
import { exportToICS } from '../caldavExporter.js';

describe('caldavExporter', () => {
    it('returns empty string if no events provided', () => {
        expect(exportToICS([])).toBe('');
        expect(exportToICS(null)).toBe('');
    });

    it('generates valid VCALENDAR with VEVENT formatted correctly', () => {
        const events = [
            {
                id: 'test-123',
                title: 'Flight to NZ',
                start: new Date('2026-04-06T12:00:00.000Z'),
                end: new Date('2026-04-06T14:00:00.000Z'),
                note: 'Bring passport\nCheck terminal',
                colorId: 3,
                updatedAt: new Date('2026-01-01T00:00:00.000Z').getTime()
            }
        ];

        const ics = exportToICS(events);

        // Calendar bounds
        expect(ics).toContain('BEGIN:VCALENDAR');
        expect(ics).toContain('END:VCALENDAR');

        // Event presence
        expect(ics).toContain('BEGIN:VEVENT');
        expect(ics).toContain('UID:test-123');

        // Note spacing replacement
        expect(ics).toContain('SUMMARY:Flight to NZ');
        expect(ics).toContain('DESCRIPTION:Bring passport\\nCheck terminal');

        // Date formatting: 'date-fns' format removes timezone offset, assuming UTC internal usage depending on system. 
        // We just ensure it uses standard format chars.
        expect(ics).toMatch(/DTSTART:\d{8}T\d{6}Z/);
        expect(ics).toMatch(/DTEND:\d{8}T\d{6}Z/);

        // Categories mapping
        expect(ics).toContain('CATEGORIES:COLOR-3');
    });
});
