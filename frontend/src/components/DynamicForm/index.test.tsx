import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import DynamicForm from './index';

vi.mock('@/components', () => ({
  JsonEditor: () => <div data-testid="json-editor" />,
}));

vi.mock('@/components/DynamicIcon', () => ({
  IconPicker: () => <div data-testid="icon-picker" />,
}));

describe('DynamicForm layout', () => {
  it('keeps actions outside the scrollable form content', () => {
    const { container } = render(
      <DynamicForm
        fields={[]}
        onSubmit={vi.fn()}
        submitText="保存"
        cancelText="取消"
      />,
    );

    const root = container.firstElementChild as HTMLElement;
    const scrollableContent = root.firstElementChild as HTMLElement;
    const actions = root.querySelector('.dynamic-form-actions') as HTMLElement;

    expect(scrollableContent).toHaveClass('min-h-0', 'flex-1', 'overflow-auto');
    expect(actions.parentElement).toBe(root);
    expect(scrollableContent).not.toContainElement(actions);
    expect(screen.getByRole('button', { name: /保存/ })).toBeVisible();
  });
});
