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
vi.mock('@/features/workspace/useWorkspace', () => ({
  useCurrentWorkspace: () => state.workspace,
  registerWorkspaceGuard: vi.fn(() => () => {
  })
}));
vi.mock('@/features/workspace/WorkspacePicker', () => ({default: () => <button type="button">公共空间选择器</button>}));
vi.mock('@/features/workspace/WorkspaceQuickSelect', () => ({
  default: ({onManage}: { onManage: () => void }) =>
    <button type="button" onClick={onManage}>空间快捷选择</button>
}));
vi.mock('@/services/arte-ai', async original => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  discoverChatOptions: vi.fn(),
  queryConversationWindow: vi.fn(),
  getConversation: vi.fn(),
  queryTurnsOfConversation: vi.fn(),
  getInvocationSnapshot: vi.fn(),
  getBudget: vi.fn(),
  turnsForChat: vi.fn(),
  watchInvocation: vi.fn()
}));
vi.mock('@/services/platform/ai', () => ({
  ensureWorkspaceAi: vi.fn(async () => ({body: {data: {needsImport: false}}})),
  getWorkspaceAi: vi.fn(async () => {
    throw new Error('FIXED mode');
  }),
  getAiCatalog: vi.fn(), importAiConfiguration: vi.fn(), updateWorkspaceAi: vi.fn(), publishAiCatalog: vi.fn(),
  refKey: (ref: { type: string; id: string; version: string }) => JSON.stringify([ref.type, ref.id, ref.version])
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
const windowReply = (records: api.ConversationResponse[] = []) => reply({
  pinned: {records: [], nextCursor: null, hasMore: false, total: 0},
  normal: {
    records: records.map(record => ({
      conversationId: record.conversationId, title: record.title,
      metadataVersion: 0, pinned: false, titleManuallyEdited: false, autoTitleResolved: false,
      previewText: null, userMessageCount: 0, lastMessageGeneratedAt: null, sortActivityAt: record.createdAt,
      createdAt: record.createdAt, statisticsReady: true
    })), nextCursor: null, hasMore: false, total: records.length
  },
  anchorConversationId: null, anchorFallback: false,
});
beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.clear();
  localStorage.clear();
  state.workspace = {scope, name: '空间', state: 0, currentRole: 0, version: 1};
  vi.mocked(api.discoverChatOptions).mockResolvedValue(reply({options: [model]}));
  vi.mocked(api.queryConversationWindow).mockResolvedValue(windowReply([conversation]));
  vi.mocked(api.getInvocationSnapshot).mockImplementation(async (query, options) => {
    const status = (await api.getInvocationStatus(query, options)).body.data;
    const result = status.resultAvailable && query.knownResultVersion !== status.version && !['ACCEPTED', 'QUEUED', 'RUNNING'].includes(status.state)
      ? (await api.getInvocationResult(query, options)).body.data : null;
    return {invocationId: query.invocationId, status, result, missing: false};
  });
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
  await waitFor(() => expect(screen.queryByRole('button', {name: '收起会话列表'})
    ?? screen.queryByRole('button', {name: '展开会话列表'})).not.toBeNull());
  const expandList = screen.queryByRole('button', {name: '展开会话列表'});
  if (expandList) fireEvent.click(expandList);
  fireEvent.click(await screen.findByRole('button', {name: '打开会话：我的会话'}));
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

it('shows one access state for generic 403 and offers guidance instead of failed loading', async () => {
  const denied = new api.AiApiError(403, {code: '200000', desc: '失败'});
  vi.mocked(api.discoverChatOptions).mockRejectedValue(denied);
  vi.mocked(api.queryConversationWindow).mockRejectedValue(denied);
  render(<ChatNewPage/>);
  await screen.findByText('此空间的 AI 功能暂不可用');
  expect(screen.getByText(/创建工作空间不会自动开通 AI 权限/)).toBeInTheDocument();
  expect(screen.queryByText('会话列表读取失败')).not.toBeInTheDocument();
  expect(screen.queryByText('会话列表暂时加载失败')).not.toBeInTheDocument();
  expect(screen.queryByText('创建或选择会话即可开始；模型配置不影响会话阅读。')).not.toBeInTheDocument();
  expect(screen.getByRole('button', {name: '新建会话'})).toBeDisabled();
  expect(screen.getByLabelText('搜索会话')).toBeDisabled();
  expect(screen.getByRole('button', {name: '切换工作空间'})).toBeEnabled();
  fireEvent.click(screen.getByRole('button', {name: '查看开通说明'}));
  await screen.findByText('如何开通此空间的 AI 功能', {}, {timeout: 5000});
  expect(screen.getByText(/联系管理员，确认当前账号在此空间/)).toBeInTheDocument();
  expect(api.getBudget).not.toHaveBeenCalled();
  expect(api.turnsForChat).not.toHaveBeenCalled();
});

it('rechecks both reads after access is granted without creating or sending automatically', async () => {
  const denied = new api.AiApiError(403, {code: '200000', desc: '失败'});
  vi.mocked(api.discoverChatOptions).mockRejectedValueOnce(denied);
  vi.mocked(api.queryConversationWindow).mockRejectedValueOnce(denied);
  render(<ChatNewPage/>);
  await screen.findByText('此空间的 AI 功能暂不可用');
  await waitFor(() => expect(api.discoverChatOptions).toHaveBeenCalledTimes(1));
  fireEvent.click(screen.getByRole('button', {name: '重新检查'}));
  await waitFor(() => expect(api.discoverChatOptions).toHaveBeenCalledTimes(2));
  await screen.findByRole('button', {name: '打开会话：我的会话'});
  expect(screen.queryByText('此空间的 AI 功能暂不可用')).not.toBeInTheDocument();
  expect(screen.getByRole('button', {name: '新建会话'})).toBeEnabled();
  expect(api.queryConversationWindow).toHaveBeenCalledTimes(2);
  expect(api.turnsForChat).not.toHaveBeenCalled();
});

it('offers login for 401 without suggesting configuration changes', async () => {
  const expired = new api.AiApiError(401, {code: '200000', desc: '失败'});
  vi.mocked(api.discoverChatOptions).mockRejectedValue(expired);
  vi.mocked(api.queryConversationWindow).mockRejectedValue(expired);
  render(<ChatNewPage/>);
  await screen.findByText('登录已失效，请重新登录');
  expect(screen.getByRole('link', {name: '重新登录'})).toHaveAttribute('href', '/user/login');
  expect(screen.queryByRole('button', {name: '查看开通说明'})).not.toBeInTheDocument();
  expect(screen.getByRole('button', {name: '新建会话'})).toBeDisabled();
});

it('keeps retry available for network failure without mislabelling it as access denial', async () => {
  vi.mocked(api.queryConversationWindow).mockRejectedValueOnce(new TypeError('network'));
  render(<ChatNewPage/>);
  await screen.findByText('会话列表暂时加载失败');
  expect(screen.queryByText('此空间的 AI 功能暂不可用')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '重试加载'}));
  await screen.findByRole('button', {name: '打开会话：我的会话'});
  expect(api.queryConversationWindow).toHaveBeenCalledTimes(2);
});

it('distinguishes an authorized empty space from denied access', async () => {
  vi.mocked(api.queryConversationWindow).mockResolvedValue(windowReply());
  vi.mocked(api.discoverChatOptions).mockResolvedValue(reply({options: []}));
  render(<ChatNewPage/>);
  await screen.findByText('此空间还没有会话，可以新建一个会话开始。');
  expect(screen.getByRole('button', {name: '新建会话'})).toBeEnabled();
  expect(screen.queryByText('此空间的 AI 功能暂不可用')).not.toBeInTheDocument();
  expect(screen.queryByText('会话列表暂时加载失败')).not.toBeInTheDocument();
});

it('keeps the chat draft and single configuration form when collapsing regions and changing right-side tabs', async () => {
  render(<ChatNewPage/>);
  await chooseConversation();
  const composer = screen.getByLabelText('输入问题');
  fireEvent.change(composer, {target: {value: '切换布局后保留'}});
  const reads = vi.mocked(api.queryConversationWindow).mock.calls.length;
  fireEvent.click(screen.getByRole('button', {name: '收起会话列表'}));
  fireEvent.click(screen.getByRole('button', {name: '展开会话列表'}));
  fireEvent.click(screen.getByRole('button', {name: '空间快捷选择'}));
  await screen.findByRole('tab', {name: '工作空间', selected: true});
  expect(screen.getByRole('button', {name: '公共空间选择器'})).toBeVisible();
  fireEvent.click(screen.getByRole('tab', {name: '会话设置'}));
  fireEvent.click(screen.getByRole('tab', {name: '模型管理'}));
  fireEvent.click(screen.getByRole('button', {name: '高级参数'}));
  const outputLimit = screen.getByRole('spinbutton', {name: /最大输出 Token/});
  fireEvent.change(outputLimit, {target: {value: '1024'}});
  fireEvent.click(screen.getByRole('tab', {name: '会话设置'}));
  expect(outputLimit).not.toBeVisible();
  fireEvent.click(screen.getByRole('tab', {name: '模型管理'}));
  expect(screen.getByRole('spinbutton', {name: /最大输出 Token/})).toBe(outputLimit);
  expect(outputLimit).toHaveValue('1024');
  fireEvent.click(screen.getByRole('button', {name: '收起设置侧栏'}));
  fireEvent.click(screen.getByRole('button', {name: '展开设置侧栏'}));
  expect(screen.getByLabelText('输入问题')).toBe(composer);
  expect(composer).toHaveValue('切换布局后保留');
  expect(document.querySelectorAll('form#ai-new-chat-config')).toHaveLength(1);
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(document.querySelector('.ant-drawer-open')).toBeNull();
  expect(api.queryConversationWindow).toHaveBeenCalledTimes(reads);
});
