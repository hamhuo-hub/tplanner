import { seriesIdOf } from './recurringTaskSelection.mjs';
import { v3 as uuidV3 } from 'uuid';

const FREQUENCIES = new Set(['daily', 'weekly', 'monthly']);
const DAY = 86_400_000;
const sharedFields = ['title', 'note', 'colorId', 'type', 'alarmEnabled', 'alarmOffsetMinutes', 'listId', 'lat', 'lng'];
const RETIRED_IDS = '_retiredRecurringOccurrenceIds';
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const countOf = value => Math.max(1, Math.min(50, Math.trunc(Number(value) || 1)));
const localZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
const iso = value => new Date(value).toISOString();

/** Recurrence is authoritative; scalar fields are the editor's compatibility projection. */
export function recurrenceOf(event) {
    if (event?.recurrence === null) return null;
    const raw = event?.recurrence ?? event?.extras?._syncV3Recurrence;
    if (raw && typeof raw === 'object' && !Array.isArray(raw)) return { ...raw };
    return FREQUENCIES.has(event?.recurrenceType)
        ? { frequency: event.recurrenceType, count: countOf(event.recurrenceCount) } : null;
}

export function withRecurrence(event, recurrence) {
    const clean = { ...event, recurrence, recurrenceType: recurrence?.frequency ?? 'none', recurrenceCount: recurrence?.count ?? 1 };
    delete clean.groupId;
    if (clean.extras) {
        clean.extras = { ...clean.extras };
        delete clean.extras.groupId;
        delete clean.extras._syncV3Recurrence;
    }
    return clean;
}

export function detachFromSeries(event) {
    const retired = [...new Set([...(event.extras?.[RETIRED_IDS] ?? []), ...(recurrenceOf(event)?.retiredOccurrenceIds ?? [])])];
    return withRecurrence(retired.length ? { ...event, extras: { ...event.extras, [RETIRED_IDS]: retired } } : event, null);
}

/** Delete only explicit occurrences, retaining an ID ledger on surviving siblings. */
export function deleteRecurringOccurrences(events, ids, now = Date.now()) {
    const targets = new Set(ids);
    const all = recoverLegacySeries(events);
    const retiredBySeries = new Map();
    for (const event of all) {
        const seriesId = seriesIdOf(event);
        if (!seriesId || !targets.has(event.id)) continue;
        if (!retiredBySeries.has(seriesId)) retiredBySeries.set(seriesId, new Set());
        retiredBySeries.get(seriesId).add(event.id);
    }
    return all.flatMap(event => {
        const retired = retiredBySeries.get(seriesIdOf(event));
        if (!targets.has(event.id) && (event.deletedAt || !retired)) return [];
        const ledger = [...new Set([...(event.extras?.[RETIRED_IDS] ?? []),
            ...(recurrenceOf(event)?.retiredOccurrenceIds ?? []), ...(retired ?? [])])];
        return [{ ...event, ...(targets.has(event.id) ? { deletedAt: now } : {}),
            ...(ledger.length ? { extras: { ...event.extras, [RETIRED_IDS]: ledger } } : {}) }];
    });
}

/** An explicit old groupId is evidence; matching titles or schedules are never evidence. */
export function recoverLegacySeries(events) {
    const foundIds = new Set(events.map(event => event.id));
    const provenGroups = new Map();
    for (const event of events) {
        const recurrence = recurrenceOf(event);
        if (seriesIdOf(event) || !FREQUENCIES.has(recurrence?.frequency)) continue;
        const matches = [];
        for (let index = 1; index < countOf(recurrence.count); index++) {
            const id = legacyAndroidOccurrenceId(event.id, index);
            if (foundIds.has(id)) matches.push([id, index]);
        }
        if (matches.length) {
            provenGroups.set(event.id, { root: event, index: 0 });
            for (const [id, index] of matches) provenGroups.set(id, { root: event, index });
        }
    }
    const proven = events.map(event => {
        const match = provenGroups.get(event.id);
        if (!match || seriesIdOf(event) || event.groupId || event.extras?.groupId) return event;
        const base = recurrenceOf(match.root);
        return withRecurrence(event, { ...recurrenceOf(event), frequency: base.frequency,
            count: countOf(base.count), seriesId: match.root.id, occurrenceIndex: match.index,
            anchorStartAt: iso(match.root.start), anchorEndAt: iso(match.root.end),
            timeZone: match.root.timezone || base.timeZone || localZone() });
    });
    const groups = new Map();
    for (const event of proven) {
        const group = event.groupId ?? event.extras?.groupId;
        if (!group || seriesIdOf(event) || !FREQUENCIES.has(recurrenceOf(event)?.frequency)) continue;
        if (!groups.has(group)) groups.set(group, []);
        groups.get(group).push(event);
    }
    const repaired = new Map();
    for (const [group, members] of groups) {
        members.sort((a, b) => new Date(a.start) - new Date(b.start) || String(a.id).localeCompare(String(b.id)));
        const first = members[0];
        const base = recurrenceOf(first);
        members.forEach((event, occurrenceIndex) => repaired.set(event.id, withRecurrence(event, {
            ...recurrenceOf(event), frequency: base.frequency,
            count: countOf(Math.max(members.length, ...members.map(e => recurrenceOf(e)?.count || 1))),
            seriesId: String(group), occurrenceIndex,
            anchorStartAt: iso(first.start), anchorEndAt: iso(first.end),
            timeZone: first.timezone || base.timeZone || localZone(),
        })));
    }
    return proven.map(event => repaired.get(event.id) ?? event);
}

