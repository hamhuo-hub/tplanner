// Canonical V3 task payload normalization.
//
// Earlier snapshots preserved historical phone fields verbatim. Keep every
// unknown field, but lift the fields now owned by V3 into their canonical
// objects and normalize checklist `text` to `title` without losing content.

function isObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

const CANONICAL_TASK_FIELDS = new Set([
  'title',
  'note',
  'completed',
  'itemType',
  'schedule',
  'recurrence',
  'listId',
  'colorId',
  'alarm',
  'location',
  'extras',
  'checklist',
]);

export function createCanonicalTaskDefaults() {
  return {
    title: '',
    note: '',
    completed: false,
    itemType: 'task',
    schedule: null,
    recurrence: null,
    alarm: { enabled: false, offsetMinutes: 0 },
    colorId: 0,
    location: { lat: null, lng: null },
    extras: {},
    listId: null,
    checklist: [],
  };
}

export function canonicalizeChecklistItem(item) {
  if (!isObject(item)) return item;
  if (!Object.hasOwn(item, 'text') && typeof item.title === 'string') return item;
  const { text, ...rest } = item;
  const title = typeof item.title === 'string'
    ? item.title
    : (typeof text === 'string' ? text : '');
  return { ...rest, title };
}

export function canonicalizeTaskPayload(payload) {
  if (!isObject(payload)) return payload;
  const next = { ...payload };

  if (!Object.hasOwn(next, 'schedule') && (
    Object.hasOwn(payload, 'start') || Object.hasOwn(payload, 'end')
  )) {
    next.schedule = {
      startAt: payload.start ?? null,
      endAt: payload.end ?? null,
    };
    delete next.start;
    delete next.end;
  }

  if (!Object.hasOwn(next, 'itemType') && typeof payload.type === 'string') {
    next.itemType = payload.type;
    delete next.type;
  }

  if (Array.isArray(payload.checklist)) {
    next.checklist = payload.checklist.map(canonicalizeChecklistItem);
  }

  if (!Object.hasOwn(next, 'recurrence')) {
    const extras = isObject(payload.extras) ? payload.extras : {};
    const frequency = payload.recurrenceType ?? extras.recurrenceType;
    const count = payload.recurrenceCount ?? extras.recurrenceCount;
    if (frequency !== undefined || count !== undefined) {
      next.recurrence = !frequency || frequency === 'none'
        ? null
        : {
            frequency,
            count: Math.max(1, Number(count) || 1),
          };
    }
  }
  delete next.recurrenceType;
  delete next.recurrenceCount;

  if (!Object.hasOwn(next, 'alarm') && (
    Object.hasOwn(payload, 'alarmEnabled') || Object.hasOwn(payload, 'alarmOffsetMinutes')
  )) {
    const rawOffset = payload.alarmOffsetMinutes;
    next.alarm = {
      enabled: Boolean(payload.alarmEnabled),
      offsetMinutes: Number.isInteger(Number(rawOffset)) ? Number(rawOffset) : 0,
    };
  }
  delete next.alarmEnabled;
  delete next.alarmOffsetMinutes;

  if (!Object.hasOwn(next, 'location') && (
    Object.hasOwn(payload, 'lat') || Object.hasOwn(payload, 'lng')
  )) {
    const coordinate = (value) => value == null || !Number.isFinite(Number(value)) ? null : Number(value);
    next.location = { lat: coordinate(payload.lat), lng: coordinate(payload.lng) };
  }
  delete next.lat;
  delete next.lng;

  // V3 keeps forward-compatible/unknown task data in one explicit bag. Existing
  // extras win over duplicate historical root fields; no value is discarded.
  const extras = isObject(payload.extras) ? { ...payload.extras } : {};
  for (const [key, value] of Object.entries(next)) {
    if (!CANONICAL_TASK_FIELDS.has(key)) {
      if (!Object.hasOwn(extras, key)) extras[key] = value;
      delete next[key];
    }
  }
  if (Object.keys(extras).length > 0 || Object.hasOwn(payload, 'extras')) next.extras = extras;

  const defaults = createCanonicalTaskDefaults();
  const canonical = { ...defaults, ...next };
  if (isObject(canonical.schedule)) {
    canonical.schedule = { startAt: null, endAt: null, ...canonical.schedule };
  }
  if (isObject(canonical.alarm)) {
    canonical.alarm = { ...defaults.alarm, ...canonical.alarm };
  }
  if (isObject(canonical.location)) {
    canonical.location = { ...defaults.location, ...canonical.location };
  }
  return canonical;
}

export function taskPayloadNeedsCanonicalization(payload) {
  return JSON.stringify(payload) !== JSON.stringify(canonicalizeTaskPayload(payload));
}
