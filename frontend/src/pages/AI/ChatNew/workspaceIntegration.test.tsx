import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/arte-ai';
import type {WorkspaceView} from '@/services/platform/workspace';
import * as platform from '@/services/platform/workspace';
import ChatNewPage from './index';
import WorkspacePicker from '@/features/workspace/WorkspacePicker';
import messages from '@/locales/zh-CN/aiChat';
import {storageKey, writeValue} from '@/features/ainew/storage';
import {workspaceStorageKey} from '@/features/workspace/useWorkspace';

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

vi.mock('@/services/platform/workspace', async original => ({
  ...(await original<typeof import('@/services/platform/workspace')>()),
  getWorkspacePreference: vi.fn(), listAccessibleWorkspaces: vi.fn(), getWorkspace: vi.fn(),
  checkWorkspaceAccess: vi.fn(), updateWorkspacePreference: vi.fn(),
}));
vi.mock('@/services/arte-ai', async original => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  discoverChatOptions: vi.fn(),
  listConversations: vi.fn(),
  getConversation: vi.fn(),
  queryTurnsOfConversation: vi.fn(),
  getBudget: vi.fn(),
  turnsForChat: vi.fn(),
  watchInvocation: vi.fn(),
  getInvocationStatus: vi.fn(),
}));
let userSequence = 0;
const otherScope = {tenantId: 'other-tenant', workspaceId: 'other-space'};
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
  state.userId = `workspace-integration-${++userSequence}`;
  const preference = {defaultScope: scope, startupScope: null, lastScope: null, favorites: [], version: 0};
  vi.mocked(platform.getWorkspacePreference).mockResolvedValue(reply(preference));
  vi.mocked(platform.getWorkspace).mockImplementation(async selected => reply({
    scope: selected,
    name: selected.workspaceId === scope.workspaceId ? '空间A' : '空间B',
    state: 0,
    currentRole: 0,
    version: 1,
  }));
  vi.mocked(platform.listAccessibleWorkspaces).mockResolvedValue(page([
    {scope, name: '空间A', state: 0, currentRole: 0, version: 1, defaultWorkspace: true},
    {scope: otherScope, name: '空间B', state: 0, currentRole: 2, version: 1, defaultWorkspace: false},
  ]));
  vi.mocked(platform.checkWorkspaceAccess).mockResolvedValue(reply({
    allowed: false,
    reason: 'POLICY_DENIED',
    checkedAt: 'now'
  }));
  vi.mocked(platform.updateWorkspacePreference).mockImplementation(async request => reply({
    operationId: 'preference-operation', status: 1, delivery: 0, value: {
      ...preference,
      startupScope: request.startupScope, lastScope: request.lastScope, favorites: request.favorites, version: 1
    },
    resultVersion: 1, acceptedAt: 'now', completedAt: 'now',
  }));
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
  vi.mocked(api.watchInvocation).mockImplementation(async (_request, _handler, options) => new Promise<void>(resolve => {
    options?.signal?.addEventListener('abort', () => resolve(), {once: true});
  }));
});
afterEach(cleanup);

async function chooseConversation() {
  if (!screen.queryByRole('button', {name: '我的会话'})) fireEvent.click(await screen.findByRole('button', {name: '会话列表'}));
  fireEvent.click(await screen.findByRole('button', {name: '我的会话'}));
  await screen.findByLabelText('输入问题');
}


function app() {
  return render(<><WorkspacePicker userId={state.userId}/><ChatNewPage/></>);
}

async function selectFromHeader(name: string) {
  fireEvent.click(screen.getAllByLabelText('选择工作空间')[0]);
  const target = await screen.findByRole('button', {name});
  await waitFor(() => expect(target).toBeEnabled());
  fireEvent.click(target);
}