export function legacyAndroidOccurrenceId(rootId, index) {
    const bytes = new TextEncoder().encode(`${rootId}:recurrence:${index}`);
    // Java's nameUUIDFromBytes hashes exactly these bytes, with no namespace.
    // Historic Android roots are UUIDs; short arbitrary Web IDs cannot be such roots.
    if (bytes.length < 16) return null;
    return uuidV3(bytes.slice(16), bytes.slice(0, 16));
}

const formatters = new Map();
function wallTime(epoch, timeZone) {
    if (!formatters.has(timeZone)) formatters.set(timeZone, new Intl.DateTimeFormat('en-CA', {
        timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit',
        minute: '2-digit', second: '2-digit', hourCycle: 'h23',
    }));
    const parts = Object.fromEntries(formatters.get(timeZone).formatToParts(new Date(epoch)).map(p => [p.type, p.value]));
    return Date.UTC(+parts.year, +parts.month - 1, +parts.day, +parts.hour, +parts.minute, +parts.second)
        + ((epoch % 1000) + 1000) % 1000;
}

// Match java.time: earlier offset in overlaps; move forward through a DST gap.
function instantFromWall(wall, timeZone) {
    const offsets = new Set([-2, -1, 0, 1, 2].map(days => {
        const sample = wall + days * DAY;
        return wallTime(sample, timeZone) - sample;
    }));
    const candidates = [...offsets].map(offset => wall - offset);
    const matches = candidates.filter(value => wallTime(value, timeZone) === wall);
    if (matches.length) return Math.min(...matches);
    const forward = candidates.filter(value => wallTime(value, timeZone) > wall)
        .sort((a, b) => wallTime(a, timeZone) - wallTime(b, timeZone));
    if (forward.length) return forward[0];
    throw new Error('无法解析所选时区的重复时间');
}

function shiftWall(wall, frequency, index) {
    const date = new Date(wall);
    if (frequency === 'monthly') {
        const day = date.getUTCDate();
        date.setUTCDate(1);
        date.setUTCMonth(date.getUTCMonth() + index);
        const last = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 0)).getUTCDate();
        date.setUTCDate(Math.min(day, last));
    } else date.setUTCDate(date.getUTCDate() + index * (frequency === 'weekly' ? 7 : 1));
    return date.getTime();
}

export function occurrenceAt(anchor, frequency, index, timeZone) {
    return new Date(instantFromWall(shiftWall(wallTime(new Date(anchor).getTime(), timeZone), frequency, index), timeZone));
}

/** Picker Dates hold wall-clock components; convert once, in the selected zone. */
export function scheduleFromEditor({ start, end, timeZone, allDay = false, original = null, dirty = true }) {
    if (original && !dirty) return { start: new Date(original.start), end: new Date(original.end) };
    const convert = (input, isEnd) => {
        const date = new Date(input);
        if (!Number.isFinite(+date)) throw new Error('请输入有效日期');
        const wall = Date.UTC(date.getFullYear(), date.getMonth(), date.getDate(),
            allDay ? (isEnd ? 23 : 0) : date.getHours(),
            allDay ? (isEnd ? 59 : 0) : date.getMinutes(),
            allDay ? (isEnd ? 59 : 0) : date.getSeconds(),
            allDay ? (isEnd ? 999 : 0) : date.getMilliseconds());
        return new Date(instantFromWall(wall, timeZone || localZone()));
    };
    return { start: convert(start, false), end: convert(end, true) };
}

