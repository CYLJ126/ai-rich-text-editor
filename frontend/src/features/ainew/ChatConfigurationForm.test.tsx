import React from 'react';
import {cleanup, render, waitFor} from '@testing-library/react';
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
  vi.mocked(discoverChatOptions).mockResolvedValueOnce(reply({options: []})).mockResolvedValueOnce(reply({options: [model]}));
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