it('protects unapplied chat configuration when selecting a space from the global header', async () => {
  app();
  await chooseConversation();
  await waitFor(() => expect(screen.getByLabelText('最大输出 Token')).toHaveValue('512'));
  fireEvent.change(screen.getByLabelText('最大输出 Token'), {target: {value: '2048'}});
  await selectFromHeader('空间B');
  await screen.findByText('有未应用配置');
  expect(platform.getWorkspace).not.toHaveBeenCalledWith(otherScope, expect.anything());
  fireEvent.click(screen.getByRole('button', {name: '留在当前会话'}));
  expect(screen.getAllByLabelText('选择工作空间')[0]).toHaveTextContent('空间A');
  expect(screen.getByLabelText('最大输出 Token')).toHaveValue('2048');
});
it('isolates scope, drafts and uncertain writes when switching away and back through the header', async () => {
  vi.mocked(api.discoverChatOptions).mockImplementation(async request => reply({options: request.scope.workspaceId === scope.workspaceId ? [model] : []}));
  vi.mocked(api.listConversations).mockImplementation(async request => page(request.scope.workspaceId === scope.workspaceId ? [conversation] : []));
  app();
  await chooseConversation();
  fireEvent.change(screen.getByLabelText('输入问题'), {target: {value: '原空间问题'}});
  await waitFor(() => expect(screen.getByRole('button', {name: '发送'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '发送'}));
  await screen.findByText('重试确认原提交');
  const original = vi.mocked(api.turnsForChat).mock.calls[0].slice(0, 2);
  fireEvent.change(screen.getByLabelText('输入问题'), {target: {value: '下一条草稿'}});
  await selectFromHeader('空间B');
  await waitFor(() => expect(screen.getAllByLabelText('选择工作空间')[0]).toHaveTextContent('空间B'));
  await screen.findByText('此空间尚无可用 AI 模型或兼容预算。');
  expect(screen.queryByLabelText('输入问题')).not.toBeInTheDocument();
  expect(api.turnsForChat).toHaveBeenCalledTimes(1);
  expect(api.discoverChatOptions).toHaveBeenCalledWith({scope: otherScope}, expect.anything());
  await selectFromHeader('空间A');
  await screen.findByText('重试确认原提交');
  expect(screen.getByLabelText('输入问题')).toHaveValue('下一条草稿');
  fireEvent.click(screen.getByText('重试确认原提交'));
  await waitFor(() => expect(api.turnsForChat).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.turnsForChat).mock.calls[1].slice(0, 2)).toEqual(original);
});

it('uses the unified scope for discovery, conversations, history, budgets and submissions', async () => {
  writeValue(workspaceStorageKey(state.userId, 'currentWorkspace'), otherScope);
  app();
  await chooseConversation();
  fireEvent.change(screen.getByLabelText('输入问题'), {target: {value: '目标空间的问题'}});
  await waitFor(() => expect(screen.getByRole('button', {name: '发送'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '发送'}));
  await screen.findByText('重试确认原提交');
  for (const method of [api.discoverChatOptions, api.listConversations, api.getConversation, api.queryTurnsOfConversation, api.getBudget, api.turnsForChat]) {
    expect(method).toHaveBeenCalled();
    for (const [request] of vi.mocked(method).mock.calls) expect(request.scope).toEqual(otherScope);
  }
});

it('keeps the explicit FIXED connection after a failed public selection and clears it only after a successful selection', async () => {
  const fixedScope = {tenantId: 'fixed-tenant', workspaceId: 'fixed-space'};
  writeValue(`arte.ainew.fixedScope.v1.${encodeURIComponent(JSON.stringify([state.userId]))}`, fixedScope);
  const loadWorkspace = vi.mocked(platform.getWorkspace).getMockImplementation()!;
  vi.mocked(platform.getWorkspace).mockImplementation(async selected => {
    if (selected.workspaceId === otherScope.workspaceId) throw new api.AiApiError(403, {desc: '目标空间已不可访问'});
    return loadWorkspace(selected);
  });
  app();
  await chooseConversation();
  await screen.findByText('当前使用手动指定的已有 AI scope');
  expect(vi.mocked(api.discoverChatOptions).mock.calls.every(([request]) => request.scope.workspaceId === fixedScope.workspaceId)).toBe(true);
  await selectFromHeader('空间B');
  await screen.findByText('目标空间已不可访问');
  expect(screen.getByText('当前使用手动指定的已有 AI scope')).toBeInTheDocument();
  expect(screen.getAllByLabelText('选择工作空间')[0]).toHaveTextContent('空间A');
  vi.mocked(platform.getWorkspace).mockImplementation(loadWorkspace);
  await selectFromHeader('空间B');
  await waitFor(() => expect(screen.queryByText('当前使用手动指定的已有 AI scope')).not.toBeInTheDocument());
  await waitFor(() => expect(api.discoverChatOptions).toHaveBeenLastCalledWith({scope: otherScope}, expect.anything()));
  expect(api.turnsForChat).not.toHaveBeenCalled();
});

it('restores the current space, original pending request and independent draft without resubmitting on remount', async () => {
  app();
  await chooseConversation();
  fireEvent.change(screen.getByLabelText('输入问题'), {target: {value: '原问题'}});
  await waitFor(() => expect(screen.getByRole('button', {name: '发送'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '发送'}));
  await screen.findByText('重试确认原提交');
  const original = vi.mocked(api.turnsForChat).mock.calls[0].slice(0, 2);
  fireEvent.change(screen.getByLabelText('输入问题'), {target: {value: '刷新后保留的新草稿'}});
  cleanup();
  app();
  await screen.findByText('重试确认原提交');
  expect(screen.getAllByLabelText('选择工作空间')[0]).toHaveTextContent('空间A');
  expect(screen.getByLabelText('输入问题')).toHaveValue('刷新后保留的新草稿');
  expect(api.turnsForChat).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByText('重试确认原提交'));
  await waitFor(() => expect(api.turnsForChat).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.turnsForChat).mock.calls[1].slice(0, 2)).toEqual(original);
});

