import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, expect, it, vi} from 'vitest';
import {type ChatModelOption, discoverChatOptions, getBudget} from '@/services/arte-ai';
import ChatConfigurationForm from './ChatConfigurationForm';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai', async original => ({
  ...(await original<typeof import('@/services/arte-ai')>()), discoverChatOptions: vi.fn(), getBudget: vi.fn(),
}));
afterEach(cleanup);

it('keeps a newly published single model unapplied when refreshing an initially empty directory', async () => {
  const scope = {tenantId: 'tenant', workspaceId: 'space'};
  const model: ChatModelOption = {
    displayName: '个人发布', resourceId: 'personal', source: 'PERSONAL',
    binding: {type: 'binding', id: 'personal-binding', version: 'v1'},
    capability: {type: 'capability', id: 'chat', version: 'v1'}, contextWindowTokens: 8192,
    limits: {
      maxInputTokens: 4096,
      maxOutputTokens: 512,
      maxInputBytes: 65536,
      maxOutputBytes: 65536,
      maxTimeoutSeconds: 60
    },
    defaults: {maxInputTokens: 1024, maxOutputTokens: 128, timeoutSeconds: 30}, budgetRefs: ['budget'],
  };
  const reply = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
  vi.mocked(discoverChatOptions).mockResolvedValueOnce(reply({modelOptions: []})).mockResolvedValueOnce(reply({modelOptions: [model]}));
  vi.mocked(getBudget).mockResolvedValue(reply({
    budgetRef: 'budget', currency: 'CNY', limit: '100', held: '0', charged: '0', available: '100',
    rateVersion: {type: 'rate', id: 'rate', version: 'v1'}, version: 0
  }));
  const props = {
    restored: null, fixedScope: scope, onApply: vi.fn(), onRestore: vi.fn(), onDirty: vi.fn(), onReset: vi.fn(),
    t: (key: string) => key, translate: (key: string) => key
  };
  const view = render(<ChatConfigurationForm {...props}/>);
  await waitFor(() => expect(discoverChatOptions).toHaveBeenCalledTimes(1));
  view.rerender(<ChatConfigurationForm {...props} refreshRevision={1}/>);
  await waitFor(() => expect(getBudget).toHaveBeenCalled());
  expect(props.onRestore).not.toHaveBeenCalled();
  expect(props.onApply).not.toHaveBeenCalled();
});

const usageScope = {tenantId: 'tenant', workspaceId: 'space'};
const preparedModel: ChatModelOption = {
  displayName: '准备后的模型', resourceId: 'prepared-model', source: 'PERSONAL',
  binding: {type: 'binding', id: 'prepared-binding', version: 'v2'},
  capability: {type: 'capability', id: 'prepared-capability', version: 'v2'}, contextWindowTokens: 8192,
  limits: {
    maxInputTokens: 4096,
    maxOutputTokens: 512,
    maxInputBytes: 65536,
    maxOutputBytes: 65536,
    maxTimeoutSeconds: 60
  },
  defaults: {maxInputTokens: 1024, maxOutputTokens: 128, timeoutSeconds: 30}, budgetRefs: ['prepared-budget']
};
const usageReply = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});

function usageProps() {
  vi.mocked(getBudget).mockResolvedValue(usageReply({
    budgetRef: 'prepared-budget', currency: 'CNY', limit: '10', held: '0', charged: '0', available: '10',
    rateVersion: {type: 'rate', id: 'rate', version: 'v2'}, version: 0
  }));
  return {
    restored: null,
    fixedScope: usageScope,
    requireExplicitApply: true,
    onApply: vi.fn(),
    onRestore: vi.fn(),
    onDirty: vi.fn(),
    onReset: vi.fn(),
    t: (key: string) => key,
    translate: (key: string) => key
  };
}

it('requires explicit application even for a single newly prepared model on fresh chat entry', async () => {
  const props = usageProps();
  vi.mocked(discoverChatOptions).mockResolvedValue(usageReply({modelOptions: [preparedModel]}));
  render(<ChatConfigurationForm {...props}/>);
  await waitFor(() => expect(screen.getByRole('button', {name: 'apply'})).toBeEnabled());
  expect(props.onRestore).not.toHaveBeenCalled();
  expect(props.onApply).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: 'apply'}));
  await waitFor(() => expect(props.onApply).toHaveBeenCalledWith(expect.objectContaining({
    bindingId: 'prepared-binding', bindingVersion: 'v2', budgetRef: 'prepared-budget'
  }), preparedModel));
});
it('refreshes and preselects the exact prepared resource and binding without applying it', async () => {
  const props = usageProps();
  vi.mocked(discoverChatOptions).mockResolvedValueOnce(usageReply({modelOptions: []})).mockResolvedValue(usageReply({modelOptions: [preparedModel]}));
  const view = render(<ChatConfigurationForm {...props}/>);
  await waitFor(() => expect(screen.getByRole('button', {name: 'apply'})).toBeDisabled());
  view.rerender(<ChatConfigurationForm {...props} externalModel={{
    key: JSON.stringify(['prepared-binding', 'v2']),
    revision: 1,
    refresh: true,
    resourceId: 'prepared-model'
  }}/>);
  await waitFor(() => expect(screen.getByRole('button', {name: 'apply'})).toBeEnabled());
  expect(props.onApply).not.toHaveBeenCalled();
  expect(props.onRestore).not.toHaveBeenCalled();
});
it.each(['wrong-version', 'wrong-resource'])('does not substitute another candidate when the prepared %s is unavailable', async mismatch => {
  const props = usageProps();
  const substitute = mismatch === 'wrong-resource' ? {...preparedModel, resourceId: 'other'}
    : {...preparedModel, binding: {...preparedModel.binding, version: 'v3'}};
  vi.mocked(discoverChatOptions).mockResolvedValue(usageReply({modelOptions: [substitute]}));
  render(<ChatConfigurationForm {...props} externalModel={{
    key: JSON.stringify(['prepared-binding', 'v2']),
    revision: 1,
    refresh: true,
    resourceId: 'prepared-model'
  }}/>);
  await screen.findByText(/待选择的精确模型版本当前不可用/);
  expect(screen.getByRole('button', {name: 'apply'})).toBeDisabled();
  expect(props.onRestore).not.toHaveBeenCalled();
});