const checklistStructure = items => (items ?? []).map(item => ({ id: item.id, text: item.text ?? item.title ?? '' }));
function sharedPatch(before, after) {
    return Object.fromEntries(sharedFields.filter(key => Object.hasOwn(after, key) && !same(before?.[key], after[key]))
        .map(key => [key, after[key]]));
}

function copyChecklistTemplate(previousTemplate, template, target) {
    const oldItems = target.checklist ?? [];
    return (template ?? []).map(item => {
        const oldIndex = (previousTemplate ?? []).findIndex(old => old.id === item.id);
        const existing = oldItems.find(old => old.id === item.id) ?? (oldIndex >= 0 ? oldItems[oldIndex] : null);
        return { ...item, id: existing?.id ?? item.id, completed: existing?.completed ?? false };
    });
}

function mergeChecklistEdits(original = [], draft = [], latest = []) {
    const previous = new Map(original.map(item => [item.id, item]));
    const desired = new Map(draft.map(item => [item.id, item]));
    const next = latest.filter(item => !previous.has(item.id) || desired.has(item.id)).map(item => {
        const old = previous.get(item.id);
        const edit = desired.get(item.id);
        if (!old || !edit) return item;
        return { ...item, ...Object.fromEntries(Object.keys(edit)
            .filter(key => !same(old[key], edit[key])).map(key => [key, edit[key]])) };
    });
    for (const item of draft) if (!previous.has(item.id) && !next.some(existing => existing.id === item.id)) next.push({ ...item });
    const oldOrder = original.filter(item => desired.has(item.id)).map(item => item.id);
    const newOrder = draft.filter(item => previous.has(item.id)).map(item => item.id);
    if (!same(oldOrder, newOrder)) {
        const rank = new Map(draft.map((item, index) => [item.id, index]));
        next.sort((a, b) => (rank.get(a.id) ?? draft.length) - (rank.get(b.id) ?? draft.length));
    }
    return next;
}

// Compare with the editor snapshot, then apply only that delta to the latest state.
// Completion, recurrence extensions, new remote child items and untouched content survive.
function mergeEditorDraft(latest, original, draft) {
    if (!original || !latest) return { ...latest, ...draft };
    const selected = { ...latest };
    for (const key of [...sharedFields, 'completed']) {
        if (Object.hasOwn(draft, key) && !same(original[key], draft[key])) selected[key] = draft[key];
    }
    for (const key of ['start', 'end']) {
        if (Object.hasOwn(draft, key) && +new Date(original[key]) !== +new Date(draft[key])) selected[key] = draft[key];
    }
    const previousZone = original.timezone || recurrenceOf(original)?.timeZone || localZone();
    if (Object.hasOwn(draft, 'timezone') && (draft.timezone || localZone()) !== previousZone) selected.timezone = draft.timezone;
    if (Object.hasOwn(draft, 'checklist') && !same(original.checklist ?? [], draft.checklist ?? [])) {
        selected.checklist = mergeChecklistEdits(original.checklist, draft.checklist, latest.checklist);
    }
    return selected;
}

/** Content edits propagate, while checking an occurrence or a child only changes that occurrence. */
export function expandSharedContentUpdates(events, updates) {
    const all = recoverLegacySeries(events);
    const byId = new Map(all.map(event => [event.id, event]));
    const result = new Map();
    for (const update of updates) {
        const before = byId.get(update.id);
        const after = { ...before, ...update };
        const seriesId = seriesIdOf(before);
        const scheduleChanged = before && (+new Date(before.start) !== +new Date(after.start)
            || +new Date(before.end) !== +new Date(after.end) || before.timezone !== after.timezone);
        if (seriesId && !after.deletedAt && scheduleChanged && same(before.recurrence, after.recurrence)) {
            // Timeline drags share the same anchor-based edit path as the modal.
            for (const planned of buildRecurringEdit(all, before, after)) result.set(planned.id, planned);
            continue;
        }
        if (seriesId && !after.deletedAt) {
            const patch = sharedPatch(before, after);
            const checklistChanged = !same(checklistStructure(before.checklist), checklistStructure(after.checklist));
            if (Object.keys(patch).length || checklistChanged) {
                for (const member of all) {
                    if (member.deletedAt || seriesIdOf(member) !== seriesId) continue;
                    const next = { ...(result.get(member.id) ?? member), ...patch };
                    if (checklistChanged) next.checklist = copyChecklistTemplate(before.checklist, after.checklist, member);
                    result.set(member.id, next);
                }
            }
        }
        result.set(after.id, after);
    }
    // Explicit batch values win over propagated patches (including per-occurrence completion).
    for (const update of updates) {
        const planned = result.get(update.id);
        // Keep the new anchor metadata produced by a timeline schedule edit.
        const recurrence = planned?.recurrence;
        result.set(update.id, { ...byId.get(update.id), ...planned, ...update,
            ...(recurrence && !same(recurrence, byId.get(update.id)?.recurrence)
                && same(update.recurrence, byId.get(update.id)?.recurrence) ? { recurrence } : {}) });
    }
    return [...result.values()];
}

