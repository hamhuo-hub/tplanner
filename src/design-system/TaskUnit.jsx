export function TaskCheckbox({ completed, disabled = false, title, onToggle }) {
    return (
        <button
            type="button"
            className={`tplanner-task-checkbox${completed ? ' tplanner-task-checkbox--completed' : ''}`}
            disabled={disabled}
            aria-pressed={completed}
            aria-label={title}
            title={title}
            onClick={(event) => {
                event.stopPropagation();
                if (!disabled) onToggle?.(!completed);
            }}
        >
            {completed && (
                <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                    <path strokeLinecap="square" strokeLinejoin="miter" strokeWidth={3} d="M5 13l4 4L19 7" />
                </svg>
            )}
        </button>
    );
}

export function TaskProgress({ done, total }) {
    if (total <= 0) return null;
    const completed = done === total;
    return (
        <span className={`tplanner-task-progress${completed ? ' tplanner-task-progress--completed' : ''}`}>
            {done}/{total}
        </span>
    );
}

/** Shared content for task/event units. Timeline positioning stays with the caller. */
export default function TaskUnit({
    title,
    type,
    completed = false,
    checklist = [],
    accentColor,
    blockedTitle,
    note,
    onToggle,
}) {
    const done = checklist.filter((item) => item.completed).length;
    const total = checklist.length;
    const blocked = total > 0 && done !== total && !completed;

    return (
        <div className="tplanner-task-unit">
            {/* Summary = the fixed 28px recognition zone (eventSummaryHeight).
                Title AND note must fit inside; everything below is body and
                may be covered by the next cascade column. The layout
                algorithm knows nothing about note — only this one height. */}
            <div className="tplanner-task-unit__summary">
                <div className="tplanner-task-unit__header">
                    {type === 'task' && (
                        <TaskCheckbox
                            completed={completed}
                            disabled={blocked}
                            title={blocked ? blockedTitle : title}
                            onToggle={onToggle}
                        />
                    )}
                    {type !== 'task' && (
                        <span className="tplanner-task-unit__accent" style={{ backgroundColor: accentColor }} />
                    )}
                    <div className={`tplanner-task-unit__title${completed ? ' tplanner-task-unit__title--completed' : ''}`}>
                        {title}
                    </div>
                    <TaskProgress done={done} total={total} />
                </div>
                {note && <div className="tplanner-task-unit__note">{note}</div>}
            </div>
        </div>
    );
}
