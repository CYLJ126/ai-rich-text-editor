import React, {useState} from 'react';
import {Modal} from 'antd';
import {act, cleanup, fireEvent, render, renderHook, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import * as api from '@/services/arte-ai';
import {chatConfigSchema, DEFAULT_CHAT_CONFIG} from './config';
import {useChatSession} from './useChatSession';
import {useConversations} from './useConversations';
import {clearAccountSession, readOperation, readValue, saveOperation, storageKey, writeValue} from './storage';
import MarkdownText from './MarkdownText';
import BusinessChatPanel from './BusinessChatPanel';
import ReconciliationPanel from './ReconciliationPanel';
import {discoverToolChat, submitToolChat} from '@/services/arte-ai/toolChat';
import {listToolBindings} from '@/services/arte-ai/tool';
import {availableToolBudgets} from '@/services/arte-ai/toolInvocation';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai/toolChat', async original => ({
  ...(await original<typeof import('@/services/arte-ai/toolChat')>()),
  discoverToolChat: vi.fn(),
  submitToolChat: vi.fn(),
}));
vi.mock('@/services/arte-ai/tool', () => ({listToolBindings: vi.fn()}));
vi.mock('@/services/arte-ai/toolInvocation', async original => ({
  ...(await original<typeof import('@/services/arte-ai/toolInvocation')>()),
  availableToolBudgets: vi.fn(),
}));
vi.mock('@/services/arte-ai', async original => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  getConversation: vi.fn(),
  getConversationExecutionState: vi.fn(),
  queryTurnsOfConversation: vi.fn(),
  getBudget: vi.fn(),
  getInvocationStatus: vi.fn(),
  getInvocationSnapshot: vi.fn(),
  getInvocationResult: vi.fn(),
  turnsForChat: vi.fn(),
  regenerateChat: vi.fn(),
  cancelInvocation: vi.fn(),
  watchInvocation: vi.fn(),
  createConversation: vi.fn(),
  listConversations: vi.fn(),
  reconciliationReceipt: vi.fn(),
  pendingReconciliations: vi.fn(),
  confirmReconciliation: vi.fn(),
}));
const scope = {tenantId: 'tenant', workspaceId: 'workspace'};
const context = {userId: 'account-a', scope};
const config = chatConfigSchema.parse({
  ...DEFAULT_CHAT_CONFIG, ...scope,
  bindingId: 'binding',
  bindingVersion: 'v1',
  capabilityId: 'text',
  capabilityVersion: 'v1',
  budgetRef: 'budget'
});
const conversation: api.ConversationResponse = {
  conversationId: 'conversation',
  title: '会话',
  version: 3,
  chatProfile: null,
  resources: [],
  state: 'ACTIVE',
  createdAt: '2026-10-10T01:00:00Z',
  updatedAt: '2026-10-10T01:00:00Z'
};
const result = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
const page = <T, >(records: T[] = []) => ({
  httpStatus: 200,
  body: {success: true as const, code: '200', desc: 'OK', records, current: 1, size: 10, total: records.length}
});
const request: api.SubmitChatRequest = {
  scope,
  conversationId: 'conversation',
  expectedVersion: 3,
  text: '原问题',
  capability: {type: 'capability', id: 'text', version: 'v1'},
  binding: {type: 'binding', id: 'binding', version: 'v1'},
  budgetRef: 'budget',
  maxInputTokens: 32768,
  generationOptions: {maxOutputTokens: 512, temperature: null, topP: null, stopSequences: []},
  timeoutSeconds: 60
};
const t = (key: string) => key;
const session = (value = config, enabled = true) => renderHook(() => useChatSession(value, conversation, vi.fn(), undefined, undefined, context, enabled));
beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.clear();
  localStorage.clear();
  vi.mocked(api.getInvocationSnapshot).mockImplementation(async (query, options) => {
    const status = (await api.getInvocationStatus(query, options)).body.data;
    const result = status.resultAvailable && query.knownResultVersion !== status.version && !['ACCEPTED', 'QUEUED', 'RUNNING'].includes(status.state)
      ? (await api.getInvocationResult(query, options)).body.data : null;
    return {invocationId: query.invocationId, status, result, missing: false};
  });
  vi.mocked(api.getConversation).mockResolvedValue(result(conversation));
  vi.mocked(api.getConversationExecutionState).mockImplementation(async query => {
    const value = (await vi.mocked(api.getConversation).getMockImplementation()!(query)).body.data;
    return {
      httpStatus: 200, body: {
        success: true, code: '200', desc: 'OK', data: {
          conversationId: query.conversationId,
          version: value.version,
          activeExecution: null,
          observedAt: '2026-11-08T00:00:00Z'
        }
      }
    };
  });
  vi.mocked(api.queryTurnsOfConversation).mockResolvedValue(page());
  vi.mocked(api.listConversations).mockResolvedValue(page());
  vi.mocked(api.getBudget).mockResolvedValue(result({
    budgetRef: 'budget',
    accountRef: {tenantId: 'tenant', type: 'budget-account' as const, resourceId: 'budget'},
    reconciliationHandling: {status: 'UNASSIGNED' as const, responsible: null},
    currency: 'CNY',
    limit: '100',
    held: '0',
    charged: '0',
    available: '100.000000000000000001',
    rateVersion: {type: 'rate', id: 'rate', version: 'v1'},
    version: 0
  }));
  vi.mocked(api.turnsForChat).mockRejectedValue(new TypeError('network'));
  vi.mocked(api.regenerateChat).mockRejectedValue(new TypeError('network'));
  vi.mocked(api.watchInvocation).mockImplementation(async (_query, _handler, options) => new Promise<void>(resolve => options?.signal?.addEventListener('abort', () => resolve(), {once: true})));
});
afterEach(() => {
  cleanup();
  Modal.destroyAll();
});