function stableHash(value) {
    let hash = 2166136261;
    for (const char of value) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619);
    return (hash >>> 0).toString(36);
}

/** Materialize a finite series. Existing IDs and completed history survive every edit. */
export function buildRecurringEdit(events, original, draft, now = Date.now(), { recurrenceChanged = false } = {}) {
    const all = recoverLegacySeries(events);
    // A failed network save may already have durably queued this stable draft ID.
    // Retrying creation edits those occurrences instead of allocating a second set.
    const latest = all.find(event => event.id === (original?.id ?? draft.id));
    const before = latest ?? original;
    if (original && !latest) throw new Error('此任务已不存在，无法保存。请关闭编辑器后重新选择任务。');
    if (before?.deletedAt || before?.lifecycle === 'deleted') {
        throw new Error('此任务已被删除，无法保存。请关闭编辑器后重新选择任务。');
    }
    const old = recurrenceOf(before);
    const originalRule = recurrenceOf(original);
    const seriesId = seriesIdOf(before);
    const wholeSeries = seriesId ? all.filter(event => seriesIdOf(event) === seriesId) : (before ? [before] : []);
    const members = wholeSeries.filter(event => !event.deletedAt);
    const selected = mergeEditorDraft(before, original, draft);
    const ruleEdited = recurrenceChanged || !original
        || (draft.recurrenceType ?? originalRule?.frequency ?? 'none') !== (originalRule?.frequency ?? 'none')
        || countOf(draft.recurrenceCount) !== countOf(originalRule?.count);
    const requestedFrequency = ruleEdited ? draft.recurrenceType : (old?.frequency ?? 'none');
    const frequency = selected.type === 'task' ? (requestedFrequency || 'none') : 'none';
    const count = countOf(ruleEdited ? draft.recurrenceCount : old?.count);
    if (before && old && !seriesId && !ruleEdited
        && frequency === old.frequency && count === countOf(old.count)) {
        // Old random-ID instances have no provable binding. A content edit must never
        // materialize their count again; the user must explicitly change recurrence.
        return [withRecurrence(selected, old)];
    }
    const content = sharedPatch(before, selected);
    const checklistChanged = !same(checklistStructure(before?.checklist), checklistStructure(selected.checklist));
    const memberContent = member => ({
        ...member, ...content,
        ...(checklistChanged ? { checklist: copyChecklistTemplate(before?.checklist, selected.checklist, member) } : {}),
    });

    if (frequency !== 'none' && !FREQUENCIES.has(frequency)) {
        // Future recurrence rules are opaque to this editor. A content edit may update
        // the series but must neither clear nor reinterpret its rule and extensions.
        if (ruleEdited && frequency !== originalRule?.frequency) throw new Error('此重复规则暂不支持编辑，请选择每日、每周、每月或不重复。');
        return (members.length ? members : [selected]).map(member => withRecurrence(
            member.id === selected.id ? selected : memberContent(member), recurrenceOf(member) ?? old));
    }

    if (frequency === 'none') {
        const retired = [...new Set(wholeSeries.flatMap(member => [
            ...(member.id !== selected.id ? [member.id] : []),
            ...(member.extras?.[RETIRED_IDS] ?? []), ...(recurrenceOf(member)?.retiredOccurrenceIds ?? []),
        ]))];
        const detach = event => detachFromSeries(retired.length
            ? { ...event, extras: { ...event.extras, [RETIRED_IDS]: retired } } : event);
        return [detach(selected), ...members.filter(member => member.id !== selected.id).map(member => {
            const next = detach(memberContent(member));
            return member.completed ? next : { ...next, deletedAt: now };
        })];
    }

    const timeZone = selected.timezone || old?.timeZone || localZone();
    const index = seriesId ? Math.max(0, Number(old.occurrenceIndex) || 0) : 0;
    const scheduleChanged = !before || +new Date(before.start) !== +new Date(selected.start)
        || +new Date(before.end) !== +new Date(selected.end) || (before.timezone || old?.timeZone || localZone()) !== timeZone;
    const frequencyChanged = old?.frequency !== frequency;
    function anchorFor(key, oldKey) {
        const chosen = +new Date(selected[key]);
        if (!seriesId || !old?.[oldKey]) return new Date(instantFromWall(shiftWall(wallTime(chosen, timeZone), frequency, -index), timeZone));
        if (!scheduleChanged && !frequencyChanged) return new Date(old[oldKey]);
        if (frequencyChanged) return new Date(instantFromWall(shiftWall(wallTime(chosen, timeZone), frequency, -index), timeZone));
        // Apply the edited occurrence's wall-clock delta to the original anchor; a February
        // clamp must not silently turn a January-31 series into a day-28 series.
        const oldAnchorWall = wallTime(+new Date(old[oldKey]), timeZone);
        const originalInstant = +(new Date(original?.[key] ?? before[key]));
        return new Date(instantFromWall(oldAnchorWall + wallTime(chosen, timeZone) - wallTime(originalInstant, timeZone), timeZone));
    }
    const anchorStart = anchorFor('start', 'anchorStartAt');
    const anchorEnd = anchorFor('end', 'anchorEndAt');
    const identity = seriesId || selected.id;
    const occupied = new Set(all.map(event => event.id));
    for (const member of [...wholeSeries, selected]) for (const id of member.extras?.[RETIRED_IDS] ?? []) occupied.add(id);
    const retired = new Set(wholeSeries.flatMap(member => [
        ...(member.deletedAt ? [member.id] : []), ...(recurrenceOf(member)?.retiredOccurrenceIds ?? []),
    ]));
    retired.forEach(id => occupied.add(id));
    let serial = Math.max(1, ...members.map(member => Number(recurrenceOf(member)?.nextOccurrenceSerial) || 1));
    const byIndex = new Map(members.map(member => [seriesId ? recurrenceOf(member)?.occurrenceIndex : 0, member]));
    const changed = [];
    for (let occurrenceIndex = 0; occurrenceIndex < count; occurrenceIndex++) {
        const existing = byIndex.get(occurrenceIndex);
        // A deliberately deleted occurrence is a hole in the existing series, not an
        // invitation to recreate it when an unrelated field is saved.
        if (!existing && seriesId && occurrenceIndex < countOf(old.count)) continue;
        let id = existing?.id;
        if (!id && occurrenceIndex === 0 && !before) id = selected.id;
        if (!id) {
            do { id = `r_${stableHash(identity)}_${occurrenceIndex}_${serial++}`; } while (occupied.has(id));
        }
        occupied.add(id);
        const event = existing ? memberContent(existing) : {
            ...selected, id, completed: false, deletedAt: 0,
            checklist: (selected.checklist ?? []).map(item => ({ ...item, completed: false })),
        };
        if (event.id === selected.id) Object.assign(event, selected);
        // Repetition never relocates completed history. Only an explicit edit of that
        // occurrence's own dates changes its historical schedule.
        if (!existing || ((scheduleChanged || frequencyChanged)
            && (!existing.completed || (existing.id === selected.id && scheduleChanged)))) {
            event.start = existing?.id === selected.id && scheduleChanged ? new Date(selected.start)
                : occurrenceAt(anchorStart, frequency, occurrenceIndex, timeZone);
            event.end = existing?.id === selected.id && scheduleChanged ? new Date(selected.end)
                : occurrenceAt(anchorEnd, frequency, occurrenceIndex, timeZone);
            event.timezone = timeZone;
        }
        changed.push(withRecurrence(event, {
            ...old, ...recurrenceOf(existing), frequency, count, seriesId: identity, occurrenceIndex,
            anchorStartAt: iso(anchorStart), anchorEndAt: iso(anchorEnd), timeZone,
        }));
    }
    for (const member of members) {
        if ((recurrenceOf(member)?.occurrenceIndex ?? 0) < count) continue;
        const event = memberContent(member);
        if (!member.completed) { event.deletedAt = now; retired.add(member.id); }
        changed.push(withRecurrence(event, { ...old, ...recurrenceOf(member), frequency, count,
            anchorStartAt: iso(anchorStart), anchorEndAt: iso(anchorEnd), timeZone }));
    }
    return changed.map(event => withRecurrence(event, {
        ...event.recurrence, nextOccurrenceSerial: serial, retiredOccurrenceIds: [...retired],
    }));
}
