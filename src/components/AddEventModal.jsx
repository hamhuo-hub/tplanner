import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { MAX_LENGTH_TITLE, MASSEY_COLORS, EVENT_TYPES } from '../utils/constants';

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
    IconButton
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

export default function AddEventModal({ isOpen, onClose, onSave, defaultDate, initialEvent }) {
    const { t } = useTranslation();

    const [title, setTitle] = useState('');
    const [type, setType] = useState(EVENT_TYPES.EVENT);
    const [startDate, setStartDate] = useState(null);
    const [endDate, setEndDate] = useState(null);
    const [note, setNote] = useState('');
    const [checklist, setChecklist] = useState([]);
    const [colorId, setColorId] = useState(0);

    // Recurrence State
    const [recurrenceType, setRecurrenceType] = useState('none'); // 'none', 'daily', 'weekly', 'monthly'
    const [recurrenceCount, setRecurrenceCount] = useState(1);

    const [isLargeNoteOpen, setIsLargeNoteOpen] = useState(false);

    useEffect(() => {
        if (isOpen) {
            if (initialEvent) {
                // Edit Mode
                setTitle(initialEvent.title);
                setType(initialEvent.type || EVENT_TYPES.EVENT);
                setStartDate(initialEvent.start);
                setEndDate(initialEvent.end);
                setNote(initialEvent.note || '');
                setChecklist(initialEvent.checklist || []);
                setColorId(initialEvent.colorId);
            } else {
                // Create Mode
                const now = defaultDate || new Date();
                // If defaultDate provided (grid click), use it.
                // If direct "Add" button, set to next hour.
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
                setNote('');
                setChecklist([]);
                setColorId(0);

                // Reset Recurrence
                setRecurrenceType('none');
                setRecurrenceCount(1);
            }
        }
    }, [isOpen, defaultDate, initialEvent]);

    const handleSave = () => {
        if (!title || !startDate || !endDate) return;

        const eventsToSave = [];
        const groupId = crypto.randomUUID(); // Optional: link them

        if (initialEvent || recurrenceType === 'none') {
            // Single Event
            eventsToSave.push({
                id: initialEvent ? initialEvent.id : crypto.randomUUID(),
                title,
                type,
                start: startDate,
                end: endDate,
                note,
                checklist: type === EVENT_TYPES.TASK ? checklist : undefined,
                colorId
            });
        } else {
            // Recurring Events
            for (let i = 0; i < recurrenceCount; i++) {
                const newStart = new Date(startDate);
                const newEnd = new Date(endDate);

                if (recurrenceType === 'daily') {
                    newStart.setDate(startDate.getDate() + i);
                    newEnd.setDate(endDate.getDate() + i);
                } else if (recurrenceType === 'weekly') {
                    newStart.setDate(startDate.getDate() + (i * 7));
                    newEnd.setDate(endDate.getDate() + (i * 7));
                } else if (recurrenceType === 'monthly') {
                    newStart.setMonth(startDate.getMonth() + i);
                    newEnd.setMonth(endDate.getMonth() + i);
                }

                eventsToSave.push({
                    id: crypto.randomUUID(),
                    title,
                    type,
                    start: newStart,
                    end: newEnd,
                    note,
                    checklist: type === EVENT_TYPES.TASK ? [...checklist] : undefined, // Clone checklist
                    colorId,
                    groupId // Optional tag
                });
            }
        }

        onSave(eventsToSave);
        onClose();
    };

    const handleTypeChange = (event, newType) => {
        if (newType !== null) {
            setType(newType);
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
                                {t('event.typeEvent', 'Event')}
                            </ToggleButton>
                            <ToggleButton value={EVENT_TYPES.STATUS}>
                                {t('event.typeStatus', 'Status')}
                            </ToggleButton>
                            <ToggleButton value={EVENT_TYPES.TASK}>
                                {t('event.typeTask', 'Task')}
                            </ToggleButton>
                        </ToggleButtonGroup>

                        {/* Recurrence Options - Only for Create Mode */}
                        {!initialEvent && (
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
                        <Stack direction="row" spacing={2}>
                            <DatePicker
                                label={t('event.startDate', 'Start Date')}
                                value={startDate}
                                onChange={(newValue) => setStartDate(newValue)}
                                slotProps={{ textField: { fullWidth: true } }}
                            />
                            <TimePicker
                                label={t('event.startTime', 'Start Time')}
                                value={startDate}
                                onChange={(newValue) => {
                                    // Update time part of startDate
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
                        </Stack>

                        <Stack direction="row" spacing={2}>
                            <DatePicker
                                label={t('event.endDate', 'End Date')}
                                value={endDate}
                                onChange={(newValue) => setEndDate(newValue)}
                                slotProps={{ textField: { fullWidth: true } }}
                            />
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
