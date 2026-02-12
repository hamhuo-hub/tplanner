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

export const MASSEY_COLORS = [
    "#377EB8", // 0: Blue
    "#FF7F00", // 1: Orange
    "#E41A1C", // 2: Red
    "#4DAF4A", // 3: Green
    "#984EA3", // 4: Purple
    "#A65628", // 5: Brown
    "#F781BF", // 6: Pink
    "#999999", // 7: Grey
];

// Event Constraints
export const MAX_LENGTH_TITLE = 50;

export const EVENTS_STORAGE_KEY = 'tplanner_events';