describe('browser recovery uses original operations', () => {
  it('clears an unavailable restored conversation without automatically creating another', async () => {
    writeValue(storageKey(context, 'selectedConversation'), 'deleted');
    vi.mocked(api.getConversation).mockRejectedValue(new api.AiApiError(404, {desc: 'not found'}));
    const hook = renderHook(() => useConversations(scope, context));
    await waitFor(() => expect(hook.result.current.selectionNotice).not.toBeNull());
    expect(hook.result.current.selectedId).toBeNull();
    expect(readValue(storageKey(context, 'selectedConversation')).value).toBeNull();
    expect(api.createConversation).not.toHaveBeenCalled();
  });
  it('restores an uncertain message across unmount and keeps the original config and key', async () => {
    const first = session();
    await waitFor(() => expect(first.result.current.historyLoading).toBe(false));
    await waitFor(() => expect(first.result.current.budget).not.toBeNull());
    act(() => first.result.current.setDraft('原问题'));
    await act(async () => {
      await first.result.current.send();
    });
    const [original, key] = vi.mocked(api.turnsForChat).mock.calls[0];
    first.unmount();
    const second = session({...config, bindingId: 'changed', budgetRef: 'new-budget'});
    expect(second.result.current.pending?.key).toBe(key);
    expect(second.result.current.draft).toBe('原问题');
    await act(async () => {
      await second.result.current.send();
    });
    expect(vi.mocked(api.turnsForChat).mock.calls[1].slice(0, 2)).toEqual([original, key]);
  });
  it('preserves a separately edited draft after confirming an earlier submission', async () => {
    saveOperation(context, 'send', 'conversation', {key: 'old-key', request});
    writeValue(storageKey(context, 'draft', 'conversation'), '下一个问题');
    vi.mocked(api.turnsForChat).mockResolvedValue({
      ...result({
        invocationId: 'accepted',
        conversationId: 'conversation',
        kind: 'INVOCATION',
        acceptedAt: 'now',
        titleMetadata: {
          title: '首个问题的名称',
          metadataVersion: 1,
          titleManuallyEdited: false,
          autoTitleResolved: true
        }
      } as api.ChatAcceptedResponse), httpStatus: 202
    });
    vi.mocked(api.getInvocationStatus).mockResolvedValue(result({
      invocationId: 'accepted',
      blocksConversation: false,
      kind: 'GENERATION',
      conversation: {conversationId: conversation.conversationId, conversationVersion: 0, turnId: 'turn'},
      state: 'RUNNING',
      version: 1,
      budgetState: 'RESERVED',
      activeAttemptId: 'attempt',
      resultAvailable: false,
      partial: null,
      error: null,
      acceptedAt: 'now',
      updatedAt: 'now'
    }));
    const onTitle = vi.fn();
    const hook = renderHook(() => useChatSession(config, conversation, vi.fn(), undefined, undefined, context, true, true, onTitle));
    await act(async () => {
      await hook.result.current.send();
    });
    expect(hook.result.current.draft).toBe('下一个问题');
    expect(onTitle).toHaveBeenCalledWith('conversation', {
      title: '首个问题的名称',
      metadataVersion: 1,
      titleManuallyEdited: false,
      autoTitleResolved: true
    });
    expect(api.listConversations).not.toHaveBeenCalled();
    expect(readOperation<api.SubmitChatRequest>(context, 'send', 'conversation').value?.acceptedId).toBe('accepted');
  });
  it('does not release the original snapshot on an idempotency conflict', async () => {
    saveOperation(context, 'send', 'conversation', {key: 'old-key', request});
    vi.mocked(api.turnsForChat).mockRejectedValue(new api.AiApiError(409, {code: '205027', desc: 'conflict'}));
    const hook = session();
    await act(async () => {
      await hook.result.current.send();
    });
    expect(hook.result.current.pending?.key).toBe('old-key');
  });
  it('restores regenerate with its exact source and expected version', async () => {
    const original = {scope, originalInvocationId: 'source', expectedConversationVersion: 2, timeoutSeconds: 30};
    saveOperation(context, 'regenerate', 'conversation', {key: 'regen-key', request: original});
    const hook = session();
    await act(async () => {
      await hook.result.current.regenerate('another');
    });
    expect(api.regenerateChat).toHaveBeenCalledWith(original, 'regen-key', expect.anything());
  });
  it('restores stop with its original invocation and key', async () => {
    saveOperation(context, 'stop', 'conversation', {key: 'stop-key', request: {scope, invocationId: 'active'}});
    vi.mocked(api.cancelInvocation).mockRejectedValue(new TypeError('network'));
    const hook = session();
    await act(async () => {
      hook.result.current.retryStop();
    });
    await waitFor(() => expect(api.cancelInvocation).toHaveBeenCalledWith({
      scope,
      invocationId: 'active'
    }, 'stop-key', expect.anything()));
  });
  it('restores create with exact title and key instead of accepting edited parameters', async () => {
    saveOperation(context, 'create', '', {key: 'create-key', request: {scope, title: '原标题'}});
    vi.mocked(api.createConversation).mockRejectedValue(new TypeError('network'));
    const hook = renderHook(() => useConversations(scope, context));
    await act(async () => {
      await hook.result.current.create('新标题');
    });
    expect(api.createConversation).toHaveBeenCalledWith({scope, title: '原标题'}, 'create-key', expect.anything());
  });
  it('blocks new operations when recovery storage is corrupt', async () => {
    sessionStorage.setItem(storageKey(context, 'send', 'conversation'), '{broken');
    const hook = session();
    expect(hook.result.current.recoveryBlocked).toBe(true);
    act(() => hook.result.current.setDraft('新问题'));
    await act(async () => {
      await hook.result.current.send();
    });
    expect(api.turnsForChat).not.toHaveBeenCalled();
  });
  it.each([null, false, 0, ''])('blocks a present recovery envelope containing invalid value %s', value => {
    writeValue(storageKey(context, 'send', 'conversation'), value);
    expect(readOperation(context, 'send', 'conversation').warning).not.toBeNull();
  });
  it('isolates storage by account, workspace and conversation and clears account session on logout', () => {
    const key = storageKey(context, 'draft', 'conversation');
    writeValue(key, 'draft');
    expect(readValue(storageKey({...context, userId: 'account-b'}, 'draft', 'conversation')).value).toBeNull();
    expect(readValue(storageKey({
      ...context,
      scope: {...scope, workspaceId: 'other'}
    }, 'draft', 'conversation')).value).toBeNull();
    expect(readValue(storageKey(context, 'draft', 'other')).value).toBeNull();
    clearAccountSession(context.userId);
    expect(readValue(key).value).toBeNull();
  });
  it('reads history without a model and does not query a pretend budget or submit', async () => {
    const hook = session({...config, bindingId: '', budgetRef: ''}, false);
    await waitFor(() => expect(hook.result.current.historyLoading).toBe(false));
    expect(api.getConversation).not.toHaveBeenCalled();
    expect(api.queryTurnsOfConversation).toHaveBeenCalled();
    expect(api.getBudget).not.toHaveBeenCalled();
    act(() => hook.result.current.setDraft('问题'));
    await act(async () => {
      await hook.result.current.send();
    });
    expect(api.turnsForChat).not.toHaveBeenCalled();
  });
});

