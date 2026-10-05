import {act, cleanup, fireEvent, render, screen} from '@testing-library/react';
import React, {StrictMode} from 'react';
import {afterEach, describe, expect, it, vi} from 'vitest';
import MathFormulaView from './MathFormulaView';
import type {MathFormulaModalProps} from './MathFormulaModal';
import modalBridge from './modalBridge';

vi.mock('@/components', () => ({
  useEditorStore: (selector: (state: { operationMode: string }) => unknown) =>
    selector({operationMode: 'edit'}),
}));

vi.mock('./MathFormulaModal', () => ({
  default: ({open, onConfirm, onCancel}: MathFormulaModalProps) => open ? (
    <div>
      <button type="button" onClick={() => onConfirm('y^2', 'block')}>保存公式</button>
      <button type="button" onClick={onCancel}>取消</button>
    </div>
  ) : null,
}));

afterEach(() => {
  cleanup();
  modalBridge.handler = null;
});

describe('MathFormulaView', () => {
  it('commits a formula exactly once in StrictMode and closes the modal', () => {
    render(<StrictMode><MathFormulaView/></StrictMode>);
    const onUpdate = vi.fn();
    act(() => modalBridge.handler?.openModal('inline', 'x', onUpdate));

    fireEvent.click(screen.getByRole('button', {name: '保存公式'}));

    expect(onUpdate).toHaveBeenCalledOnce();
    expect(onUpdate).toHaveBeenCalledWith('y^2', 'block');
    expect(screen.queryByRole('button', {name: '保存公式'})).toBeNull();
  });

  it('closes on cancel without changing the formula', () => {
    render(<MathFormulaView/>);
    const onUpdate = vi.fn();
    act(() => modalBridge.handler?.openModal('block', 'x', onUpdate));

    fireEvent.click(screen.getByRole('button', {name: '取消'}));

    expect(onUpdate).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', {name: '保存公式'})).toBeNull();
  });
});