it('returns to chat with the space selected in another module and keeps the old chat draft isolated', async () => {
  vi.mocked(api.listConversations).mockImplementation(async request => page(request.scope.workspaceId === scope.workspaceId ? [conversation] : []));
  const renderModule = (chat: boolean) => <><WorkspacePicker userId={state.userId}/>{chat ? <ChatNewPage/> :
    <main>其他模块</main>}</>;
  const view = render(renderModule(true));
  await chooseConversation();
  fireEvent.change(screen.getByLabelText('输入问题'), {target: {value: '空间A的草稿'}});
  view.rerender(renderModule(false));
  await selectFromHeader('空间B');
  await waitFor(() => expect(screen.getAllByLabelText('选择工作空间')[0]).toHaveTextContent('空间B'));
  view.rerender(renderModule(true));
  await waitFor(() => expect(api.listConversations).toHaveBeenLastCalledWith(expect.objectContaining({scope: otherScope}), expect.anything()));
  expect(screen.queryByLabelText('输入问题')).not.toBeInTheDocument();
  await selectFromHeader('空间A');
  await waitFor(() => expect(screen.getByLabelText('输入问题')).toHaveValue('空间A的草稿'));
});

it('ignores another tab changing startup, recent scope or cached configuration', async () => {
  app();
  await chooseConversation();
  fireEvent.change(screen.getByLabelText('输入问题'), {target: {value: '本标签草稿'}});
  vi.mocked(platform.getWorkspacePreference).mockResolvedValue(reply({
    defaultScope: scope,
    startupScope: otherScope,
    lastScope: otherScope,
    favorites: [],
    version: 2
  }));
  act(() => window.dispatchEvent(new StorageEvent('storage', {
    key: workspaceStorageKey(state.userId, 'currentWorkspace'),
    newValue: JSON.stringify(otherScope)
  })));
  act(() => window.dispatchEvent(new StorageEvent('storage', {
    key: storageKey({
      userId: state.userId,
      scope
    }, 'recentConfiguration'), newValue: '{}'
  })));
  await screen.findByText('另一标签页更新了配置偏好，请刷新目录并重新校验；当前选择和原操作保持不变。');
  expect(screen.getAllByLabelText('选择工作空间')[0]).toHaveTextContent('空间A');
  expect(screen.getByLabelText('输入问题')).toHaveValue('本标签草稿');
  expect(vi.mocked(api.discoverChatOptions).mock.calls.every(([request]) => request.scope.workspaceId === scope.workspaceId)).toBe(true);
  expect(api.turnsForChat).not.toHaveBeenCalled();
});