it('renders GFM tables and code while rejecting raw HTML and unsafe links', () => {
  render(<MarkdownText
    text={'| a | b |\n| - | - |\n| 1 | 2 |\n\n```js\nconst a = 1\n```\n\n<script>alert(1)</script>\n\n[unsafe](javascript:alert(1))'}/>);
  expect(screen.getByRole('table')).toBeInTheDocument();
  expect(screen.getByRole('button', {name: '复制代码'})).toBeInTheDocument();
  expect(document.querySelector('script')).toBeNull();
  expect(screen.getByText('unsafe').getAttribute('href')).not.toContain('javascript:');
});

it('keeps Shift+Enter and composing Enter from sending, then allows ordinary Enter', async () => {
  render(<BusinessChatPanel config={config} context={context} conversation={conversation} options={[]} dirty={false}
                            t={t} onUpdated={vi.fn()} onConfigurationRejected={vi.fn()} onChooseModel={vi.fn()}
                            onInteraction={vi.fn()} onOpenToolSettings={vi.fn()}/>);
  await waitFor(() => expect(screen.getByRole('button', {name: '发送'})).toBeDisabled());
  const input = screen.getByLabelText('输入问题');
  fireEvent.change(input, {target: {value: '中文问题'}});
  await waitFor(() => expect(screen.getByRole('button', {name: '发送'})).not.toBeDisabled());
  fireEvent.keyDown(input, {key: 'Enter', shiftKey: true});
  expect(api.turnsForChat).not.toHaveBeenCalled();
  fireEvent.compositionStart(input);
  fireEvent.keyDown(input, {key: 'Enter'});
  expect(api.turnsForChat).not.toHaveBeenCalled();
  fireEvent.compositionEnd(input);
  fireEvent.keyDown(input, {key: 'Enter'});
  await waitFor(() => expect(api.turnsForChat).toHaveBeenCalledTimes(1));
});

it('shares sidebar tool settings with the composer and submission, then resets them for another conversation', async () => {
  vi.mocked(discoverToolChat).mockResolvedValue(result({enabled: true}));
  vi.mocked(listToolBindings).mockResolvedValue(result({
    records: [{
      bindingId: 'count',
      toolId: 'count',
      version: 'v1',
      name: '文字统计',
      enabled: true,
      createdAt: '',
      updatedAt: ''
    }],
    scanned: 1, exhausted: true, nextCursor: null,
  }));
  vi.mocked(availableToolBudgets).mockResolvedValue(result({price: null, accounts: []}));
  vi.mocked(submitToolChat).mockRejectedValue(new TypeError('network'));
  const options: api.ChatModelOption[] = [{
    displayName: '工具模型', binding: request.binding, capability: request.capability,
    contextWindowTokens: 65536, toolCallsSupported: true, budgetRefs: ['budget'],
    limits: {
      maxInputTokens: 32768,
      maxOutputTokens: 4096,
      maxInputBytes: 100000,
      maxOutputBytes: 100000,
      maxTimeoutSeconds: 120
    },
    defaults: {maxInputTokens: 32768, maxOutputTokens: 512, timeoutSeconds: 60},
  }];

  function Harness({conversationId = 'conversation'}: { conversationId?: string }) {
    const [target, setTarget] = useState<HTMLDivElement | null>(null);
    const [active, setActive] = useState(false);
    return <>
      <aside aria-label="工具侧栏" hidden={!active}>
        <div ref={setTarget}/>
      </aside>
      <button onClick={() => setActive(false)}>收起工具侧栏</button>
      <BusinessChatPanel key={conversationId} config={config} context={context}
                         conversation={{...conversation, conversationId}} options={options} dirty={false}
                         t={t} onUpdated={vi.fn()} onConfigurationRejected={vi.fn()} onChooseModel={vi.fn()}
                         onInteraction={vi.fn()} onOpenToolSettings={() => setActive(true)}
                         toolSettingsContainer={target} toolSettingsActive={active}/>
    </>;
  }

  const view = render(<Harness/>);
  expect(discoverToolChat).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '工具设置'}));
  const toggle = screen.getByRole('switch', {name: '允许助手调用工具'});
  expect(toggle.closest('aside')).toHaveAccessibleName('工具侧栏');
  await waitFor(() => expect(toggle).toBeEnabled());
  fireEvent.click(toggle);
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '允许使用的工具'}));
  fireEvent.click(await screen.findByText('文字统计'));
  expect(screen.getByRole('button', {name: '工具已开启 · 1 项'})).toBeEnabled();
  fireEvent.change(screen.getByRole('textbox', {name: '工具聊天总费用上限'}), {target: {value: '2.50'}});
  fireEvent.click(screen.getByRole('button', {name: '收起工具侧栏'}));
  fireEvent.click(screen.getByRole('button', {name: '工具已开启 · 1 项'}));
  expect(screen.getByRole('textbox', {name: '工具聊天总费用上限'})).toHaveValue('2.50');
  fireEvent.change(screen.getByLabelText('输入问题'), {target: {value: '统计这段文字'}});
  await waitFor(() => expect(screen.getByRole('button', {name: '发送'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '发送'}));
  await waitFor(() => expect(submitToolChat).toHaveBeenCalledTimes(1));
  expect(vi.mocked(submitToolChat).mock.calls[0][0]).toMatchObject({
    conversationId: 'conversation', toolBindingIds: ['count'],
    budgetLimits: [{currency: 'CNY', amount: '2.50'}],
  });
  expect(api.turnsForChat).not.toHaveBeenCalled();
  expect(screen.getByRole('switch', {name: '允许助手调用工具'})).toBeDisabled();
  view.rerender(<Harness conversationId="another-conversation"/>);
  expect(screen.getByRole('switch', {name: '允许助手调用工具'})).not.toBeChecked();
  expect(screen.getByRole('button', {name: '工具设置'})).toBeInTheDocument();
  expect(screen.queryByRole('textbox', {name: '工具聊天总费用上限'})).toBeNull();
});

it('restores a reconciliation and queries the receipt without issuing a new charge', async () => {
  const original = {
    scope,
    budgetRef: 'budget',
    invocationId: 'unknown',
    invocationVersion: 2,
    reservationId: 'reservation',
    reservationVersion: 1,
    actualCharge: '0.000000000000000001',
    currency: 'CNY',
    evidenceRef: 'evidence',
    note: '核实',
    executionEnded: true
  };
  saveOperation(context, 'reconcile', 'budget', {key: 'reconcile-key', request: original});
  vi.mocked(api.pendingReconciliations).mockResolvedValue(result({
    page: page<api.PendingReconciliation>().body,
    canReconcile: false
  }));
  vi.mocked(api.reconciliationReceipt).mockRejectedValue(new api.AiApiError(404, {desc: 'not found'}));
  render(<ReconciliationPanel context={context} config={config} t={t} onConfirmed={vi.fn()}/>);
  fireEvent.click(await screen.findByText('查询原核对回执'));
  await waitFor(() => expect(api.reconciliationReceipt).toHaveBeenCalledWith({
    scope,
    budgetRef: 'budget',
    invocationId: 'unknown',
    key: 'reconcile-key'
  }, expect.anything()));
  expect(api.confirmReconciliation).not.toHaveBeenCalled();
  expect(readOperation(context, 'reconcile', 'budget').value?.key).toBe('reconcile-key');
});


