import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/arte-ai';
import type {WorkspaceView} from '@/services/platform/workspace';
import ChatNewPage from './index';
import messages from '@/locales/zh-CN/aiChat';

const state = vi.hoisted(() => ({workspace: null as WorkspaceView | null, userId: 'page-user'}));
vi.mock('@umijs/max', () => ({
  request: vi.fn(),
  useModel: () => ({initialState: {currentUser: {id: state.userId}}}),
  useIntl: () => ({
    formatMessage: ({id, defaultMessage}: {
      id: string;
      defaultMessage?: string
    }) => messages[id as keyof typeof messages] ?? defaultMessage ?? id
  })
}));
vi.mock('@/features/workspace/useWorkspace', () => ({useCurrentWorkspace: () => state.workspace}));
vi.mock('@/features/workspace/WorkspacePicker', () => ({default: () => <button type="button">公共空间选择器</button>}));
vi.mock('@/services/arte-ai', async original => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  discoverChatOptions: vi.fn(),
  listConversations: vi.fn(),
  getConversation: vi.fn(),
  queryTurnsOfConversation: vi.fn(),
  getBudget: vi.fn(),
  turnsForChat: vi.fn(),
  watchInvocation: vi.fn()
}));
const scope = {tenantId: 'tenant', workspaceId: 'workspace'};
const conversation: api.ConversationResponse = {
  conversationId: 'conversation',
  title: '我的会话',
  version: 1,
  chatProfile: null,
  resources: [],
  state: 'ACTIVE',
  createdAt: '2026-10-10T01:00:00Z',
  updatedAt: '2026-10-10T01:00:00Z'
};
const model: api.ChatModelOption = {
  displayName: '模型',
  binding: {type: 'binding', id: 'binding', version: 'v1'},
  capability: {type: 'capability', id: 'text', version: 'v1'},
  contextWindowTokens: 65536,
  limits: {
    maxInputTokens: 60000,
    maxOutputTokens: 4096,
    maxInputBytes: 65536,
    maxOutputBytes: 1000000,
    maxTimeoutSeconds: 120
  },
  defaults: {maxInputTokens: 32768, maxOutputTokens: 512, timeoutSeconds: 60},
  budgetRefs: ['budget']
};
const reply = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
const page = <T, >(records: T[] = []) => ({
  httpStatus: 200,
  body: {success: true as const, code: '200', desc: 'OK', records, current: 1, size: 20, total: records.length}
});
beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.clear();
  localStorage.clear();
  state.workspace = {scope, name: '空间', state: 0, currentRole: 0, version: 1};
  vi.mocked(api.discoverChatOptions).mockResolvedValue(reply({options: [model]}));
  vi.mocked(api.listConversations).mockResolvedValue(page([conversation]));
  vi.mocked(api.getConversation).mockResolvedValue(reply(conversation));
  vi.mocked(api.queryTurnsOfConversation).mockResolvedValue(page());
  vi.mocked(api.getBudget).mockResolvedValue(reply({
    budgetRef: 'budget',
    currency: 'CNY',
    limit: '100',
    held: '0',
    charged: '0',
    available: '100',
    rateVersion: {type: 'rate', id: 'rate', version: 'v1'},
    version: 0
  }));
  vi.mocked(api.turnsForChat).mockRejectedValue(new TypeError('network'));
});
afterEach(cleanup);

async function chooseConversation() {
  if (!screen.queryByRole('button', {name: '我的会话'})) fireEvent.click(screen.getByRole('button', {name: '会话列表'}));
  fireEvent.click(await screen.findByRole('button', {name: '我的会话'}));
  await screen.findByLabelText('输入问题');
}

it('registers a business view with one chat session and one shared configuration form', async () => {
  render(<ChatNewPage/>);
  await chooseConversation();
  fireEvent.change(screen.getByLabelText('输入问题'), {target: {value: '你好'}});
  await waitFor(() => expect(screen.getByRole('button', {name: '发送'})).toBeEnabled());
  expect(document.querySelectorAll('.ainew-chat')).toHaveLength(1);
  expect(document.querySelectorAll('form#ai-new-chat-config')).toHaveLength(1);
});
it('restores a full pending operation after refresh and never sends automatically', async () => {
  render(<ChatNewPage/>);
  await chooseConversation();
  fireEvent.change(screen.getByLabelText('输入问题'), {target: {value: '原问题'}});
  await waitFor(() => expect(screen.getByRole('button', {name: '发送'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '发送'}));
  await waitFor(() => expect(api.turnsForChat).toHaveBeenCalledTimes(1));
  await screen.findByText('重试确认原提交');
  const original = vi.mocked(api.turnsForChat).mock.calls[0].slice(0, 2);
  cleanup();
  render(<ChatNewPage/>);
  await screen.findByText('重试确认原提交');
  expect(api.turnsForChat).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByText('重试确认原提交'));
  await waitFor(() => expect(api.turnsForChat).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.turnsForChat).mock.calls[1].slice(0, 2)).toEqual(original);
});
it('keeps history readable when the workspace has no executable model', async () => {
  vi.mocked(api.discoverChatOptions).mockResolvedValue(reply({options: []}));
  render(<ChatNewPage/>);
  await screen.findByText('此空间尚无可用 AI 模型或兼容预算。');
  await chooseConversation();
  await waitFor(() => expect(api.queryTurnsOfConversation).toHaveBeenCalled());
  expect(api.getBudget).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText('输入问题'), {target: {value: '草稿'}});
  expect(screen.getByRole('button', {name: '发送'})).toBeDisabled();
});
it('blocks writes in an archived workspace while preserving history access', async () => {
  state.workspace = {...state.workspace!, state: 1};
  render(<ChatNewPage/>);
  await chooseConversation();
  await waitFor(() => expect(api.queryTurnsOfConversation).toHaveBeenCalled());
  fireEvent.change(screen.getByLabelText('输入问题'), {target: {value: '草稿'}});
  expect(screen.getByRole('button', {name: '发送'})).toBeDisabled();
  expect(api.turnsForChat).not.toHaveBeenCalled();
});
