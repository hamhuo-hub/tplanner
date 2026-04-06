export const eventSchema = {
    title: 'event schema',
    version: 0,
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
        updatedAt: { type: 'number' } // For future CRDT / replication use
    },
    required: ['id', 'title', 'start', 'end']
};