describe('progressive history and asynchronous notification refresh', () => {
  const completed: api.InvocationStatusResponse = {
    blocksConversation: false,
    invocationId: 'inv',
    kind: 'GENERATION',
    conversation: {conversationId: conversation.conversationId, conversationVersion: 0, turnId: 'turn'},
    state: 'SUCCEEDED',
    version: 3,
    activeAttemptId: 'attempt', budgetState: 'SETTLED', resultAvailable: false, partial: null,
    error: null, acceptedAt: conversation.createdAt, updatedAt: conversation.updatedAt,
  };
  const historyTurn: api.ConversationTurnResponse = {
    turnId: 'turn', conversationId: conversation.conversationId, sequence: 1,
    parentTurnId: null, supersedesTurnId: null, selectedInvocationId: 'inv', invocationIds: ['inv'], version: 1,
    userMessage: {messageId: 'message', role: 'USER', content: [{text: '先显示问题'}], toolCalls: [], toolCallId: null},
    createdAt: conversation.createdAt, updatedAt: conversation.updatedAt,
  };
  it('shows questions while snapshots are pending and prevents submitting with unverified history', async () => {
    let finish!: (value: api.InvocationSnapshotResponse) => void;
    vi.mocked(api.queryTurnsOfConversation).mockResolvedValue(page([historyTurn]));
    vi.mocked(api.getInvocationSnapshot).mockReturnValue(new Promise(resolve => {
      finish = resolve;
    }));
    const hook = session();
    await waitFor(() => expect(hook.result.current.turns).toEqual([historyTurn]));
    expect(hook.result.current.historyLoading).toBe(true);
    act(() => hook.result.current.setDraft('下一题'));
    await act(async () => hook.result.current.send());
    expect(api.turnsForChat).not.toHaveBeenCalled();
    await act(async () => {
      finish({invocationId: 'inv', status: completed, result: null, missing: false});
    });
    await waitFor(() => expect(hook.result.current.historyLoading).toBe(false));
    expect(hook.result.current.historyError).toBeNull();
  });
  it('returns from a burst of SSE callbacks before slow HTTP completes and coalesces the refresh', async () => {
    vi.mocked(api.queryTurnsOfConversation).mockResolvedValue(page([historyTurn]));
    vi.mocked(api.getInvocationSnapshot).mockResolvedValue({
      invocationId: 'inv',
      status: {...completed, state: 'RUNNING', version: 1, budgetState: 'RESERVED'},
      result: null,
      missing: false
    });
    const hook = session();
    await waitFor(() => expect(hook.result.current.historyLoading).toBe(false));
    await waitFor(() => expect(api.watchInvocation).toHaveBeenCalledOnce());
    const reads = vi.mocked(api.getInvocationSnapshot).mock.calls.length;
    let finish!: (value: api.InvocationSnapshotResponse) => void;
    vi.mocked(api.getInvocationSnapshot).mockImplementation(() => new Promise(resolve => {
      finish = resolve;
    }));
    const notify = vi.mocked(api.watchInvocation).mock.calls[0][1];
    await act(async () => {
      await notify({executionId: 'inv', sequence: 2, kind: 'STARTED'});
      await notify({executionId: 'inv', sequence: 3, kind: 'CONTROL'});
      await notify({executionId: 'inv', sequence: 4, kind: 'TERMINAL'});
    });
    expect(api.getInvocationSnapshot).toHaveBeenCalledTimes(reads);
    await waitFor(() => expect(api.getInvocationSnapshot).toHaveBeenCalledTimes(reads + 1));
    await act(async () => finish({invocationId: 'inv', status: completed, result: null, missing: false}));
    await waitFor(() => expect(hook.result.current.activeIds).toEqual([]));
    expect(hook.result.current.invocations.inv.status?.state).toBe('SUCCEEDED');
    expect(api.getInvocationSnapshot).toHaveBeenCalledTimes(reads + 1);
  });
  it('bounds terminal read retries after the notification cursor is recorded and leaves manual refresh', async () => {
    vi.mocked(api.queryTurnsOfConversation).mockResolvedValue(page([historyTurn]));
    vi.mocked(api.getInvocationSnapshot).mockResolvedValue({
      invocationId: 'inv',
      status: {...completed, state: 'RUNNING', version: 1, budgetState: 'RESERVED'}, result: null, missing: false
    });
    const hook = session();
    await waitFor(() => expect(hook.result.current.historyLoading).toBe(false));
    await waitFor(() => expect(api.watchInvocation).toHaveBeenCalledOnce());
    const reads = vi.mocked(api.getInvocationSnapshot).mock.calls.length;
    vi.mocked(api.getInvocationSnapshot).mockRejectedValue(new TypeError('offline'));
    vi.mocked(api.getInvocationStatus).mockRejectedValue(new TypeError('offline'));
    vi.useFakeTimers();
    try {
      await act(async () => {
        await vi.mocked(api.watchInvocation).mock.calls[0][1]({executionId: 'inv', sequence: 4, kind: 'TERMINAL'});
        await vi.advanceTimersByTimeAsync(50);
      });
      expect(api.getInvocationSnapshot).toHaveBeenCalledTimes(reads + 1);
      await act(async () => vi.advanceTimersByTimeAsync(1000));
      expect(api.getInvocationSnapshot).toHaveBeenCalledTimes(reads + 2);
      await act(async () => vi.advanceTimersByTimeAsync(3000));
      expect(api.getInvocationSnapshot).toHaveBeenCalledTimes(reads + 3);
      expect(hook.result.current.pollPaused).toBe(true);
      await act(async () => vi.advanceTimersByTimeAsync(30000));
      expect(api.getInvocationSnapshot).toHaveBeenCalledTimes(reads + 3);
      expect(api.turnsForChat).not.toHaveBeenCalled();
      expect(api.regenerateChat).not.toHaveBeenCalled();
      vi.mocked(api.getInvocationSnapshot).mockResolvedValue({
        invocationId: 'inv',
        status: completed,
        result: null,
        missing: false
      });
      await act(async () => hook.result.current.resume());
      await act(async () => vi.advanceTimersByTimeAsync(0));
      expect(hook.result.current.activeIds).toEqual([]);
      expect(hook.result.current.invocations.inv.status?.state).toBe('SUCCEEDED');
    } finally {
      vi.useRealTimers();
    }
  });
  it('stops HTTP fallback after an expired SSE cursor exhausts its bounded reads', async () => {
    vi.useFakeTimers();
    vi.mocked(api.queryTurnsOfConversation).mockResolvedValue(page([historyTurn]));
    vi.mocked(api.getInvocationSnapshot).mockResolvedValue({
      invocationId: 'inv',
      status: {...completed, blocksConversation: true, state: 'RUNNING', version: 1, budgetState: 'RESERVED'},
      result: null,
      missing: false
    });
    vi.mocked(api.watchInvocation).mockRejectedValueOnce(new api.AiApiError(410, {}));
    const hook = session();
    try {
      await act(async () => vi.advanceTimersByTimeAsync(0));
      expect(api.watchInvocation).toHaveBeenCalledOnce();
      const reads = vi.mocked(api.getInvocationSnapshot).mock.calls.length;
      vi.mocked(api.getInvocationSnapshot).mockRejectedValue(new TypeError('offline'));
      vi.mocked(api.getInvocationStatus).mockRejectedValue(new TypeError('offline'));
      await act(async () => vi.advanceTimersByTimeAsync(14000));
      expect(api.getInvocationSnapshot).toHaveBeenCalledTimes(reads + 3);
      expect(hook.result.current.pollPaused).toBe(true);
      expect(hook.result.current.watching).toBe(false);
      await act(async () => vi.advanceTimersByTimeAsync(30000));
      expect(api.getInvocationSnapshot).toHaveBeenCalledTimes(reads + 3);
      expect(api.turnsForChat).not.toHaveBeenCalled();
    } finally {
      hook.unmount();
      vi.useRealTimers();
    }
  });

});

