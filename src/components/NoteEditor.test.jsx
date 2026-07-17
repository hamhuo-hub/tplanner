import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import NoteEditor from './NoteEditor';

vi.mock('react-i18next', () => ({
    useTranslation: () => ({ t: key => key }),
}));

afterEach(cleanup);

describe('NoteEditor read-only mode', () => {
    it('does not enter editing or invoke callbacks when the body is clicked', () => {
        const onChange = vi.fn();
        const onCommit = vi.fn();

        render(
            <NoteEditor
                value="Read-only note"
                readOnly
                onChange={onChange}
                onCommit={onCommit}
            />,
        );

        fireEvent.click(screen.getByText('Read-only note'));

        expect(screen.queryByRole('textbox')).toBeNull();
        expect(onChange).not.toHaveBeenCalled();
        expect(onCommit).not.toHaveBeenCalled();
    });

    it('renders Markdown task checkboxes as disabled', () => {
        const { container } = render(
            <NoteEditor value="- [ ] Pending item" readOnly />,
        );

        const checkbox = container.querySelector('input[type="checkbox"]');

        expect(checkbox).not.toBeNull();
        expect(checkbox.disabled).toBe(true);
    });

    it('does not expose the fullscreen editor button', () => {
        render(<NoteEditor value="Read-only note" readOnly />);

        expect(screen.queryByTitle('note.fullscreen')).toBeNull();
    });
});

describe('NoteEditor editable mode', () => {
    it('enters textarea editing when the preview is clicked', () => {
        render(<NoteEditor value="Editable note" />);

        fireEvent.click(screen.getByText('Editable note'));

        expect(screen.getByRole('textbox').value).toBe('Editable note');
    });
});
