import { format } from 'date-fns';

/**
 * Converts internal Event objects to a standardized iCalendar (RFC 5545) string
 * @param {Array} events - Array of internal Event objects
 * @returns {string} - The raw .ics string
 */
export function exportToICS(events) {
    if (!events || events.length === 0) return '';

    let icsContent = [
        'BEGIN:VCALENDAR',
        'VERSION:2.0',
        'PRODID:-//tPlanner//Local-First Calendar//EN',
        'CALSCALE:GREGORIAN',
        'METHOD:PUBLISH'
    ];

    events.forEach(event => {
        icsContent.push('BEGIN:VEVENT');
        
        // UID
        icsContent.push(`UID:${event.id || crypto.randomUUID()}`);
        
        // Date formatting: iCalendar uses YYYYMMDDTHHmmssZ
        // date-fns format: yyyyMMdd'T'HHmmss'Z'
        const startStr = format(new Date(event.start), "yyyyMMdd'T'HHmmss'Z'");
        const endStr = format(new Date(event.end), "yyyyMMdd'T'HHmmss'Z'");
        const dtStamp = format(new Date(event.updatedAt || Date.now()), "yyyyMMdd'T'HHmmss'Z'");

        icsContent.push(`DTSTAMP:${dtStamp}`);
        icsContent.push(`DTSTART:${startStr}`);
        icsContent.push(`DTEND:${endStr}`);
        
        // Title
        if (event.title) {
            icsContent.push(`SUMMARY:${event.title.replace(/\n/g, '\\n')}`);
        }
        
        // Description / Notes
        if (event.note) {
            icsContent.push(`DESCRIPTION:${event.note.replace(/\n/g, '\\n')}`);
        }
        
        // Downgrade customized colorId to standard categories to remain compatible
        if (event.colorId !== undefined && event.colorId !== null) {
            icsContent.push(`CATEGORIES:COLOR-${event.colorId}`);
            // Also append experimental COLOR tag (RFC 7986)
            // icsContent.push(`COLOR:turquoise`); // Can map actual colors here if needed
        }

        icsContent.push('END:VEVENT');
    });

    icsContent.push('END:VCALENDAR');
    return icsContent.join('\r\n');
}

/**
 * Downloads the given string as a file in the browser
 */
export function downloadICSFile(icsString, filename = 'tplanner_export.ics') {
    const blob = new Blob([icsString], { type: 'text/calendar;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
}
