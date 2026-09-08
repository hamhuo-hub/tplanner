// A display projection only: storage, exports and the timeline keep every occurrence.
export function seriesIdOf(event) {
    const recurrence = Object.hasOwn(event ?? {}, 'recurrence') ? event.recurrence : event?.extras?._syncV3Recurrence;
    return typeof recurrence?.seriesId === 'string' && recurrence.seriesId ? recurrence.seriesId : null;
}

export function selectNextPendingOccurrences(events, { keepCompleted = false } = {}) {
    const next = new Map();
    const lastCompleted = new Map();
    const compare = (a, b) => new Date(a.start) - new Date(b.start)
        || (a.recurrence?.occurrenceIndex ?? 0) - (b.recurrence?.occurrenceIndex ?? 0)
        || String(a.id).localeCompare(String(b.id));
    for (const event of events) {
        const seriesId = seriesIdOf(event);
        if (event.deletedAt || event.type !== 'task' || !seriesId) continue;
        if (event.completed) {
            if (!lastCompleted.has(seriesId) || compare(event, lastCompleted.get(seriesId)) > 0) lastCompleted.set(seriesId, event);
            continue;
        }
        if (!next.has(seriesId) || compare(event, next.get(seriesId)) < 0) next.set(seriesId, event);
    }
    return events.filter(event => {
        if (event.deletedAt) return false;
        if (event.type !== 'task') return true;
        const seriesId = seriesIdOf(event);
        if (event.completed) return keepCompleted && (!seriesId
            || (!next.has(seriesId) && lastCompleted.get(seriesId) === event));
        return !seriesId || next.get(seriesId) === event;
    });
}
