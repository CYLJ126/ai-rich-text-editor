import React from 'react';
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

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai', async original => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  getConversation: vi.fn(),
  queryTurnsOfConversation: vi.fn(),
  getBudget: vi.fn(),
  getInvocationStatus: vi.fn(),
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
  vi.mocked(api.getConversation).mockResolvedValue(result(conversation));
  vi.mocked(api.queryTurnsOfConversation).mockResolvedValue(page());
  vi.mocked(api.listConversations).mockResolvedValue(page());
  vi.mocked(api.getBudget).mockResolvedValue(result({
    budgetRef: 'budget',
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
afterEach(cleanup);

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
        acceptedAt: 'now'
      } as api.ChatAcceptedResponse), httpStatus: 202
    });
    vi.mocked(api.getInvocationStatus).mockResolvedValue(result({
      invocationId: 'accepted',
      kind: 'GENERATION',
      conversation: null,
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
    const hook = session();
    await act(async () => {
      await hook.result.current.send();
    });
    expect(hook.result.current.draft).toBe('下一个问题');
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
    expect(api.getConversation).toHaveBeenCalled();
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

it('keeps composing Enter from sending, then allows ordinary Enter', async () => {
  render(<BusinessChatPanel config={config} context={context} conversation={conversation} options={[]} dirty={false}
                            t={t} onUpdated={vi.fn()} onConfigurationRejected={vi.fn()} onChooseModel={vi.fn()}
                            onInteraction={vi.fn()}/>);
  await waitFor(() => expect(screen.getByRole('button', {name: '发送'})).toBeDisabled());
  const input = screen.getByLabelText('输入问题');
  fireEvent.change(input, {target: {value: '中文问题'}});
  await waitFor(() => expect(screen.getByRole('button', {name: '发送'})).not.toBeDisabled());
  fireEvent.compositionStart(input);
  fireEvent.keyDown(input, {key: 'Enter'});
  expect(api.turnsForChat).not.toHaveBeenCalled();
  fireEvent.compositionEnd(input);
  fireEvent.keyDown(input, {key: 'Enter'});
  await waitFor(() => expect(api.turnsForChat).toHaveBeenCalledTimes(1));
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
