export const eventSchema = {
    title: 'event schema',
    version: 4,
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
        recurrence: { type: ['object', 'null'], additionalProperties: true },
        extras: { type: 'object', additionalProperties: true },
        itemType: { type: 'string' },
        listId: { type: ['string', 'null'] },
        alarmEnabled: { type: 'boolean' },
        alarmOffsetMinutes: { type: 'number' },
        lat: { type: ['number', 'null'] },
        lng: { type: ['number', 'null'] },
        lifecycle: { type: 'string' },
        version:   { type: 'number' },
        updatedAt: { type: 'number' },
        deletedAt: { type: ['number', 'null'] },
    },
    required: ['id', 'title', 'start', 'end']
};

// Preserve unknown sync extensions in extras while keeping the RxDB projection valid.
export function toDatabaseEvent(event) {
    const result = {};
    const extras = { ...event.extras };
    for (const [key, value] of Object.entries(event)) {
        if (key === '_rev' || key === '_meta' || key === '_attachments' || key === '_deleted') continue;
        if (Object.hasOwn(eventSchema.properties, key)) result[key] = value;
        else extras[key] = value;
    }
    if (Object.keys(extras).length || event.extras) result.extras = extras;
    result.start = new Date(event.start).toISOString();
    result.end = new Date(event.end).toISOString();
    result.deletedAt = event.deletedAt instanceof Date ? event.deletedAt.getTime() : (event.deletedAt || 0);
    return result;
}
