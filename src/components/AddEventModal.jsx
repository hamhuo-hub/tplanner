import { useState, useEffect, useRef } from 'react';
import { toZonedTime } from 'date-fns-tz';
import { buildRecurringEdit, recurrenceOf, scheduleFromEditor } from '../domain/recurringTasks.mjs';
import { seriesIdOf } from '../domain/recurringTaskSelection.mjs';
import { useTranslation } from 'react-i18next';
import { MAX_LENGTH_TITLE, EVENT_TYPES, TIMEZONES } from '../utils/constants';
import { categoryTokens } from '../design-system';

import {
    Dialog,
    DialogTitle,
    DialogContent,
    DialogActions,
    TextField,
    Button,
    ToggleButton,
    ToggleButtonGroup,
    Stack,
    Box,
    Typography,
    IconButton,
    FormControlLabel,
    Switch,
    Alert,
} from '@mui/material';
import { DatePicker } from '@mui/x-date-pickers/DatePicker';
import { TimePicker } from '@mui/x-date-pickers/TimePicker';
import { PlusCircle, MinusCircle, Check } from 'lucide-react';
import NoteEditor from './NoteEditor';

export default function AddEventModal({ isOpen, onClose, onSave, defaultDate, initialEvent, events = [] }) {
    const { t } = useTranslation();

    const [title, setTitle] = useState('');
    const [type, setType] = useState(EVENT_TYPES.EVENT);
    const [startDate, setStartDate] = useState(null);
    const [endDate, setEndDate] = useState(null);
    const [eventTimezone, setEventTimezone] = useState(''); // Empty string means local time
    const [note, setNote] = useState('');
    const [checklist, setChecklist] = useState([]);
    const [colorId, setColorId] = useState(0);

    // Recurrence State
    const [recurrenceType, setRecurrenceType] = useState('none'); // 'none', 'daily', 'weekly', 'monthly'
    const [recurrenceCount, setRecurrenceCount] = useState(1);

    const [allDay, setAllDay] = useState(false);
    const [scheduleDirty, setScheduleDirty] = useState(false);
    const [saving, setSaving] = useState(false);
    const [saveError, setSaveError] = useState('');
    const [draftId, setDraftId] = useState('');
    const [recurrenceDirty, setRecurrenceDirty] = useState(false);
    const editSessionRef = useRef(0);
    const creationBaselineRef = useRef(null);

    useEffect(() => {
        editSessionRef.current += 1;
        if (!isOpen) return;
        setSaving(false);
        creationBaselineRef.current = null;
        setScheduleDirty(false);
        setRecurrenceDirty(false);
        setSaveError('');
        setDraftId(initialEvent?.id || crypto.randomUUID());
        if (isOpen) {
            if (initialEvent) {
                // Edit Mode
                const evType = initialEvent.type || EVENT_TYPES.EVENT;
                setTitle(initialEvent.title);
                setType(evType);
                const zone = initialEvent.timezone || recurrenceOf(initialEvent)?.timeZone || '';
                const displayedStart = zone ? toZonedTime(initialEvent.start, zone) : new Date(initialEvent.start);
                const displayedEnd = zone ? toZonedTime(initialEvent.end, zone) : new Date(initialEvent.end);
                setStartDate(displayedStart);
                setEndDate(displayedEnd);
                setEventTimezone(zone);
                setNote(initialEvent.note || '');
                setChecklist(initialEvent.checklist || []);
                setColorId(initialEvent.colorId);

                // Detect all-day: 00:00 start and 23:59 end, only for status/task
                const isAllDay = evType !== EVENT_TYPES.EVENT
                    && displayedStart.getHours() === 0
                    && displayedStart.getMinutes() === 0
                    && displayedEnd.getHours() === 23
                    && displayedEnd.getMinutes() >= 59;
                setAllDay(isAllDay);

                // Edit recurrence
                setRecurrenceType(recurrenceOf(initialEvent)?.frequency || 'none');
                setRecurrenceCount(recurrenceOf(initialEvent)?.count || 1);
            } else {
                // Create Mode
                const now = defaultDate || new Date();
                let start = new Date(now);
                if (!defaultDate) {
                    start.setMinutes(0, 0, 0);
                    start.setHours(start.getHours() + 1);
                }

                const end = new Date(start.getTime() + 60 * 60 * 1000);

                setTitle('');
                setType(EVENT_TYPES.EVENT);
                setStartDate(start);
                setEndDate(end);
                setAllDay(false);

                const savedTravelTz = localStorage.getItem('tplanner_travel_timezone');
                setEventTimezone(savedTravelTz || 'Asia/Shanghai');

                setNote('');
                setChecklist([]);
                setColorId(0);

                setRecurrenceType('none');
                setRecurrenceCount(1);
            }
        }
    }, [isOpen, defaultDate, initialEvent]);

    const handleSave = async () => {
        if (saving || !title.trim() || !startDate || !endDate) return;
        const session = editSessionRef.current;
        setSaving(true);
        setSaveError('');
        try {
            const schedule = scheduleFromEditor({
                start: startDate, end: endDate, timeZone: eventTimezone,
                allDay, original: initialEvent, dirty: scheduleDirty,
            });
            if (!Number.isFinite(+schedule.start) || !Number.isFinite(+schedule.end) || schedule.end < schedule.start) {
                throw new Error(t('event.invalidDates', '结束时间不能早于开始时间'));
            }
            const draft = {
                ...initialEvent, id: draftId, title: title.trim(), type,
                ...schedule, timezone: eventTimezone,
                note, checklist: type === EVENT_TYPES.TASK ? checklist : [],
                completed: initialEvent?.completed ?? false, colorId,
                recurrenceType: type === EVENT_TYPES.TASK ? recurrenceType : 'none',
                recurrenceCount: Math.max(1, Math.min(50, recurrenceCount)),
            };
            const snapshot = initialEvent || (events.some(event => event.id === draftId) ? creationBaselineRef.current : null);
            const updates = buildRecurringEdit(events, snapshot, draft, Date.now(), { recurrenceChanged: recurrenceDirty });
            creationBaselineRef.current ??= draft;
            await onSave(updates);
            if (session === editSessionRef.current) onClose();
        } catch (error) {
            if (session === editSessionRef.current) setSaveError(error?.message || t('messages.saveError', '保存失败，请重试'));
        } finally {
            if (session === editSessionRef.current) setSaving(false);
        }
    };

    const handleTypeChange = (_, newType) => {
        if (newType !== null) {
            setType(newType);
            if (newType === EVENT_TYPES.EVENT) setAllDay(false);
        }
    };

    return (
        <>
            <Dialog open={isOpen} onClose={saving ? undefined : onClose} maxWidth="sm" fullWidth
                disableScrollLock
                closeAfterTransition={false}
            >
                <DialogTitle>
                    {initialEvent ? t('actions.edit') : t('actions.addEvent')}
                </DialogTitle>
                <DialogContent component="fieldset" disabled={saving} sx={{ border: 0, minWidth: 0, m: 0 }}>
                    <Stack spacing={3} sx={{ mt: 1 }}>
                        {saveError && <Alert severity="error">{saveError}</Alert>}
                        {seriesIdOf(initialEvent) && <Alert severity="info">
                            {t('recurrence.seriesEditHint', '标题、备注、分类和子项内容会更新整个系列；每次完成进度保持独立。修改排程会更新未完成次数，减少次数或取消重复会保留已完成历史。')}
                        </Alert>}
                        {initialEvent && recurrenceOf(initialEvent) && !seriesIdOf(initialEvent) && <Alert severity="info">
                            {t('recurrence.legacyUnbound', '这条旧重复任务没有可验证的系列关联。普通编辑只修改此次；重新选择重复规则可从此次建立系列。')}
                        </Alert>}
                        {recurrenceType !== 'none' && !['daily', 'weekly', 'monthly'].includes(recurrenceType) && <Alert severity="info">
                            {t('recurrence.unsupportedRule', '此任务使用较新版本的重复规则。普通编辑会保留原规则；可选择不重复或其他规则来更改。')}
                        </Alert>}
                        {/* Type Toggle */}
                        <ToggleButtonGroup
                            value={type}
                            exclusive
                            onChange={handleTypeChange}
                            aria-label="event type"
                            fullWidth
                        >
                            <ToggleButton value={EVENT_TYPES.EVENT}>
                                {t('event.typeReminder', 'Reminder')}
                            </ToggleButton>
                            <ToggleButton value={EVENT_TYPES.STATUS}>
                                {t('event.typeStatus', 'Status')}
                            </ToggleButton>
                            <ToggleButton value={EVENT_TYPES.TASK}>
                                {t('event.typeTask', 'Task')}
                            </ToggleButton>
                        </ToggleButtonGroup>

                        {/* Recurrence Options — tasks only */}
                        {type === EVENT_TYPES.TASK && (
                        <Box sx={{ border: '1px solid', borderColor: 'divider', p: 1, borderRadius: 'var(--tp-semantic-radius-control)' }}>
                            <Stack direction={{ xs: 'column', sm: 'row' }} gap={1} alignItems={{ xs: 'stretch', sm: 'center' }} justifyContent="space-between">
                                <Typography variant="body2" color="text.secondary">
                                    {t('event.recurrence', 'Repeat')}
                                </Typography>
                                <ToggleButtonGroup
                                    value={recurrenceType}
                                    exclusive
                                    onChange={(e, val) => { if (val === null) return; setRecurrenceDirty(true); setRecurrenceType(val); if (val !== 'none' && recurrenceCount < 2) setRecurrenceCount(2); }}
                                    size="small"
                                >
                                    <ToggleButton value="none">{t('recurrence.none')}</ToggleButton>
                                    <ToggleButton value="daily">{t('recurrence.daily')}</ToggleButton>
                                    <ToggleButton value="weekly">{t('recurrence.weekly')}</ToggleButton>
                                    <ToggleButton value="monthly">{t('recurrence.monthly')}</ToggleButton>
                                </ToggleButtonGroup>
                            </Stack>
                            {recurrenceType !== 'none' && (
                                <Stack direction="row" spacing={2} sx={{ mt: 2 }} alignItems="center">
                                    <TextField
                                        label={t('recurrence.count')}
                                        disabled={!['daily', 'weekly', 'monthly'].includes(recurrenceType)}
                                        type="number"
                                        size="small"
                                        value={recurrenceCount}
                                        onChange={(e) => { setRecurrenceDirty(true); setRecurrenceCount(Math.max(1, Math.min(50, parseInt(e.target.value) || 1))); }}
                                        inputProps={{ min: 1, max: 50 }}
                                        sx={{ width: 100 }}
                                    />
                                    <Typography variant="caption" color="text.secondary">
                                        {t('recurrence.max')}
                                    </Typography>
                                </Stack>
                            )}
                        </Box>
                        )}

                        {/* Title */}
                        <TextField
                            autoFocus
                            label={t('event.title')}
                            fullWidth
                            value={title}
                            onChange={(e) => setTitle(e.target.value)}
                            inputProps={{ maxLength: MAX_LENGTH_TITLE }}
                            required
                        />

                        {/* Date & Time Pickers */}
                        <Stack spacing={2}>
                            {/* All-day toggle — only for Status and Task */}
                            {type !== EVENT_TYPES.EVENT && (
                                <FormControlLabel
                                    control={
                                        <Switch
                                            checked={allDay}
                                            onChange={(e) => {
                                                const next = e.target.checked;
                                                setScheduleDirty(true);
                                                setAllDay(next);
                                                // When switching to all-day, snap end date to match start date
                                                if (next && startDate && endDate) {
                                                    const snapped = new Date(startDate);
                                                    snapped.setHours(23, 59, 59, 999);
                                                    // Keep end date but ensure it's same day or later
                                                    if (endDate < startDate) setEndDate(snapped);
                                                }
                                            }}
                                            size="small"
                                        />
                                    }
                                    label={
                                        <Typography variant="body2" color="text.secondary">
                                            {t('event.allDay')}
                                        </Typography>
                                    }
                                />
                            )}

                            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems="stretch">
                                <DatePicker
                                    label={t('event.startDate', 'Start Date')}
                                    value={startDate}
                                    onChange={(newValue) => { setScheduleDirty(true); setStartDate(newValue); }}
                                    slotProps={{ textField: { fullWidth: true } }}
                                />
                                {!allDay && (
                                    <TimePicker
                                        label={t('event.startTime', 'Start Time')}
                                        value={startDate}
                                        onChange={(newValue) => {
                                            setScheduleDirty(true);
                                            if (startDate && newValue) {
                                                const newDate = new Date(startDate);
                                                newDate.setHours(newValue.getHours());
                                                newDate.setMinutes(newValue.getMinutes());
                                                setStartDate(newDate);
                                            } else {
                                                setStartDate(newValue);
                                            }
                                        }}
                                        ampm={false}
                                        slotProps={{ textField: { fullWidth: true } }}
                                    />
                                )}
                            </Stack>
                            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems="stretch">
                                <DatePicker
                                    label={t('event.endDate', 'End Date')}
                                    value={endDate}
                                    onChange={(newValue) => { setScheduleDirty(true); setEndDate(newValue); }}
                                    slotProps={{ textField: { fullWidth: true } }}
                                />
                                {!allDay && (
                                    <TimePicker
                                        label={t('event.endTime', 'End Time')}
                                        value={endDate}
                                        onChange={(newValue) => {
                                            setScheduleDirty(true);
                                            if (endDate && newValue) {
                                                const newDate = new Date(endDate);
                                                newDate.setHours(newValue.getHours());
                                                newDate.setMinutes(newValue.getMinutes());
                                                setEndDate(newDate);
                                            } else {
                                                setEndDate(newValue);
                                            }
                                        }}
                                        ampm={false}
                                        slotProps={{ textField: { fullWidth: true } }}
                                    />
                                )}
                            </Stack>
                            <TextField
                                select
                                label={t('event.timezone', 'Timezone')}
                                value={eventTimezone}
                                onChange={(e) => { setScheduleDirty(true); setEventTimezone(e.target.value); }}
                                SelectProps={{
                                    native: true,
                                }}
                                size="small"
                                fullWidth
                            >
                                {TIMEZONES.map((option) => (
                                    <option key={option.value} value={option.value}>
                                        {t(`timezones.${option.value ? option.value.replace('/', '_') : 'default'}`, option.label)}
                                    </option>
                                ))}
                            </TextField>
                        </Stack>

                        {/* Note */}
                        <Box>
                            <Typography variant="caption" color="text.secondary"
                                sx={{ display: 'block', mb: 0.5 }}
                            >
                                {t('event.note')}
                            </Typography>
                            <NoteEditor value={note} onChange={setNote} />
                        </Box>

                        {/* Checklist - Only for Task Type */}
                        {type === EVENT_TYPES.TASK && (
                            <Box>
                                <Typography variant="body2" color="text.secondary" gutterBottom>
                                    {t('event.checklist', 'Checklist')}
                                </Typography>
                                <Stack spacing={1}>
                                    {checklist.map((item, index) => (
                                        <Stack key={item.id || index} direction="row" spacing={1} alignItems="center">
                                            <TextField
                                                fullWidth
                                                size="small"
                                                value={item.text}
                                                onChange={(e) => {
                                                    const val = e.target.value;
                                                    setChecklist(checklist.map((it, i) =>
                                                        i === index ? { ...it, text: val } : it
                                                    ));
                                                }}
                                                placeholder={t('event.checklistItem', 'Item...')}
                                            />
                                            <IconButton
                                                size="small"
                                                color="error"
                                                aria-label={`${t('actions.delete')} ${item.text || t('event.checklistItem', 'Item...')}`}
                                                onClick={() => {
                                                    const newChecklist = checklist.filter((_, i) => i !== index);
                                                    setChecklist(newChecklist);
                                                }}
                                            >
                                                <MinusCircle size={20} />
                                            </IconButton>
                                        </Stack>
                                    ))}
                                    <Button
                                        startIcon={<PlusCircle size={16} />}
                                        size="small"
                                        onClick={() => setChecklist([...checklist, { id: crypto.randomUUID(), text: '', completed: false }])}
                                        sx={{ alignSelf: 'flex-start' }}
                                    >
                                        {t('actions.addItem', 'Add Item')}
                                    </Button>
                                </Stack>
                            </Box>
                        )}

                        {/* Color Picker */}
                        <Box>
                            <Typography variant="body2" color="text.secondary" gutterBottom>
                                {t('event.color')}
                            </Typography>
                            <div className="event-category-picker" role="group" aria-label={t('event.color')}>
                                {categoryTokens.map((category) => (
                                    <button
                                        type="button"
                                        key={category.id}
                                        className="event-category-option"
                                        aria-label={`${t('event.color')} ${category.id + 1}`}
                                        aria-pressed={colorId === category.id}
                                        title={`${t('event.color')} ${category.id + 1}`}
                                        style={{ backgroundColor: category.background, color: category.foreground, borderColor: category.border }}
                                        onClick={() => setColorId(category.id)}
                                    >
                                        <span className="event-category-option__swatch" style={{ backgroundColor: category.accent }} />
                                        {colorId === category.id && <Check size={16} aria-hidden="true" />}
                                    </button>
                                ))}
                            </div>
                        </Box>

                    </Stack>
                </DialogContent>
                <DialogActions>
                    <Button onClick={onClose} color="inherit" disabled={saving}>
                        {t('actions.cancel')}
                    </Button>
                    <Button onClick={handleSave} variant="contained" color="primary" disabled={saving}>
                        {t('actions.save')}
                    </Button>
                </DialogActions>
            </Dialog>

        </>
    );
}