it('never sends AI requests to an inaccessible startup preference before falling back to the default space', async () => {
  vi.mocked(platform.getWorkspacePreference).mockResolvedValue(reply({
    defaultScope: scope,
    startupScope: otherScope,
    lastScope: otherScope,
    favorites: [],
    version: 1
  }));
  vi.mocked(platform.getWorkspace).mockImplementation(async selected => {
    if (selected.workspaceId === otherScope.workspaceId) throw new api.AiApiError(404, {code: 'PLATFORM_NOT_FOUND'});
    return reply({scope: selected, name: '空间A', state: 0, currentRole: 0, version: 1});
  });
  app();
  await chooseConversation();
  expect(platform.getWorkspace).toHaveBeenCalledWith(otherScope, expect.anything());
  for (const method of [api.discoverChatOptions, api.listConversations, api.getConversation, api.queryTurnsOfConversation, api.getBudget]) {
    for (const [request] of vi.mocked(method).mock.calls) expect(request.scope).toEqual(scope);
  }
  fireEvent.click(screen.getAllByLabelText('选择工作空间')[0]);
  await screen.findByText('原空间已不可访问，已回到个人默认空间。');
});

it('shows FIXED authorization denial without borrowing models or budgets from the previous space', async () => {
  vi.mocked(api.discoverChatOptions).mockImplementation(async request => {
    if (request.scope.workspaceId === otherScope.workspaceId) throw new api.AiApiError(403, {code: '205001'});
    return reply({options: [model]});
  });
  app();
  await chooseConversation();
  await waitFor(() => expect(api.getBudget).toHaveBeenCalled());
  await selectFromHeader('空间B');
  await screen.findByText('此空间尚未配置 AI 使用能力或当前账号无调用授权。');
  await chooseConversation();
  fireEvent.change(screen.getByLabelText('输入问题'), {target: {value: '没有授权时不能发送'}});
  expect(screen.getByRole('button', {name: '发送'})).toBeDisabled();
  expect(vi.mocked(api.getBudget).mock.calls.every(([request]) => request.scope.workspaceId === scope.workspaceId)).toBe(true);
  expect(api.turnsForChat).not.toHaveBeenCalled();
  expect(screen.getByRole('combobox', {name: '当前发送模型'})).toHaveValue('');
});

it.each(['resolve', 'reject'])('aborts old model and conversation reads and ignores their late %s after switching', async outcome => {
  let finishModels!: () => void;
  let finishList!: () => void;
  vi.mocked(api.discoverChatOptions).mockImplementation(request => request.scope.workspaceId === scope.workspaceId
    ? new Promise((resolve, reject) => {
      finishModels = () => outcome === 'resolve' ? resolve(reply({options: [model]})) : reject(new api.AiApiError(403, {code: '205001'}));
    })
    : Promise.resolve(reply({options: []})));
  vi.mocked(api.listConversations).mockImplementation(request => request.scope.workspaceId === scope.workspaceId
    ? new Promise((resolve, reject) => {
      finishList = () => outcome === 'resolve' ? resolve(page([conversation])) : reject(new api.AiApiError(403, {code: '205001'}));
    })
    : Promise.resolve(page()));
  app();
  await waitFor(() => expect(finishModels).toBeTypeOf('function'));
  const modelSignal = vi.mocked(api.discoverChatOptions).mock.calls[0][1]?.signal;
  const listSignal = vi.mocked(api.listConversations).mock.calls[0][1]?.signal;
  await selectFromHeader('空间B');
  await screen.findByText('此空间尚无可用 AI 模型或兼容预算。');
  expect(modelSignal?.aborted).toBe(true);
  expect(listSignal?.aborted).toBe(true);
  await act(async () => {
    finishModels();
    finishList();
  });
  expect(screen.getAllByLabelText('选择工作空间')[0]).toHaveTextContent('空间B');
  expect(screen.queryByText('我的会话')).not.toBeInTheDocument();
  expect(screen.queryByText('此空间尚未配置 AI 使用能力或当前账号无调用授权。')).not.toBeInTheDocument();
  expect(api.getBudget).not.toHaveBeenCalled();
  expect(api.turnsForChat).not.toHaveBeenCalled();
});

