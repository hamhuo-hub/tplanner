import { useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { I18nextProvider, useTranslation } from 'react-i18next';
import { ThemeProvider } from '@mui/material/styles';
import { LocalizationProvider } from '@mui/x-date-pickers';
import { AdapterDateFns } from '@mui/x-date-pickers/AdapterDateFns';
import { Plus, Globe, Languages, FileText, LogIn, RefreshCw, Eye } from 'lucide-react';
import { fromZonedTime } from 'date-fns-tz';
import '../design-assets/tokens/generated/tplanner-light.css';
import '../src/index.css';

const query = new URLSearchParams(location.search);
const profile = query.get('profile') === 'desktop' ? 'desktop' : 'web';
const clean = query.get('clean') === '1';
const RealDate = window.Date;
const fixtureNow = new RealDate('2026-09-08T10:30:00+08:00').getTime();
// Only this isolated review document freezes the clock. Production modules are unchanged.
window.Date = class ReviewDate extends RealDate {
    constructor(...args) { super(...(args.length ? args : [fixtureNow])); }
    static now() { return fixtureNow; }
};

if (profile === 'desktop') {
    const listeners = new Set();
    let maximized = false;
    // UI-only stub: no IPC, filesystem, synchronization or native window operations.
    window.electronAPI = Object.freeze({
        isMaximized: async () => maximized,
        onMaximizeChange: callback => { listeners.add(callback); return () => listeners.delete(callback); },
        maximize: () => { maximized = !maximized; listeners.forEach(callback => callback(maximized)); },
        minimize: () => {},
        close: () => {},
    });
}

// Import after the profile stub so the real MUI theme and token installer see the right platform.
const [
    { default: i18n }, { default: theme }, { installDesignTokens },
    { default: Timeline }, { default: AddEventModal }, { default: EventDetailsModal },
    { default: NoteEditor }, { default: LoginScreen }, { default: TitleBar },
    { default: ClashBanner }, { default: OverdueBanner }, { default: ReminderBanner },
    { checkForClashes },
] = await Promise.all([
    import('../src/i18n'), import('../src/theme'), import('../src/design-system'),
    import('../src/components/Timeline'), import('../src/components/AddEventModal'), import('../src/components/EventDetailsModal'),
    import('../src/components/NoteEditor'), import('../src/components/LoginScreen'), import('../src/components/TitleBar'),
    import('../src/components/ClashBanner'), import('../src/components/OverdueBanner'), import('../src/components/ReminderBanner'),
    import('../src/utils/dateUtils'),
]);
installDesignTokens();
await i18n.changeLanguage(query.get('lang') === 'en' ? 'en' : 'zh');

const fixtureNote = '# 浅色工作记录\n\n长中文正文与 English metadata 共用系统字体。**重点内容**、*补充说明*与[站内链接](#review-notes)保持清晰。\n\n- [x] 已核对分类与文字配对\n- [ ] 检查键盘焦点和子任务阻塞\n\n> 已完成任务保持可读，用划线和图标表达状态。\n\n```js\nconst colorId = 3;\n```\n\n---\n\n支持行内编辑、全文分栏和 Markdown 清单。';
const at = (day, time) => fromZonedTime(`2026-09-${String(day).padStart(2, '0')}T${time}:00`, 'Asia/Shanghai');
const startDate = new Date(2026, 8, 7);
const endDate = new Date(2026, 8, 11);
const currentDay = { type: 'today', start: at(8, '00:00'), end: at(9, '00:00') };

function makeFixtures() {
    const labels = ['蓝 · 需求梳理', '金 · 当前评审', '玫瑰 · 内容校对', '绿 · 午后计划', '紫 · 深度工作', '陶橙 · 长中文任务标题检验和跨端复核', '青 · 项目同步', '灰 · 收尾检查'];
    const categories = labels.map((title, colorId) => ({
        id: `category-${colorId}`, title, colorId, type: 'event',
        start: at(colorId < 4 ? 8 : 9, `${String(8 + colorId % 4 * 2).padStart(2, '0')}:00`),
        end: at(colorId < 4 ? 8 : 9, `${String(10 + colorId % 4 * 2).padStart(2, '0')}:00`),
        timezone: 'Asia/Shanghai', note: fixtureNote, checklist: [], completed: false,
    }));
    return [
        ...categories,
        { id: 'blocked-task', title: '任务 · 等待两个子项，当前不可完成', colorId: 5, type: 'task', start: at(8, '15:00'), end: at(8, '18:00'), note: fixtureNote, checklist: [{ id: 'sub-1', text: '已完成：核对统一令牌', completed: true }, { id: 'sub-2', text: '待完成：检查 Web / Desktop 界面', completed: false }, { id: 'sub-3', text: '待完成：长中文子任务内容应允许自然换行并完整显示', completed: false }] },
        { id: 'done-task', title: '已完成 · 保持整行可读', colorId: 6, type: 'task', start: at(8, '05:00'), end: at(8, '08:00'), completed: true, checklist: [{ id: 'done-sub', text: '令牌已复核', completed: true }], note: fixtureNote },
        { id: 'overdue-task', title: '逾期 · 文案确认', colorId: 2, type: 'task', start: at(7, '10:00'), end: at(7, '14:00'), completed: false, checklist: [] },
        { id: 'clashing-event', title: '冲突 · 并行会议', colorId: 4, type: 'event', start: at(8, '10:30'), end: at(8, '12:30'), note: fixtureNote },
        { id: 'short-event', title: '5 分钟短事件', colorId: 7, type: 'event', start: at(8, '18:15'), end: at(8, '18:20') },
        { id: 'cross-day', title: '跨日 · 23:40–00:20', colorId: 3, type: 'event', start: at(8, '23:40'), end: at(9, '00:20'), note: fixtureNote },
        { id: 'non-conflicting-reminder', title: '独立 reminder 类型', colorId: 1, type: 'reminder', start: at(9, '17:00'), end: at(9, '19:00') },
        ...[0, 2, 6].map((colorId, index) => ({ id: `status-${index}`, title: `状态条 ${index + 1} · 分类 ${colorId + 1}`, type: 'status', colorId, start: at(8, `${8 + index}:00`), end: at(8, `${16 + index}:00`) })),
    ];
}

function ReviewApp() {
    const { t } = useTranslation();
    const [events, setEvents] = useState(makeFixtures);
    const [view, setView] = useState(query.get('view') === 'login' ? 'login' : query.get('view') === 'notes' ? 'notes' : 'timeline');
    const [addOpen, setAddOpen] = useState(query.get('view') === 'add');
    const [editing, setEditing] = useState(null);
    const [defaultDate, setDefaultDate] = useState(at(8, '13:00'));
    const [selectedId, setSelectedId] = useState(query.get('view') === 'details' ? 'blocked-task' : null);
    const [selectedIds, setSelectedIds] = useState(() => new Set(['category-7']));
    const [highlight, setHighlight] = useState(currentDay);
    const [timezone, setTimezone] = useState('Asia/Shanghai');
    const [note, setNote] = useState(fixtureNote);
    const [readOnly, setReadOnly] = useState(false);
    const [journals, setJournals] = useState({ '2026-09-08': fixtureNote, '2026-09-09': '## 明日计划\n\n一段中文日记，验证浮层、正文与行高。' });
    const [message, setMessage] = useState('离线内存示例 · 固定时钟 2026-09-08 10:30 +08:00');
    const clashes = useMemo(() => checkForClashes(events), [events]);
    const selectedEvent = events.find(event => event.id === selectedId) || null;
    const save = changed => {
        const updates = Array.isArray(changed) ? changed : [changed];
        setEvents(previous => {
            const result = new Map(previous.map(event => [event.id, event]));
            updates.forEach(event => result.set(event.id, event));
            return [...result.values()];
        });
        setMessage(`已在内存更新 ${updates.length} 条记录，刷新恢复示例。`);
    };
    const openAdd = (start = at(8, '13:00')) => { setDefaultDate(start); setEditing(null); setAddOpen(true); };
    const reset = () => { setEvents(makeFixtures()); setNote(fixtureNote); setSelectedIds(new Set(['category-7'])); setHighlight(currentDay); setMessage('已恢复离线示例。'); };
    const today = () => { setView('timeline'); setHighlight(currentDay); document.getElementById('row-2026-09-08')?.scrollIntoView({ block: 'center' }); };

    if (view === 'login') return <>
        <LoginScreen onLogin={async ({ account, password }) => {
            const accepted = account === 'demo' && password === 'demo';
            if (accepted) setView('timeline');
            return accepted;
        }} />
        {!clean && <button className="btn" style={{ position: 'fixed', right: 16, bottom: 16, zIndex: 1000 }} onClick={() => setView('timeline')}>返回示例 · demo / demo</button>}
    </>;

    return <div className="app-container" style={{ display: 'flex', flexDirection: 'column', height: '100dvh', background: 'var(--clr-bg)', overflow: 'hidden' }}>
        {profile === 'desktop' && <TitleBar />}
        <header className="app-header">
            <div className="app-header-left">
                {profile !== 'desktop' && <h1 className="app-header-title">{t('app.title')}</h1>}
                <button className="btn btn--ghost" id="btn-today" onClick={today}>{t('nav.today')}</button>
            </div>
            <div className="app-header-right">
                <div className="tz-select-wrap" title={t('app.displayTimezone')}>
                    <Globe size={13} />
                    <select className="tz-select" id="tz-select" value={timezone} onChange={event => setTimezone(event.target.value)}>
                        <option value="Asia/Shanghai">北京</option><option value="Europe/London">London</option><option value="Pacific/Auckland">Auckland</option>
                    </select>
                </div>
                <button className="btn btn--ghost" id="btn-lang" onClick={() => i18n.changeLanguage(i18n.language === 'zh' ? 'en' : 'zh')} title={t('app.switchLanguage')}><Languages size={13} />{i18n.language === 'zh' ? 'EN' : '中文'}</button>
                <button className="btn btn--ghost" onClick={() => setSelectedId('blocked-task')} title="任务详情"><Eye size={13} /><span>详情</span></button>
                <button className="btn btn--ghost" onClick={() => setView(view === 'notes' ? 'timeline' : 'notes')}><FileText size={13} /><span>{view === 'notes' ? '时间轴' : '便签'}</span></button>
                <button className="btn btn--ghost" onClick={() => setView('login')}><LogIn size={13} /><span>登录</span></button>
                <button className="btn btn--primary" id="btn-add-event" onClick={() => openAdd()}><Plus size={13} />{t('actions.addEvent')}</button>
            </div>
        </header>
        {view === 'timeline' ? <main style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: 12, minHeight: 0, gap: 8 }}>
            <div className="calendar-banners">
                <ReminderBanner events={events} travelTimezone={timezone} onHighlight={setHighlight} />
                <OverdueBanner events={events} travelTimezone={timezone} onHighlight={setHighlight} />
                <ClashBanner clashes={clashes} events={events} travelTimezone={timezone} onHighlight={setHighlight} />
            </div>
            <Timeline startDate={startDate} endDate={endDate} events={events} clashes={clashes} travelTimezone={timezone}
                onEventClick={event => { setSelectedId(event.id); setSelectedIds(new Set()); }} onAddEvent={openAdd} highlight={highlight}
                onUpdateEvent={save} onToggleTaskComplete={(id, completed) => {
                    const event = events.find(item => item.id === id);
                    if (!event || (completed && event.checklist?.some(item => !item.completed))) return;
                    save({ ...event, completed });
                }}
                journals={journals} onSaveJournal={(date, text) => setJournals(previous => ({ ...previous, [date]: text }))}
                selectedIds={selectedIds} onSelectionChange={setSelectedIds} onContextMenu={(_, event) => setSelectedId(event.id)} />
        </main> : <main id="review-notes" style={{ flex: 1, minHeight: 0, padding: 'var(--tp-profile-page-padding)', overflow: 'auto' }}>
            <section className="tp-panel" style={{ maxWidth: 960, margin: '0 auto', padding: 'var(--tp-semantic-spacing-section)' }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 16 }}>
                    <h2>{t('event.note')}</h2><button className="btn" aria-pressed={readOnly} onClick={() => setReadOnly(value => !value)}>{readOnly ? '切换编辑' : '只读预览'}</button>
                </div>
                <NoteEditor value={note} onChange={setNote} onCommit={() => setMessage('便签已在内存保存。')} readOnly={readOnly} />
            </section>
        </main>}
        {!clean && <footer style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 8, padding: '4px 12px', borderTop: '1px solid var(--clr-border)', background: 'var(--clr-surface)', fontSize: 'var(--tp-profile-meta-font-size)', color: 'var(--clr-text-dim)' }}>
            <span role="status" style={{ flex: 1 }}>{message}</span><span>{profile} · 8 分类</span>
            <button className="btn btn--ghost" onClick={reset}><RefreshCw size={13} />恢复示例</button>
        </footer>}
        <AddEventModal isOpen={addOpen} onClose={() => { setAddOpen(false); setEditing(null); }} onSave={save} defaultDate={defaultDate} initialEvent={editing} events={events} />
        <EventDetailsModal event={selectedEvent} travelTimezone={timezone} onClose={() => setSelectedId(null)} onSave={save}
            onDelete={id => { setEvents(previous => previous.filter(event => event.id !== id)); setSelectedId(null); }}
            onEdit={event => { setEditing(event); setDefaultDate(event.start); setAddOpen(true); }} />
    </div>;
}

// Vite may re-evaluate this entry after a theme/CSS dependency changes.
// Reuse the existing React root across module replacements.
const reactRoot = import.meta.hot?.data.reactRoot ?? createRoot(document.getElementById('root'));
if (import.meta.hot) {
    import.meta.hot.dispose(data => { data.reactRoot = reactRoot; });
}

reactRoot.render(
    <I18nextProvider i18n={i18n}><ThemeProvider theme={theme}><LocalizationProvider dateAdapter={AdapterDateFns}>
        <ReviewApp />
    </LocalizationProvider></ThemeProvider></I18nextProvider>,
);