it('external Run acceptance invalidates a concurrent history read before its old version can be published', async () => {
  let resolve!: (value: Awaited<ReturnType<typeof api.queryTurnsOfConversation>>) => void;
  vi.mocked(api.queryTurnsOfConversation).mockImplementationOnce(() => new Promise(done => {
    resolve = done;
  }));
  vi.mocked(api.getConversation).mockResolvedValue(result({...conversation, version: 4}));
  const updated = vi.fn();
  const hook = renderHook(() => useChatSession(config, conversation, updated, undefined, undefined, context));
  await waitFor(() => expect(api.queryTurnsOfConversation).toHaveBeenCalledTimes(1));
  act(() => hook.result.current.refreshAfterExternalExecution());
  await act(async () => {
    resolve(page<api.ConversationTurnResponse>());
  });
  await waitFor(() => expect(hook.result.current.historyLoading).toBe(false));
  expect(api.queryTurnsOfConversation).toHaveBeenCalledTimes(2);
  expect(updated).toHaveBeenCalledTimes(1);
  expect(updated.mock.calls[0][0].version).toBe(4);
});

it('typed Run history does not read its final model child as a standalone conversation candidate', async () => {
  const candidate = {kind: 'TOOL_CHAT_RUN' as const, executionId: 'run'};
  vi.mocked(api.queryTurnsOfConversation).mockResolvedValue(page([{
    turnId: 'turn',
    conversationId: conversation.conversationId,
    sequence: 1,
    parentTurnId: null,
    supersedesTurnId: null,
    userMessage: {messageId: 'user', role: 'USER', content: [{text: 'question'}], toolCalls: [], toolCallId: null},
    invocationIds: [],
    selectedInvocationId: 'final-child',
    candidates: [candidate],
    selectedCandidate: candidate,
    version: 1,
    createdAt: conversation.createdAt,
    updatedAt: conversation.updatedAt
  }]));
  const hook = session();
  await waitFor(() => expect(hook.result.current.historyLoading).toBe(false));
  expect(hook.result.current.turns[0].selectedCandidate?.executionId).toBe('run');
  expect(api.getInvocationSnapshot).not.toHaveBeenCalled();
});

