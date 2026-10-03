import {act, cleanup, renderHook} from '@testing-library/react';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import type {PropsWithChildren} from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {getChatHistory, getChatTurn, submitChat} from '@/services/ai-new/chat';
import {AiNewApiError} from '@/services/ai-new/request';
import type {ChatTurnResult} from '@/types/ai-new/chat';
import type {Conversation} from '@/types/ai-new/conversation';
import {useChat} from './useChat';
import {type ChatStreamEvent, observeChatEvents} from '@/services/ai-new/stream';

vi.mock('@/services/ai-new/chat', () => ({
  getChatHistory: vi.fn(), getChatTurn: vi.fn(), submitChat: vi.fn(), cancelChat: vi.fn(),
}));
vi.mock('@/services/ai-new/stream', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/services/ai-new/stream')>(),
  observeChatEvents: vi.fn(async () => {
  }),
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
  vi.mocked(observeChatEvents).mockResolvedValue(undefined);
  localStorage.clear();
  sessionStorage.clear();
  client = new QueryClient({defaultOptions: {queries: {retry: false}}});
});
afterEach(() => {
  cleanup();
  client.clear();
  vi.useRealTimers();
});

describe('single-turn observation', () => {
  it('adds an accepted turn immediately and keeps older history available after the first page grows', async () => {
    vi.mocked(getChatHistory).mockResolvedValue(Array.from({length: 20}, (_, i) => turn(40 - i)));
    vi.mocked(submitChat).mockResolvedValue(turn(41, 'RUNNING'));
    vi.mocked(getChatTurn).mockResolvedValue(turn(41, 'RUNNING'));
    const {result} = mount();
    await tick();
    await act(async () => {
      expect(await result.current.send('new question')).toBe(true);
    });
    await tick();
    expect(result.current.turns.at(-1)?.turn.turnId).toBe('turn-41');
    expect(result.current.turns).toHaveLength(21);
    expect(getChatHistory).toHaveBeenCalledOnce();
    expect(observeChatEvents).toHaveBeenCalledWith(scope, 'conversation', 'turn-41', 'execution-41', -1,
      expect.any(AbortSignal), expect.any(Function), expect.any(Function));
    expect(result.current.history.hasNextPage).toBe(true);
    vi.mocked(getChatHistory).mockResolvedValueOnce([turn(20)]);
    await act(async () => {
      await result.current.history.fetchNextPage();
    });
    await tick();
    expect(getChatHistory).toHaveBeenLastCalledWith(scope, 'conversation', 21, expect.any(AbortSignal));
    expect(result.current.turns).toHaveLength(22);
  });
  it('preserves newer live text while an older history page is being loaded', async () => {
    vi.mocked(getChatHistory).mockResolvedValue(Array.from({length: 20}, (_, i) => turn(40 - i, i === 0 ? 'RUNNING' : 'SUCCEEDED')));
    vi.mocked(getChatTurn).mockResolvedValue(turn(40, 'RUNNING'));
    let receive: (event: ChatStreamEvent) => void = () => {
    };
    vi.mocked(observeChatEvents).mockImplementation(async (...args) => {
      receive = args[6];
      args[7]?.(true);
      await new Promise<void>(resolve => args[5].addEventListener('abort', () => resolve(), {once: true}));
    });
    const {result} = mount();
    await tick();
    let resolvePage: (value: ChatTurnResult[]) => void = () => {
    };
    vi.mocked(getChatHistory).mockImplementationOnce(() => new Promise(resolve => {
      resolvePage = resolve;
    }));
    let loading: Promise<unknown> | undefined;
    await act(async () => {
      loading = result.current.history.fetchNextPage();
    });
    await act(async () => receive({
      executionId: 'execution-40',
      sequence: 2,
      status: 'RUNNING',
      textDelta: '你好',
      result: null,
      error: null
    }));
    await act(async () => {
      resolvePage([turn(20)]);
      await loading;
    });
    await tick();
    await act(async () => receive({
      executionId: 'execution-40',
      sequence: 3,
      status: 'RUNNING',
      textDelta: '世界',
      result: null,
      error: null
    }));
    await tick();
    expect(result.current.turns).toHaveLength(21);
    expect(result.current.turns.at(-1)?.execution?.partialText).toBe('你好世界');
  });
  it('preserves live text and terminal state when an older history refresh finishes', async () => {
    vi.mocked(getChatHistory).mockResolvedValue([turn(2, 'RUNNING'), turn(1)]);
    vi.mocked(getChatTurn).mockResolvedValue(turn(2, 'RUNNING'));
    let receive: (event: ChatStreamEvent) => void = () => {
    };
    vi.mocked(observeChatEvents).mockImplementation(async (...args) => {
      receive = args[6];
      args[7]?.(true);
      await new Promise<void>(resolve => args[5].addEventListener('abort', () => resolve(), {once: true}));
    });
    const {result} = mount();
    await tick();
    let resolveHistory: (value: ChatTurnResult[]) => void = () => {
    };
    vi.mocked(getChatHistory).mockImplementationOnce(() => new Promise(resolve => {
      resolveHistory = resolve;
    }));
    let refresh: Promise<unknown> | undefined;
    await act(async () => {
      refresh = result.current.history.refetch();
    });
    await act(async () => receive({
      executionId: 'execution-2',
      sequence: 2,
      status: 'RUNNING',
      textDelta: '你好',
      result: null,
      error: null
    }));
    await act(async () => {
      resolveHistory([turn(2, 'RUNNING'), turn(1)]);
      await refresh;
    });
    await tick();
    await act(async () => receive({
      executionId: 'execution-2',
      sequence: 3,
      status: 'RUNNING',
      textDelta: '世界',
      result: null,
      error: null
    }));
    await tick();
    expect(result.current.turns.at(-1)?.execution?.partialText).toBe('你好世界');
    await act(async () => receive({
      executionId: 'execution-2',
      sequence: 4,
      status: 'SUCCEEDED',
      textDelta: null,
      result: {output: [{text: '你好世界'}]},
      error: null
    }));
    await tick();
    await act(async () => {
      await result.current.history.refetch();
    });
    await tick();
    expect(result.current.turns.at(-1)?.execution?.status).toBe('SUCCEEDED');
    expect(result.current.turns.at(-1)?.execution?.result?.output[0].text).toBe('你好世界');
    expect(result.current.turns).toHaveLength(2);
  });
  it('merges live SSE text, rejects an older poll snapshot and aborts after terminal state', async () => {
    vi.mocked(getChatHistory).mockResolvedValue([turn(2, 'RUNNING'), turn(1)]);
    let resolvePoll: (value: ChatTurnResult) => void = () => {
    };
    vi.mocked(getChatTurn).mockImplementation(() => new Promise(resolve => {
      resolvePoll = resolve;
    }));
    let receive: (event: ChatStreamEvent) => void = () => {
    };
    let signal: AbortSignal | undefined;
    vi.mocked(observeChatEvents).mockImplementation(async (...args) => {
      signal = args[5];
      receive = args[6];
      args[7]?.(true);
      await new Promise<void>(resolve => args[5].addEventListener('abort', () => resolve(), {once: true}));
    });
    const {result} = mount();
    await tick();
    await act(async () => receive({
      executionId: 'execution-2',
      sequence: 2,
      status: 'RUNNING',
      textDelta: 'partial',
      result: null,
      error: null
    }));
    await tick();
    expect(result.current.turns.at(-1)?.execution?.partialText).toBe('partial');
    expect(getChatTurn).not.toHaveBeenCalled();
    await tick(10000);
    expect(getChatTurn).toHaveBeenCalledOnce();
    await act(async () => resolvePoll(turn(2, 'RUNNING')));
    await tick();
    expect(result.current.turns.at(-1)?.execution?.partialText).toBe('partial');
    await act(async () => receive({
      executionId: 'execution-2',
      sequence: 3,
      status: 'SUCCEEDED',
      textDelta: null,
      result: {output: [{text: 'final'}]},
      error: null
    }));
    await tick();
    expect(result.current.turns).toHaveLength(2);
    expect(result.current.turns.at(-1)?.execution?.result?.output[0].text).toBe('final');
    expect(signal?.aborted).toBe(true);
  });
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
