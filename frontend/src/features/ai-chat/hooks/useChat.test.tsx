import {act, cleanup, renderHook} from '@testing-library/react';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import type {PropsWithChildren} from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {getChatHistory, getChatTurn} from '@/services/ai-new/chat';
import {AiNewApiError} from '@/services/ai-new/request';
import type {ChatTurnResult} from '@/types/ai-new/chat';
import type {Conversation} from '@/types/ai-new/conversation';
import {useChat} from './useChat';

vi.mock('@/services/ai-new/chat', () => ({
  getChatHistory: vi.fn(), getChatTurn: vi.fn(), submitChat: vi.fn(), cancelChat: vi.fn(),
}));

const scope = {tenantId: 'tenant', workspaceId: 'workspace'};
const conversation: Conversation = {
  conversationId: 'conversation',
  scope: {...scope, principal: {principalId: 'user', type: 'USER'}},
  title: 'chat',
  modelBindingRef: {definitionType: 'ai-binding', definitionId: 'chat', version: 'v1'},
  status: 'ACTIVE',
  version: 41,
  resources: [],
  createdAt: '2026-10-03T00:00:00Z',
  updatedAt: '2026-10-03T00:00:00Z',
  deletedAt: null,
};
const turn = (sequence: number, status: 'RUNNING' | 'SUCCEEDED' = 'SUCCEEDED'): ChatTurnResult => ({
  turn: {
    turnId: `turn-${sequence}`,
    conversationId: conversation.conversationId,
    sequence,
    kind: 'MESSAGE',
    status: 'ACCEPTED',
    regeneratesTurnId: null,
    input: [{role: 'USER', parts: [{text: 'question'}]}],
    idempotencyKey: {key: `key-${sequence}`},
    rejectionError: null,
    createdAt: '2026-10-03T00:00:00Z'
  },
  execution: {
    executionId: `execution-${sequence}`,
    status,
    result: status === 'SUCCEEDED' ? {output: [{text: 'answer'}]} : null,
    error: null
  },
});
let client: QueryClient;
const tick = async (ms = 20) => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
};
const mount = () => renderHook(() => useChat('user', scope, conversation), {
  wrapper: ({children}: PropsWithChildren) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
});

beforeEach(() => {
  vi.useFakeTimers();
  vi.resetAllMocks();
  localStorage.clear();
  client = new QueryClient({defaultOptions: {queries: {retry: false}}});
});
afterEach(() => {
  cleanup();
  client.clear();
  vi.useRealTimers();
});

describe('single-turn observation', () => {
  it('polls only the active turn, preserves older pages and stops after completion', async () => {
    vi.mocked(getChatHistory).mockImplementation(async (_scope, _id, before) =>
      Array.from({length: 20}, (_, i) => turn(before ? 20 - i : 40 - i, !before && i === 0 ? 'RUNNING' : 'SUCCEEDED')));
    let completed = false;
    vi.mocked(getChatTurn).mockImplementation(async () => turn(40, completed ? 'SUCCEEDED' : 'RUNNING'));
    const {result} = mount();
    await tick();
    await act(async () => {
      await result.current.history.fetchNextPage();
    });
    await tick();
    expect(result.current.turns).toHaveLength(40);
    expect(getChatHistory).toHaveBeenCalledTimes(2);
    const initialPolls = vi.mocked(getChatTurn).mock.calls.length;
    await tick(2100);
    expect(getChatTurn).toHaveBeenCalledTimes(initialPolls + 1);
    expect(getChatTurn).toHaveBeenLastCalledWith(scope, 'conversation', 'turn-40', expect.any(AbortSignal));
    expect(getChatHistory).toHaveBeenCalledTimes(2);
    completed = true;
    await tick(2100);
    expect(result.current.unfinished).toBe(false);
    expect(result.current.turns).toHaveLength(40);
    expect(result.current.turns.at(-1)?.execution?.result?.output[0].text).toBe('answer');
    const finalPolls = vi.mocked(getChatTurn).mock.calls.length;
    await tick(4100);
    expect(getChatTurn).toHaveBeenCalledTimes(finalPolls);
  });

  it('stops after an access error and allows an explicit refresh to recover', async () => {
    vi.mocked(getChatHistory).mockResolvedValue([turn(1, 'RUNNING')]);
    vi.mocked(getChatTurn).mockRejectedValue(new AiNewApiError(403, null));
    const {result} = mount();
    await tick();
    expect(result.current.history.isError).toBe(true);
    expect(getChatTurn).toHaveBeenCalledTimes(1);
    await tick(4100);
    expect(getChatTurn).toHaveBeenCalledTimes(1);
    expect(getChatHistory).toHaveBeenCalledTimes(1);
    // History may complete first and remove the observed Turn while the explicit refresh runs.
    vi.mocked(getChatHistory).mockResolvedValue([turn(1)]);
    vi.mocked(getChatTurn).mockResolvedValue(turn(1));
    await act(async () => {
      await result.current.refresh();
    });
    await tick();
    expect(result.current.history.isError).toBe(false);
    expect(result.current.unfinished).toBe(false);
    expect(vi.mocked(getChatTurn).mock.calls.every((call) => call[2] === 'turn-1')).toBe(true);
  });
});
