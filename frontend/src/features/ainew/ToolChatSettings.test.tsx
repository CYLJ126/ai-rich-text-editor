import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import ToolChatSettings from './ToolChatSettings';
import {discoverToolChat} from '@/services/arte-ai/toolChat';
import {listToolBindings} from '@/services/arte-ai/tool';
import {toolChatContext as context, toolChatReply as reply} from './toolChat.testdata';

vi.mock('@/features/aitool/ToolBudgetPicker', () => ({
  default: ({
              onChange,
            }: {
    onChange: (ref: string, price: unknown) => void;
  }) => (
    <button
      onClick={() =>
        onChange('search-wallet', {
          rate: {type: 'rate', id: 'search', version: 'v1'},
          perCall: {amount: '0.1', currency: 'USD'},
        })
      }
    >
      选择搜索预算
    </button>
  ),
}));

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai/toolChat', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai/toolChat')>()),
  discoverToolChat: vi.fn()
}));
vi.mock('@/services/arte-ai/tool', () => ({listToolBindings: vi.fn()}));
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(discoverToolChat).mockResolvedValue(reply({enabled: false}));
});
afterEach(cleanup);
it('default text chat performs no tool discovery and cannot enable a server-disabled feature', async () => {
  render(<ToolChatSettings context={context} value={null} onChange={vi.fn()} disabled={false} currency="CNY"/>);
  expect(discoverToolChat).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '工具设置'}));
  await waitFor(() => expect(discoverToolChat).toHaveBeenCalledTimes(1));
  expect(await screen.findByRole('switch', {name: '允许助手调用工具'})).toBeDisabled();
  expect(listToolBindings).not.toHaveBeenCalled();
});
it('a disabled or revoked service still lets the user turn off an existing tool selection', async () => {
  const changed = vi.fn();
  render(<ToolChatSettings context={context} value={{
    toolBindingIds: ['count'],
    maxModelRounds: 8,
    maxToolCalls: 16,
    maxConcurrentTools: 2,
    budgetLimits: [{currency: 'CNY', amount: '1.00'}]
  }} onChange={changed} disabled={false} currency="CNY"/>);
  fireEvent.click(screen.getByRole('button', {name: '工具已开启 · 1 项'}));
  await waitFor(() => expect(discoverToolChat).toHaveBeenCalledTimes(1));
  const toggle = await screen.findByRole('switch', {name: '允许助手调用工具'});
  expect(toggle).toBeEnabled();
  fireEvent.click(toggle);
  expect(changed).toHaveBeenCalledWith(null);
});

it('does not enable tools for a text-only model even when the service is open', async () => {
  vi.mocked(discoverToolChat).mockResolvedValue(reply({enabled: true}));
  vi.mocked(listToolBindings).mockResolvedValue(reply({records: [], scanned: 0, exhausted: true, nextCursor: null}));
  const changed = vi.fn();
  render(<ToolChatSettings context={context} value={null} onChange={changed} disabled={false} currency="CNY"
                           modelSupportsTools={false}/>);
  fireEvent.click(screen.getByRole('button', {name: '工具设置'}));
  await screen.findByText('当前模型不支持工具调用，请切换到支持工具调用的模型。');
  await waitFor(() => expect(listToolBindings).toHaveBeenCalledTimes(1));
  expect(screen.getByRole('switch', {name: '允许助手调用工具'})).toBeDisabled();
  expect(changed).not.toHaveBeenCalled();
});

it('keeps the model limit and adds a separate currency limit and fixed account for a paid tool', async () => {
  vi.mocked(discoverToolChat).mockResolvedValue(reply({enabled: true}));
  vi.mocked(listToolBindings).mockResolvedValue(
    reply({records: [], scanned: 0, exhausted: true, nextCursor: null}),
  );
  const changed = vi.fn();
  render(
    <ToolChatSettings
      context={context}
      value={{
        toolBindingIds: ['search'],
        maxModelRounds: 8,
        maxToolCalls: 16,
        maxConcurrentTools: 2,
        budgetLimits: [{currency: 'CNY', amount: '1.00'}],
      }}
      onChange={changed}
      disabled={false}
      currency="CNY"
      modelSupportsTools
    />,
  );
  fireEvent.click(screen.getByRole('button', {name: '工具已开启 · 1 项'}));
  fireEvent.click(await screen.findByRole('button', {name: '选择搜索预算'}));
  expect(changed).toHaveBeenCalledWith(
    expect.objectContaining({
      toolBudgetRefs: {search: 'search-wallet'},
      budgetLimits: [
        {currency: 'CNY', amount: '1.00'},
        {currency: 'USD', amount: '0.00'},
      ],
    }),
  );
});