describe('ER-4 authority, damaged recovery and explicit draft actions', () => {
  const terminal: api.InvocationStatusResponse = {
    invocationId: 'unknown', blocksConversation: false, kind: 'GENERATION',
    conversation: {conversationId: 'conversation', conversationVersion: 3, turnId: 'turn'},
    state: 'UNKNOWN', version: 4, budgetState: 'PENDING_RECONCILIATION',
    activeAttemptId: null, resultAvailable: false, partial: null,
    error: null, acceptedAt: '2026-11-08T00:00:00Z', updatedAt: '2026-11-08T00:00:00Z',
  };
  const historyTurn: api.ConversationTurnResponse = {
    turnId: 'turn', conversationId: 'conversation', sequence: 1, parentTurnId: null, supersedesTurnId: null,
    userMessage: {messageId: 'message', role: 'USER', content: [{text: '历史原问题'}], toolCalls: [], toolCallId: null},
    invocationIds: ['unknown'], selectedInvocationId: 'unknown', version: 1,
    createdAt: conversation.createdAt, updatedAt: conversation.updatedAt,
  };
  const ready = async (hook: ReturnType<typeof session>) => waitFor(() => {
    expect(hook.result.current.historyLoading).toBe(false);
    expect(hook.result.current.historyError).toBeNull();
    expect(hook.result.current.budget).not.toBeNull();
  });
  const statusRead = (status = terminal) => vi.mocked(api.getInvocationSnapshot).mockResolvedValue({
    invocationId: status.invocationId, status, result: null, missing: false,
  });
  it('recovers by a known ID with an invalid request/key, preserves damaged record and never confirms a write', async () => {
    const key = storageKey(context, 'send', 'conversation');
    writeValue(key, {key: null, request: {broken: true}, acceptedId: 'unknown'});
    const raw = sessionStorage.getItem(key);
    statusRead();
    const hook = session();
    expect(hook.result.current.recoveryBlocked).toBe(true);
    await ready(hook);
    await waitFor(() => expect(hook.result.current.recoveryBlocked).toBe(false));
    expect(hook.result.current.pending).toBeNull();
    expect(hook.result.current.unresolved).toBe(false);
    expect(sessionStorage.getItem(key)).toBe(raw);
    expect(api.turnsForChat).not.toHaveBeenCalled();
    act(() => hook.result.current.setDraft('新问题'));
    await act(async () => hook.result.current.send());
    expect(vi.mocked(api.turnsForChat).mock.calls[0][0]).toMatchObject({
      text: '新问题',
      binding: request.binding,
      expectedVersion: 3
    });
    const archive = Array.from({length: sessionStorage.length}, (_, index) => sessionStorage.key(index)!).find(key => key.startsWith('arte.ainew.recovery-damaged-send-'));
    expect(archive).toBeDefined();
    expect(sessionStorage.getItem(archive!)).toBe(raw);
  });
  it('requires authorized conversation ownership for a recovered ID', async () => {
    writeValue(storageKey(context, 'send', 'conversation'), {acceptedId: 'unknown'});
    statusRead({...terminal, conversation: {...terminal.conversation!, conversationId: 'another'}});
    const hook = session();
    await waitFor(() => expect(hook.result.current.historyLoading).toBe(false));
    expect(hook.result.current.recoveryBlocked).toBe(true);
    expect(hook.result.current.historyError ?? hook.result.current.pollError).not.toBeNull();
    expect(hook.result.current.turns).toEqual([]);
    expect(api.turnsForChat).not.toHaveBeenCalled();
  });
  it('isolates an unrecoverable record locally, keeps the original read only, and permits another conversation', async () => {
    const key = storageKey(context, 'send', 'conversation');
    sessionStorage.setItem(key, '{broken');
    const hook = session();
    await ready(hook);
    act(() => {
      hook.result.current.setDraft('保留草稿');
      hook.result.current.isolateRecovery();
    });
    expect(hook.result.current.recoveryIsolated).toBe(true);
    expect(hook.result.current.recoveryBlocked).toBe(true);
    await act(async () => hook.result.current.send());
    expect(api.turnsForChat).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(key)).toBe('{broken');
    expect(readValue(storageKey(context, 'draft', 'conversation')).value).toBe('保留草稿');
    hook.unmount();
    const reopened = session();
    expect(reopened.result.current.recoveryIsolated).toBe(true);
    expect(reopened.result.current.recoveryBlocked).toBe(true);
    const other = renderHook(() => useChatSession(config, {
      ...conversation,
      conversationId: 'other'
    }, vi.fn(), undefined, undefined, context));
    await waitFor(() => expect(other.result.current.historyLoading).toBe(false));
    expect(other.result.current.recoveryBlocked).toBe(false);
    act(() => other.result.current.setDraft('独立问题'));
    await act(async () => other.result.current.send());
    expect(vi.mocked(api.turnsForChat).mock.calls[0][0].conversationId).toBe('other');
    expect(api.createConversation).not.toHaveBeenCalled();
  });
  it.each(['INVOCATION', 'RUN'] as const)('an old nonblocking UNKNOWN cannot override a current %s occupant', async kind => {
    vi.mocked(api.queryTurnsOfConversation).mockResolvedValue(page([historyTurn]));
    vi.mocked(api.getConversationExecutionState).mockResolvedValue(result({
      conversationId: 'conversation', version: 3,
      activeExecution: {kind, executionId: 'other',}, observedAt: terminal.updatedAt
    }));
    vi.mocked(api.getInvocationSnapshot).mockImplementation(async ({invocationId}) => ({
      invocationId,
      status: invocationId === 'unknown' ? terminal : {
        ...terminal,
        invocationId,
        state: 'RUNNING',
        blocksConversation: true,
        budgetState: 'RESERVED'
      }, result: null, missing: false
    }));
    const hook = session();
    await ready(hook);
    expect(hook.result.current.unresolved).toBe(true);
    act(() => hook.result.current.setDraft('下一轮'));
    await act(async () => hook.result.current.send());
    await act(async () => hook.result.current.regenerate('unknown'));
    expect(api.turnsForChat).not.toHaveBeenCalled();
    expect(api.regenerateChat).not.toHaveBeenCalled();
    expect(hook.result.current.draft).toBe('下一轮');
  });
  it('accepts same-version occupancy release while pending fees remain', async () => {
    vi.mocked(api.queryTurnsOfConversation).mockResolvedValue(page([historyTurn]));
    statusRead();
    vi.mocked(api.getConversationExecutionState).mockResolvedValue(result({
      conversationId: 'conversation', version: 3,
      activeExecution: {kind: 'RUN', executionId: 'other'}, observedAt: terminal.updatedAt
    }));
    const hook = session();
    await ready(hook);
    expect(hook.result.current.unresolved).toBe(true);
    vi.mocked(api.getConversationExecutionState).mockResolvedValue(result({
      conversationId: 'conversation', version: 3,
      activeExecution: null, observedAt: terminal.updatedAt
    }));
    await act(async () => hook.result.current.resume());
    await ready(hook);
    expect(hook.result.current.unresolved).toBe(false);
    expect(hook.result.current.invocations.unknown.status?.budgetState).toBe('PENDING_RECONCILIATION');
    act(() => hook.result.current.setDraft('继续提问'));
    await act(async () => hook.result.current.send());
    expect(api.turnsForChat).toHaveBeenCalledOnce();
  });
  it('clears stale readable data when current authorization is revoked, without automatic retry or write', async () => {
    vi.mocked(api.queryTurnsOfConversation).mockResolvedValue(page([historyTurn]));
    statusRead();
    const hook = session();
    await ready(hook);
    vi.mocked(api.getConversationExecutionState).mockRejectedValue(new api.AiApiError(403, {}));
    const reads = vi.mocked(api.getConversationExecutionState).mock.calls.length;
    await act(async () => hook.result.current.resume());
    await waitFor(() => expect(hook.result.current.historyError).not.toBeNull());
    expect(api.getConversationExecutionState).toHaveBeenCalledTimes(reads + 1);
    expect(hook.result.current.turns).toEqual([]);
    expect(hook.result.current.invocations).toEqual({});
    expect(hook.result.current.unresolved).toBe(true);
    expect(api.turnsForChat).not.toHaveBeenCalled();
  });
  it('refreshes occupancy without repeatedly scanning unchanged full history, pauses hidden and refreshes visible', async () => {
    const intervals = vi.spyOn(globalThis, 'setInterval');
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    try {
      const hook = session();
      await ready(hook);
      const historyReads = vi.mocked(api.queryTurnsOfConversation).mock.calls.length;
      const stateReads = vi.mocked(api.getConversationExecutionState).mock.calls.length;
      const tick = intervals.mock.calls.find(([, delay]) => delay === 10000)![0] as () => void;
      await act(async () => tick());
      expect(api.getConversationExecutionState).toHaveBeenCalledTimes(stateReads + 1);
      expect(api.queryTurnsOfConversation).toHaveBeenCalledTimes(historyReads);
      visibility.mockReturnValue('hidden');
      act(() => document.dispatchEvent(new Event('visibilitychange')));
      await act(async () => {
        tick();
        tick();
      });
      expect(api.getConversationExecutionState).toHaveBeenCalledTimes(stateReads + 1);
      visibility.mockReturnValue('visible');
      await act(async () => document.dispatchEvent(new Event('visibilitychange')));
      expect(api.getConversationExecutionState).toHaveBeenCalledTimes(stateReads + 2);
      expect(api.queryTurnsOfConversation).toHaveBeenCalledTimes(historyReads + 1);
      expect(api.turnsForChat).not.toHaveBeenCalled();
    } finally {
      visibility.mockRestore();
      intervals.mockRestore();
    }
  });
  it('keeps draft and configuration after a version conflict and never retries the write', async () => {
    const hook = session();
    await ready(hook);
    vi.mocked(api.turnsForChat).mockRejectedValue(new api.AiApiError(409, {code: '205035'}));
    act(() => hook.result.current.setDraft('当前问题'));
    await act(async () => hook.result.current.send());
    await ready(hook);
    expect(api.turnsForChat).toHaveBeenCalledOnce();
    expect(hook.result.current.draft).toBe('当前问题');
    expect(hook.result.current.pending).toBeNull();
    expect(vi.mocked(api.turnsForChat).mock.calls[0][0].binding).toEqual(request.binding);
  });
});

