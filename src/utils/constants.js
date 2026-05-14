/**
 * @typedef {Object} Event
 * @property {string} id
 * @property {string} title
 * @property {Date} start
 * @property {Date} end
 * @property {number} colorId - 0-6
 * @property {string} [note]
 * @property {'event'|'status'|'task'} [type] - Default 'event'
 * @property {Array<{id: string, text: string, completed: boolean}>} [checklist] - For 'task' type
 */

export const EVENT_TYPES = {
    EVENT: 'event',
    STATUS: 'status',
    TASK: 'task',
};

/**
 * @typedef {Object} Clash
 * @property {string} eventId
 * @property {string} clashWithId
 * @property {number} overlapMinutes
 */

// Default event color palette — lower saturation, harmonious tones.
// These are also exposed as CSS variables --clr-event-0 … --clr-event-7
// so that .tptheme packages can override them via their "eventColors" field.
export const MASSEY_COLORS = [
    "#5B8FCC", // 0: Steel Blue
    "#C9A84C", // 1: Antique Gold
    "#C0697A", // 2: Dusty Rose
    "#5B9E72", // 3: Sage Green
    "#8B6BAE", // 4: Dusty Lavender
    "#C87D5A", // 5: Burnt Sienna
    "#4A9DA8", // 6: Steel Teal
    "#8A8A8A", // 7: Stone Grey
];

// CSS variable names corresponding to each color slot.
export const EVENT_COLOR_VARS = MASSEY_COLORS.map((_, i) => `--clr-event-${i}`);


export const TIMEZONES = [
    { value: '', label: 'Beijing Time (Default)' },
    { value: 'America/Los_Angeles', label: 'Los Angeles (PT)' },
    { value: 'America/New_York', label: 'New York (ET)' },
    { value: 'Europe/London', label: 'London (GMT)' },
    { value: 'Europe/Paris', label: 'Paris (CET)' },
    { value: 'Asia/Dubai', label: 'Dubai (GST)' },
    { value: 'Asia/Tokyo', label: 'Tokyo (JST)' },
    { value: 'Australia/Sydney', label: 'Sydney (AET)' },
    { value: 'Pacific/Auckland', label: 'Auckland (NZST/NZDT)' },
];

// Event Constraints
export const MAX_LENGTH_TITLE = 50;

export const EVENTS_STORAGE_KEY = 'tplanner_events';
