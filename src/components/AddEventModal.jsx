import { useState, useEffect } from 'react';
import { toDate } from 'date-fns-tz';
import { useTranslation } from 'react-i18next';
import { MAX_LENGTH_TITLE, MASSEY_COLORS, EVENT_TYPES, TIMEZONES } from '../utils/constants';

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
} from '@mui/material';
import { DatePicker } from '@mui/x-date-pickers/DatePicker';
import { TimePicker } from '@mui/x-date-pickers/TimePicker';
import { styled } from '@mui/material/styles';
import { Maximize2, X, PlusCircle, MinusCircle } from 'lucide-react';

const ColorButton = styled('button')(({ theme, colorSelected }) => ({
    width: 32,
    height: 32,
    borderRadius: '50%',
    border: colorSelected ? '2px solid #666' : '2px solid transparent',
    padding: 0,
    cursor: 'pointer',
    transition: 'all 0.2s',
    '&:hover': {
        transform: 'scale(1.1)',
    },
}));

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
    const [editScope, setEditScope] = useState('single');

    const [allDay, setAllDay] = useState(false);
    const [isLargeNoteOpen, setIsLargeNoteOpen] = useState(false);

    useEffect(() => {
        if (isOpen) {
            if (initialEvent) {
                // Edit Mode
                const evType = initialEvent.type || EVENT_TYPES.EVENT;
                setTitle(initialEvent.title);
                setType(evType);
                setStartDate(initialEvent.start);
                setEndDate(initialEvent.end);
                setEventTimezone(initialEvent.timezone || '');
                setNote(initialEvent.note || '');
                setChecklist(initialEvent.checklist || []);
                setColorId(initialEvent.colorId);

                // Detect all-day: 00:00 start and 23:59 end, only for status/task
                const isAllDay = evType !== EVENT_TYPES.EVENT
                    && initialEvent.start.getHours() === 0
                    && initialEvent.start.getMinutes() === 0
                    && initialEvent.end.getHours() === 23
                    && initialEvent.end.getMinutes() >= 59;
                setAllDay(isAllDay);

                // Edit recurrence
                setRecurrenceType(initialEvent.recurrenceType || 'none');
                setRecurrenceCount(initialEvent.recurrenceCount || 1);
                setEditScope('single');
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

    const handleSave = () => {
        if (!title || !startDate || !endDate) return;

        let finalStartDate = startDate;
        let finalEndDate = endDate;

        // All-day: clamp times to 00:00:00 → 23:59:59
        if (allDay) {
            finalStartDate = new Date(startDate);
            finalStartDate.setHours(0, 0, 0, 0);
            finalEndDate = new Date(endDate);
            finalEndDate.setHours(23, 59, 59, 999);
        }

        // If a specific timezone is selected, we assume the user entered the time AS IF they were in that timezone.
        // We need to construct a Date object that represents that absolute moment in time.
        // Since the user typed "10:00" into the picker, the Date object currently thinks it's 10:00 local time.
        if (eventTimezone) {
            try {
                // Extract the YYYY-MM-DD HH:mm representation of what the user entered
                const startStr = `${startDate.getFullYear()}-${String(startDate.getMonth() + 1).padStart(2, '0')}-${String(startDate.getDate()).padStart(2, '0')}T${String(startDate.getHours()).padStart(2, '0')}:${String(startDate.getMinutes()).padStart(2, '0')}:00`;
                const endStr = `${endDate.getFullYear()}-${String(endDate.getMonth() + 1).padStart(2, '0')}-${String(endDate.getDate()).padStart(2, '0')}T${String(endDate.getHours()).padStart(2, '0')}:${String(endDate.getMinutes()).padStart(2, '0')}:00`;

                // Parse it relative to the selected timezone using date-fns-tz
                finalStartDate = toDate(startStr, { timeZone: eventTimezone });
                finalEndDate = toDate(endStr, { timeZone: eventTimezone });
            } catch (e) {
                console.error("Failed to parse date with timezone", e);
                // Fallback to local
            }
        }

        const eventsToSave = [];
        const groupId = (initialEvent && editScope !== 'single') ? initialEvent.groupId : crypto.randomUUID();

        if ((editScope === 'single' && initialEvent) || recurrenceType === 'none') {
            // Single Event
            eventsToSave.push({
                id: initialEvent ? initialEvent.id : crypto.randomUUID(),
                title,
                type,
                start: finalStartDate,
                end: finalEndDate,
                timezone: eventTimezone,
                note,
                checklist: type === EVENT_TYPES.TASK ? checklist : undefined,
                colorId,
                groupId: (initialEvent && editScope === 'single') ? initialEvent.groupId : groupId,
                recurrenceType: (initialEvent && editScope === 'single') ? initialEvent.recurrenceType : recurrenceType,
                recurrenceCount: (initialEvent && editScope === 'single') ? initialEvent.recurrenceCount : recurrenceCount
            });
        } else {
            // Recurring Events
            for (let i = 0; i < recurrenceCount; i++) {
                const newStart = new Date(finalStartDate);
                const newEnd = new Date(finalEndDate);

                if (recurrenceType === 'daily') {
                    newStart.setDate(finalStartDate.getDate() + i);
                    newEnd.setDate(finalEndDate.getDate() + i);
                } else if (recurrenceType === 'weekly') {
                    newStart.setDate(finalStartDate.getDate() + (i * 7));
                    newEnd.setDate(finalEndDate.getDate() + (i * 7));
                } else if (recurrenceType === 'monthly') {
                    newStart.setMonth(finalStartDate.getMonth() + i);
                    newEnd.setMonth(finalEndDate.getMonth() + i);
                }

                // Determine ID: Use existing ID for the first item if editing
                let eventId;
                if (initialEvent && i === 0) {
                    eventId = initialEvent.id;
                } else {
                    eventId = crypto.randomUUID();
                }

                eventsToSave.push({
                    id: eventId,
                    title,
                    type,
                    start: newStart,
                    end: newEnd,
                    timezone: eventTimezone,
                    note,
                    checklist: type === EVENT_TYPES.TASK ? [...checklist] : undefined, // Clone checklist
                    colorId,
                    groupId,
                    recurrenceType,
                    recurrenceCount
                });
            }
        }

        onSave(eventsToSave, {
            scope: editScope,
            originalGroupId: initialEvent?.groupId,
            originalStartDate: initialEvent?.start
        });
        onClose();
    };

    const handleTypeChange = (_, newType) => {
        if (newType !== null) {
            setType(newType);
            if (newType === EVENT_TYPES.EVENT) setAllDay(false);
        }
    };

    return (
        <>
            <Dialog open={isOpen} onClose={onClose} maxWidth="sm" fullWidth>
                <DialogTitle>
                    {initialEvent ? t('actions.edit') : t('actions.addEvent')}
                </DialogTitle>
                <DialogContent>
                    <Stack spacing={3} sx={{ mt: 1 }}>
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

                        {/* Recurrence Options - Available in both Create and Edit Mode */}
                        <Box sx={{ border: '1px solid #eee', p: 1, borderRadius: 1 }}>
                            <Stack direction="row" alignItems="center" justifyContent="space-between">
                                <Typography variant="body2" color="text.secondary">
                                    {t('event.recurrence', 'Repeat')}
                                </Typography>
                                <ToggleButtonGroup
                                    value={recurrenceType}
                                    exclusive
                                    onChange={(e, val) => setRecurrenceType(val)}
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
                                        type="number"
                                        size="small"
                                        value={recurrenceCount}
                                        onChange={(e) => setRecurrenceCount(parseInt(e.target.value) || 1)}
                                        inputProps={{ min: 1, max: 50 }}
                                        sx={{ width: 100 }}
                                    />
                                    <Typography variant="caption" color="text.secondary">
                                        {t('recurrence.max')}
                                    </Typography>
                                </Stack>
                            )}
                        </Box>

                        {/* Edit Scope - Only when editing a recurring event */}
                        {initialEvent && initialEvent.groupId && (
                            <TextField
                                select
                                label={t('recurrence.editScope', 'Apply changes to')}
                                value={editScope}
                                onChange={(e) => setEditScope(e.target.value)}
                                SelectProps={{ native: true }}
                                size="small"
                                fullWidth
                            >
                                <option value="single">{t('recurrence.scopeSingle')}</option>
                                <option value="future">{t('recurrence.scopeFuture')}</option>
                                <option value="all">{t('recurrence.scopeAll')}</option>
                            </TextField>
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
                                            {t('event.allDay', '全天')}
                                        </Typography>
                                    }
                                />
                            )}

                            <Stack direction="row" spacing={2} alignItems="center">
                                <DatePicker
                                    label={t('event.startDate', 'Start Date')}
                                    value={startDate}
                                    onChange={(newValue) => setStartDate(newValue)}
                                    slotProps={{ textField: { fullWidth: true } }}
                                />
                                {!allDay && (
                                    <TimePicker
                                        label={t('event.startTime', 'Start Time')}
                                        value={startDate}
                                        onChange={(newValue) => {
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
                            <Stack direction="row" spacing={2} alignItems="center">
                                <DatePicker
                                    label={t('event.endDate', 'End Date')}
                                    value={endDate}
                                    onChange={(newValue) => setEndDate(newValue)}
                                    slotProps={{ textField: { fullWidth: true } }}
                                />
                                {!allDay && (
                                    <TimePicker
                                        label={t('event.endTime', 'End Time')}
                                        value={endDate}
                                        onChange={(newValue) => {
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
                                onChange={(e) => setEventTimezone(e.target.value)}
                                SelectProps={{
                                    native: true,
                                }}
                                size="small"
                                fullWidth
                            >
                                {TIMEZONES.map((option) => (
                                    <option key={option.value} value={option.value}>
                                        {t(`timezones.${option.value.replace('/', '_')}`, option.label)}
                                    </option>
                                ))}
                            </TextField>
                        </Stack>

                        {/* Note */}
                        <Box position="relative">
                            <TextField
                                label={t('event.note')}
                                multiline
                                rows={3}
                                fullWidth
                                value={note}
                                onChange={(e) => setNote(e.target.value)}
                            />
                            <IconButton
                                onClick={() => setIsLargeNoteOpen(true)}
                                size="small"
                                sx={{ position: 'absolute', right: 8, top: 8, zIndex: 1, backgroundColor: 'rgba(255,255,255,0.8)' }}
                                title="Expand Note Editor"
                            >
                                <Maximize2 size={16} />
                            </IconButton>
                        </Box>

                        {/* Checklist - Only for Task Type */}
                        {type === EVENT_TYPES.TASK && (
                            <Box>
                                <Typography variant="body2" color="text.secondary" gutterBottom>
                                    {t('event.checklist', 'Checklist')}
                                </Typography>
                                <Stack spacing={1}>
                                    {checklist.map((item, index) => (
                                        <Stack key={index} direction="row" spacing={1} alignItems="center">
                                            <TextField
                                                fullWidth
                                                size="small"
                                                value={item.text}
                                                onChange={(e) => {
                                                    const newChecklist = [...checklist];
                                                    newChecklist[index].text = e.target.value;
                                                    setChecklist(newChecklist);
                                                }}
                                                placeholder={t('event.checklistItem', 'Item...')}
                                            />
                                            <IconButton
                                                size="small"
                                                color="error"
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
                            <Stack direction="row" spacing={1}>
                                {MASSEY_COLORS.map((c, i) => (
                                    // Render color circle
                                    // Using DOM button with Tailwind class for color? 
                                    // Or mapping tailwind class to hex?
                                    // Our constant uses Tailwind classes `bg-blue-600`.
                                    // We can just use a div with className.
                                    <div
                                        key={i}
                                        className={`rounded-full w-8 h-8 cursor-pointer border-2 ${colorId === i ? 'border-gray-600' : 'border-transparent'}`}
                                        style={{ backgroundColor: c }}
                                        onClick={() => setColorId(i)}
                                    />
                                ))}
                            </Stack>
                        </Box>

                    </Stack>
                </DialogContent>
                <DialogActions>
                    <Button onClick={onClose} color="inherit">
                        {t('actions.cancel')}
                    </Button>
                    <Button onClick={handleSave} variant="contained" color="primary">
                        {t('actions.save')}
                    </Button>
                </DialogActions>
            </Dialog>

            {/* Large Note Editor Dialog */}
            <Dialog
                open={isLargeNoteOpen}
                onClose={() => setIsLargeNoteOpen(false)}
                fullWidth
                maxWidth="md"
                PaperProps={{
                    sx: { height: '80vh', display: 'flex', flexDirection: 'column' }
                }}
            >
                <DialogTitle sx={{ borderBottom: '1px solid #eee', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    {t('event.note')}
                    <IconButton onClick={() => setIsLargeNoteOpen(false)} edge="end">
                        <X size={20} />
                    </IconButton>
                </DialogTitle>
                <DialogContent sx={{ flexGrow: 1, p: 2, display: 'flex', flexDirection: 'column' }}>
                    <TextField
                        multiline
                        fullWidth
                        value={note}
                        onChange={(e) => setNote(e.target.value)}
                        variant="standard"
                        InputProps={{ disableUnderline: true }}
                        placeholder={t('event.notePlaceholder')}
                        sx={{
                            flexGrow: 1,
                            mt: 2,
                            '& .MuiInputBase-root': { height: '100%', alignItems: 'flex-start', overflow: 'auto' },
                            '& .MuiInputBase-input': { height: '100% !important', overflow: 'auto !important' }
                        }}
                    />
                </DialogContent>
                <DialogActions sx={{ borderTop: '1px solid #eee', p: 2 }}>
                    <Button onClick={() => setIsLargeNoteOpen(false)} variant="contained" color="primary">
                        {t('actions.save')} & {t('actions.close')}
                    </Button>
                </DialogActions>
            </Dialog>
        </>
    );
}
