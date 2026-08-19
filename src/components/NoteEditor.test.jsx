// @vitest-environment jsdom
import { useState } from 'react';
import { fireEvent, render } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import NoteEditor from './NoteEditor';

vi.mock('react-i18next', () => ({
    useTranslation: () => ({ t: key => key }),
}));

function ControlledNote({ initialValue, onCommit }) {
    const [value, setValue] = useState(initialValue);
    return <NoteEditor value={value} onChange={setValue} onCommit={onCommit} />;
}

describe('NoteEditor operation boundaries', () => {
    test('commits one final value from focus until blur', () => {
        const onCommit = vi.fn();
        const { container } = render(<ControlledNote initialValue="before" onCommit={onCommit} />);

        fireEvent.click(container.querySelector('.journal-md-preview'));
        const textarea = container.querySelector('textarea');
        fireEvent.focus(textarea);
        fireEvent.change(textarea, { target: { value: 'during' } });
        fireEvent.change(textarea, { target: { value: 'after' } });
        fireEvent.blur(textarea);

        expect(onCommit).toHaveBeenCalledTimes(1);
        expect(onCommit).toHaveBeenCalledWith('after');
    });

    test('does not commit an unchanged focus session', () => {
        const onCommit = vi.fn();
        const { container } = render(<ControlledNote initialValue="same" onCommit={onCommit} />);

        fireEvent.click(container.querySelector('.journal-md-preview'));
        const textarea = container.querySelector('textarea');
        fireEvent.focus(textarea);
        fireEvent.blur(textarea);

        expect(onCommit).not.toHaveBeenCalled();
    });

    test('commits every markdown checklist toggle as its own operation', () => {
        const onCommit = vi.fn();
        const { container } = render(<ControlledNote initialValue={'- [ ] first\n- [ ] second'} onCommit={onCommit} />);

        fireEvent.click(container.querySelector('input[type="checkbox"]'));
        fireEvent.click(container.querySelector('input[type="checkbox"]'));

        expect(onCommit).toHaveBeenCalledTimes(2);
        expect(onCommit).toHaveBeenNthCalledWith(1, '- [x] first\n- [ ] second');
        expect(onCommit).toHaveBeenNthCalledWith(2, '- [ ] first\n- [ ] second');
    });
});
