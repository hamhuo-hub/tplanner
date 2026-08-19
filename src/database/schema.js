export const eventSchema = {
    title: 'event schema',
    version: 3,
    primaryKey: 'id',
    type: 'object',
    properties: {
        id: { type: 'string', maxLength: 100 },
        title: { type: 'string' },
        type: { type: 'string' },
        start: { type: 'string', format: 'date-time' },
        end: { type: 'string', format: 'date-time' },
        timezone: { type: 'string' },
        note: { type: 'string' },
        colorId: { type: 'number' },
        checklist: { type: 'array', items: { type: 'object' } },
        completed: { type: 'boolean' },
        recurrenceType: { type: 'string' },
        recurrenceCount: { type: 'number' },
        version:   { type: 'number' },
        updatedAt: { type: 'number' },
        deletedAt: { type: 'number' },
    },
    required: ['id', 'title', 'start', 'end']
};
