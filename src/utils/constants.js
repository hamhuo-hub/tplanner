/**
 * @typedef {Object} Event
 * @property {string} id
 * @property {string} title
 * @property {Date} start
 * @property {Date} end
 * @property {number} colorId - 0-6
 * @property {string} [note]
 */

/**
 * @typedef {Object} Clash
 * @property {string} eventId
 * @property {string} clashWithId
 * @property {number} overlapMinutes
 */

export const MASSEY_COLORS = [
    "bg-blue-600",   // 0: Standard Blue
    "bg-purple-600", // 1: Lecture
    "bg-green-600",  // 2: Lab
    "bg-yellow-500", // 3: Workshop
    "bg-red-500",    // 4: Important
    "bg-gray-500",   // 5: Optional
    "bg-indigo-500", // 6: Other
];

// Event Constraints
export const MAX_LENGTH_TITLE = 50;

export const EVENTS_STORAGE_KEY = 'tplanner_events';