it('copies only a question into the draft, defaults to keep a nonempty draft, and never submits automatically', async () => {
  const messages = (await import('@/locales/zh-CN/aiChat')).default;
  const uiT = (key: string) => messages[`app.aiChat.${key}` as keyof typeof messages] ?? key;
  vi.mocked(api.queryTurnsOfConversation).mockResolvedValue(page([{
    turnId: 'failed-turn', conversationId: 'conversation', sequence: 1, parentTurnId: null, supersedesTurnId: null,
    selectedInvocationId: 'unknown', invocationIds: ['unknown'], version: 1,
    userMessage: {
      messageId: 'question',
      role: 'USER',
      content: [{text: '可复制的原问题'}],
      toolCalls: [],
      toolCallId: null
    },
    createdAt: conversation.createdAt, updatedAt: conversation.updatedAt,
  }]));
  vi.mocked(api.getInvocationSnapshot).mockResolvedValue({
    invocationId: 'unknown', missing: false, result: null, status: {
      invocationId: 'unknown', blocksConversation: false, kind: 'GENERATION', state: 'UNKNOWN', version: 4,
      conversation: {conversationId: 'conversation', conversationVersion: 3, turnId: 'failed-turn'},
      activeAttemptId: null, budgetState: 'PENDING_RECONCILIATION', resultAvailable: false, partial: null,
      error: null, acceptedAt: conversation.createdAt, updatedAt: conversation.updatedAt,
    }
  });
  writeValue(storageKey(context, 'draft', 'conversation'), '已有草稿');
  render(<BusinessChatPanel config={config} context={context} conversation={conversation} options={[]} dirty={false}
                            onUpdated={vi.fn()} onChooseModel={vi.fn()} onConfigurationRejected={vi.fn()}
                            onInteraction={vi.fn()} onOpenToolSettings={vi.fn()} t={uiT}/>);
  const copy = await screen.findByRole('button', {name: uiT('recovery.copyQuestion')});
  await screen.findByText(uiT('recovery.turnSkipped'));
  expect(screen.getByRole('textbox', {name: '输入问题'})).toHaveValue('已有草稿');
  fireEvent.click(copy);
  const keep = await screen.findByRole('button', {name: uiT('recovery.keepDraft')});
  await waitFor(() => expect(keep).toHaveFocus());
  await act(async () => {
    fireEvent.click(keep);
    await new Promise(resolve => setTimeout(resolve, 30));
  });
  const dialog = screen.getByRole('dialog');
  fireEvent.animationEnd(dialog);
  fireEvent.transitionEnd(dialog);
  expect(screen.getByRole('textbox', {name: '输入问题'})).toHaveValue('已有草稿');
  await waitFor(() => expect(screen.queryByRole('button', {name: uiT('recovery.replace')})).toBeNull());
  fireEvent.click(copy);
  fireEvent.click(await screen.findByRole('button', {name: uiT('recovery.replace')}));
  await waitFor(() => expect(screen.getByRole('textbox', {name: '输入问题'})).toHaveValue('可复制的原问题'));
  expect(screen.getByText(uiT('recovery.currentConfiguration'))).toBeInTheDocument();
  expect(api.turnsForChat).not.toHaveBeenCalled();
  expect(api.regenerateChat).not.toHaveBeenCalled();
  expect(api.confirmReconciliation).not.toHaveBeenCalled();
});

it('regenerates from the original source without requiring a current model or budget selection', async () => {
  const state: api.InvocationStatusResponse = {
    invocationId: 'source',
    blocksConversation: false,
    kind: 'GENERATION',
    conversation: {conversationId: 'conversation', conversationVersion: 3, turnId: 'turn'},
    state: 'UNKNOWN',
    version: 1,
    activeAttemptId: null,
    budgetState: 'PENDING_RECONCILIATION',
    resultAvailable: false,
    partial: null,
    error: null,
    acceptedAt: conversation.createdAt,
    updatedAt: conversation.updatedAt
  };
  vi.mocked(api.getInvocationSnapshot).mockResolvedValue({
    invocationId: 'source',
    status: state,
    result: null,
    missing: false
  });
  const hook = session({...config, bindingId: '', budgetRef: ''}, false);
  await waitFor(() => expect(hook.result.current.historyLoading).toBe(false));
  await act(async () => hook.result.current.regenerate('source'));
  expect(vi.mocked(api.regenerateChat).mock.calls[0][0]).toEqual({
    scope,
    originalInvocationId: 'source',
    expectedConversationVersion: 3,
    timeoutSeconds: config.timeoutSeconds
  });
  expect(api.turnsForChat).not.toHaveBeenCalled();
});

