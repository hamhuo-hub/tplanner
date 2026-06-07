export const goalSchema = {
    title: 'goal schema',
    version: 0,
    primaryKey: 'id',
    type: 'object',
    properties: {
        id:        { type: 'string', maxLength: 100 },
        title:     { type: 'string' },
        color:     { type: 'string' },              // hex color, e.g. '#5B8FCC'
        note:      { type: 'string' },              // markdown body
        icon:      { type: 'string' },              // base64 or URL; empty = none
        q:         { type: 'number' },              // hex grid q-coordinate
        r:         { type: 'number' },              // hex grid r-coordinate
        s:         { type: 'number' },              // hex grid s-coordinate (= -q-r)
        order:     { type: 'number' },              // display order fallback
        updatedAt: { type: 'number' },
        deletedAt: { type: 'number' },              // tombstone; 0 = alive
    },
    required: ['id', 'title', 'color', 'order'],
};

export const eventSchema = {
    title: 'event schema',
    version: 1,
    primaryKey: 'id',
    type: 'object',
    properties: {
        id: { type: 'string', maxLength: 100 },
        title: { type: 'string' },
        type: { type: 'string' },
        start: { type: 'string', format: 'date-time' }, // ISO String
        end: { type: 'string', format: 'date-time' },   // ISO String
        timezone: { type: 'string' },
        note: { type: 'string' },
        colorId: { type: 'number' },
        groupId: { type: 'string' },
        checklist: {
            type: 'array',
            items: { type: 'object' }
        },
        completed: { type: 'boolean' },
        recurrenceType: { type: 'string' },
        recurrenceCount: { type: 'number' },
        updatedAt: { type: 'number' },
        deletedAt: { type: 'number' } // tombstone timestamp; 0 or absent = alive
    },
    required: ['id', 'title', 'start', 'end']
};
