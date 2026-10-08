import React from 'react';
import {fireEvent, render, screen, waitFor} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import ModelSelector from './index';
import {useModelsStore} from '@/stores/modelsStore';

vi.mock('@/components', () => ({MyDynamicIcon: () => null}));
vi.mock('@/services/ant-design-pro/ai.rbac', () => ({listModelConfigs: vi.fn()}));
vi.mock('antd-style', () => ({createStyles: () => () => ({styles: {}})}));
vi.mock('antd', () => ({
  Tag: ({children}: any) => <span>{children}</span>,
  Tooltip: ({children}: any) => children,
  Select: ({value, options, onChange}: any) => (
    <select aria-label="model" value={value ?? ''} onChange={(event) => onChange(Number(event.target.value))}>
      {options.map((option: any) => <option key={option.value} value={option.value}>{option.value}</option>)}
    </select>
  ),
}));

describe('ModelSelector', () => {
  beforeEach(() => {
    useModelsStore.setState({initialized: true, models: [
      {id: 1, modelId: 'same-name', status: 1},
      {id: 2, modelId: 'same-name', status: 1, defaultFlag: true, publicFlag: true},
    ]});
  });

  it('automatically selects the default public configuration', async () => {
    const onSelect = vi.fn();
    render(<ModelSelector onSelect={onSelect}/>);
    await waitFor(() => expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({id: 2})));
    expect(screen.getByLabelText('model')).toHaveValue('2');
  });

  it('distinguishes configurations published under the same model name', async () => {
    const onSelect = vi.fn();
    render(<ModelSelector onSelect={onSelect}/>);
    await waitFor(() => expect(screen.getByLabelText('model')).toHaveValue('2'));
    fireEvent.change(screen.getByLabelText('model'), {target: {value: '1'}});
    expect(onSelect).toHaveBeenLastCalledWith(expect.objectContaining({id: 1}));
    expect(screen.getByLabelText('model')).toHaveValue('1');
  });

  it('preserves the upstream explicit defaultModelId behavior', async () => {
    const onSelect = vi.fn();
    render(<ModelSelector defaultModelId={1} onSelect={onSelect}/>);
    await waitFor(() => expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({id: 1})));
  });
});