it('marks only a failed viewed candidate when a complete successful answer remains selected for context', async () => {
  const messages = (await import('@/locales/zh-CN/aiChat')).default;
  const uiT = (key: string) => messages[`app.aiChat.${key}` as keyof typeof messages] ?? key;
  vi.mocked(api.queryTurnsOfConversation).mockResolvedValue(page([{
    turnId: 'turn', conversationId: 'conversation', sequence: 1, parentTurnId: null, supersedesTurnId: null,
    selectedInvocationId: 'success', invocationIds: ['success', 'failed'], version: 1,
    userMessage: {
      messageId: 'question',
      role: 'USER',
      content: [{text: '保留旧答案的问题'}],
      toolCalls: [],
      toolCallId: null
    },
    createdAt: conversation.createdAt, updatedAt: conversation.updatedAt,
  }]));
  vi.mocked(api.getInvocationSnapshot).mockImplementation(async ({invocationId}) => ({
    invocationId, missing: false,
    status: {
      invocationId,
      kind: 'GENERATION',
      blocksConversation: false,
      state: invocationId === 'success' ? 'SUCCEEDED' : 'UNKNOWN',
      conversation: {conversationId: 'conversation', conversationVersion: 3, turnId: 'turn'},
      version: 3,
      activeAttemptId: null,
      budgetState: invocationId === 'success' ? 'SETTLED' : 'PENDING_RECONCILIATION',
      resultAvailable: invocationId === 'success',
      partial: null,
      error: null,
      acceptedAt: conversation.createdAt,
      updatedAt: conversation.updatedAt
    },
    result: invocationId !== 'success' ? null : {
      invocationId, kind: 'GENERATION', result: {
        value: {
          resultId: 'answer', model: {providerId: 'provider', modelId: 'model', revision: null},
          outputs: [{
            messageId: 'answer-message',
            role: 'ASSISTANT',
            content: [{text: '原完整成功答案'}],
            toolCalls: [],
            toolCallId: null
          }],
          finishReason: 'STOP', complete: true, structuredOutput: null,
          usage: {basis: 'PROVIDER_REPORTED', inputTokens: 3, outputTokens: 3, totalTokens: 6}, sources: [],
        }
      }
    },
  }));
  render(<BusinessChatPanel config={config} context={context} conversation={conversation} options={[]} dirty={false}
                            onUpdated={vi.fn()} onChooseModel={vi.fn()} onConfigurationRejected={vi.fn()}
                            onInteraction={vi.fn()} onOpenToolSettings={vi.fn()} t={uiT}/>);
  await screen.findByText('原完整成功答案');
  expect(screen.getByText('回复 1 · 后续上下文采用')).toBeInTheDocument();
  expect(screen.queryByText(uiT('recovery.turnSkipped'))).toBeNull();
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '浏览第 1 轮候选回复'}));
  fireEvent.click(await screen.findByText('回复 2'));
  await screen.findByText(uiT('recovery.candidateSkipped'));
  expect(screen.queryByText(uiT('recovery.turnSkipped'))).toBeNull();
  expect(api.turnsForChat).not.toHaveBeenCalled();
  expect(api.regenerateChat).not.toHaveBeenCalled();
});

it('uses the newly applied configuration when resuming from focus after initially reading without a model', async () => {
  const hook = renderHook(({enabled}) => useChatSession(enabled ? config : {...config, bindingId: '', budgetRef: ''},
    conversation, vi.fn(), undefined, undefined, context, enabled), {initialProps: {enabled: false}});
  await waitFor(() => expect(hook.result.current.historyLoading).toBe(false));
  expect(api.getBudget).not.toHaveBeenCalled();
  hook.rerender({enabled: true});
  await waitFor(() => expect(hook.result.current.budget?.budgetRef).toBe('budget'));
  await act(async () => window.dispatchEvent(new Event('focus')));
  await waitFor(() => expect(hook.result.current.historyLoading).toBe(false));
  expect(hook.result.current.budget?.available).toBe('100.000000000000000001');
  expect(hook.result.current.budgetError).toBeNull();
  expect(api.turnsForChat).not.toHaveBeenCalled();
});

it('lets the user isolate corrupt recovery through the page and release only the navigation guard', async () => {
  const messages = (await import('@/locales/zh-CN/aiChat')).default;
  const uiT = (key: string) => messages[`app.aiChat.${key}` as keyof typeof messages] ?? key;
  const key = storageKey(context, 'send', 'conversation');
  sessionStorage.setItem(key, '{broken');
  const interaction = vi.fn();
  render(<BusinessChatPanel config={config} context={context} conversation={conversation} options={[]} dirty={false}
                            onUpdated={vi.fn()} onChooseModel={vi.fn()} onConfigurationRejected={vi.fn()}
                            onInteraction={interaction} onOpenToolSettings={vi.fn()} t={uiT}/>);
  await waitFor(() => expect(interaction.mock.calls.at(-1)?.[0].unsafeToLeave).toBe(true));
  fireEvent.change(screen.getByRole('textbox', {name: '输入问题'}), {target: {value: '保留的问题'}});
  expect(screen.queryByRole('button', {name: '重试确认原提交'})).toBeNull();
  fireEvent.click(screen.getByRole('button', {name: uiT('recovery.leave')}));
  const stay = await screen.findByRole('button', {name: uiT('recovery.stay')});
  await waitFor(() => expect(stay).toHaveFocus());
  expect(screen.getByText(uiT('recovery.leaveWarning'))).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: uiT('recovery.isolate')}));
  await waitFor(() => expect(interaction.mock.calls.at(-1)?.[0].unsafeToLeave).toBe(false));
  expect(screen.getByRole('button', {name: '发送'})).toBeDisabled();
  expect(screen.getByRole('textbox', {name: '输入问题'})).toHaveValue('保留的问题');
  expect(sessionStorage.getItem(key)).toBe('{broken');
  expect(api.turnsForChat).not.toHaveBeenCalled();
  expect(api.regenerateChat).not.toHaveBeenCalled();
  expect(api.cancelInvocation).not.toHaveBeenCalled();
  expect(api.createConversation).not.toHaveBeenCalled();
});

it('explains insufficient available funds as held money while leaving the record lookup in the costs view', async () => {
  const messages = (await import('@/locales/zh-CN/aiChat')).default;
  const uiT = (key: string) => messages[`app.aiChat.${key}` as keyof typeof messages] ?? key;
  vi.mocked(api.getBudget).mockResolvedValue(result({
    budgetRef: 'budget',
    accountRef: {tenantId: 'tenant', type: 'budget-account' as const, resourceId: 'budget'},
    reconciliationHandling: {status: 'UNASSIGNED' as const, responsible: null},
    currency: 'CNY',
    limit: '10',
    held: '10',
    charged: '0', available: '0', rateVersion: {type: 'rate', id: 'rate', version: 'v1'}, version: 1
  }));
  render(<BusinessChatPanel config={config} context={context} conversation={conversation} options={[]} dirty={false}
                            onUpdated={vi.fn()} onChooseModel={vi.fn()} onConfigurationRejected={vi.fn()}
                            onInteraction={vi.fn()} onOpenToolSettings={vi.fn()} t={uiT}/>);
  fireEvent.change(screen.getByRole('textbox', {name: '输入问题'}), {target: {value: '新问题'}});
  await screen.findByText(`${uiT('recovery.insufficientHeld')} (10 CNY)`);
  expect(screen.getByRole('button', {name: '发送'})).toBeDisabled();
  expect(screen.getByRole('button', {name: '查看费用'})).toBeEnabled();
  expect(screen.queryByText(uiT('recovery.busy'))).toBeNull();
  expect(screen.queryByText(uiT('recovery.insufficientWithPending'))).toBeNull();
  expect(api.confirmReconciliation).not.toHaveBeenCalled();
  expect(api.turnsForChat).not.toHaveBeenCalled();
});