it('aborts the old history and budget reads even when the target space has the same conversation ID', async () => {
  let finishHistory!: () => void;
  const finishBudgets: (() => void)[] = [];
  vi.mocked(api.discoverChatOptions).mockImplementation(async request => reply({options: request.scope.workspaceId === scope.workspaceId ? [model] : []}));
  const oldTurn: api.ConversationTurnResponse = {
    turnId: 'old-turn',
    conversationId: conversation.conversationId,
    sequence: 1,
    parentTurnId: null,
    supersedesTurnId: null,
    userMessage: {
      messageId: 'old-message',
      role: 'USER',
      content: [{text: '只属于空间A的历史'}],
      toolCalls: [],
      toolCallId: null
    },
    invocationIds: [],
    selectedInvocationId: null,
    version: 1,
    createdAt: 'now',
    updatedAt: 'now',
  };
  vi.mocked(api.queryTurnsOfConversation).mockImplementation(request => request.scope.workspaceId === scope.workspaceId
    ? new Promise(resolve => {
      finishHistory = () => resolve(page([oldTurn]));
    }) : Promise.resolve(page()));
  vi.mocked(api.getBudget).mockImplementation(() => new Promise(resolve => {
    finishBudgets.push(() => resolve(reply({
      budgetRef: 'budget', currency: 'CNY', limit: '999', held: '0', charged: '0', available: '999',
      rateVersion: {type: 'rate', id: 'rate', version: 'v1'}, version: 1,
    })));
  }));
  app();
  await chooseConversation();
  await waitFor(() => expect(finishHistory).toBeTypeOf('function'));
  await waitFor(() => expect(finishBudgets.length).toBeGreaterThan(0));
  const historySignal = vi.mocked(api.queryTurnsOfConversation).mock.calls[0][1]?.signal;
  const budgetSignals = vi.mocked(api.getBudget).mock.calls.map(([, options]) => options?.signal);
  await selectFromHeader('空间B');
  await screen.findByText('此空间尚无可用 AI 模型或兼容预算。');
  await chooseConversation();
  await waitFor(() => expect(api.queryTurnsOfConversation).toHaveBeenLastCalledWith(expect.objectContaining({scope: otherScope}), expect.anything()));
  expect(historySignal?.aborted).toBe(true);
  expect(budgetSignals.every(signal => signal?.aborted)).toBe(true);
  await act(async () => {
    finishHistory();
    finishBudgets.forEach(finish => finish());
  });
  expect(screen.queryByText('只属于空间A的历史')).not.toBeInTheDocument();
  expect(screen.queryByText(/999/)).not.toBeInTheDocument();
  expect(screen.getByRole('button', {name: '发送'})).toBeDisabled();
  expect(api.turnsForChat).not.toHaveBeenCalled();
});

it('cancels the old invocation observer on switching and resumes only the original scope on returning', async () => {
  vi.mocked(api.listConversations).mockImplementation(async request => page(request.scope.workspaceId === scope.workspaceId ? [conversation] : []));
  vi.mocked(api.turnsForChat).mockResolvedValue({
    ...reply({
      invocationId: 'accepted-in-A', conversationId: conversation.conversationId,
      kind: 'INVOCATION', acceptedAt: 'now'
    }), httpStatus: 202
  });
  vi.mocked(api.getInvocationStatus).mockResolvedValue(reply({
    invocationId: 'accepted-in-A', kind: 'GENERATION', conversation: null, state: 'RUNNING', version: 1,
    budgetState: 'RESERVED', activeAttemptId: 'attempt', resultAvailable: false, partial: null, error: null,
    acceptedAt: 'now', updatedAt: 'now',
  }));
  app();
  await chooseConversation();
  fireEvent.change(screen.getByLabelText('输入问题'), {target: {value: '开始旧空间的调用'}});
  await waitFor(() => expect(screen.getByRole('button', {name: '发送'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '发送'}));
  await waitFor(() => expect(api.watchInvocation).toHaveBeenCalled());
  const observer = vi.mocked(api.watchInvocation).mock.calls[0];
  expect(observer[0]).toEqual(expect.objectContaining({scope, invocationId: 'accepted-in-A'}));
  await selectFromHeader('空间B');
  await waitFor(() => expect(screen.getAllByLabelText('选择工作空间')[0]).toHaveTextContent('空间B'));
  expect(observer[2]?.signal?.aborted).toBe(true);
  expect(api.turnsForChat).toHaveBeenCalledTimes(1);
  await selectFromHeader('空间A');
  await waitFor(() => expect(api.watchInvocation).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.watchInvocation).mock.calls[1][0]).toEqual(expect.objectContaining({
    scope,
    invocationId: 'accepted-in-A'
  }));
  expect(api.turnsForChat).toHaveBeenCalledTimes(1);
});
