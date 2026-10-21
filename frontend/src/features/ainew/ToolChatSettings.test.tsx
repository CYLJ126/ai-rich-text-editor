import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import ToolChatSettings from './ToolChatSettings';
import {discoverToolChat} from '@/services/arte-ai/toolChat';
import {listToolBindings} from '@/services/arte-ai/tool';
import {toolChatContext as context, toolChatReply as reply} from './toolChat.testdata';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai/toolChat', async original => ({
  ...await original<typeof import('@/services/arte-ai/toolChat')>(),
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
