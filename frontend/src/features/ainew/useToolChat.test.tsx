import {act, cleanup, renderHook, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/arte-ai';
import * as runApi from '@/services/arte-ai/toolChat';
import {useToolChatRun} from './useToolChatRun';
import {useToolChatCommand} from './useToolChatCommand';
import {storageKey, writeValue} from './storage';
import * as storage from './storage';
import {
  toolChatContext as context,
  toolChatRequest as request,
  toolChatStatus as status,
  toolChatAnswer as answer,
  toolChatReply as reply,
} from './toolChat.testdata';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  getInvocationSnapshot: vi.fn(),
  watchInvocation: vi.fn(),
}));
vi.mock('@/services/arte-ai/toolChat', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai/toolChat')>()),
  getRunStatus: vi.fn(),
  getRunBudget: vi.fn(),
  getRunResult: vi.fn(),
  replayRunEvents: vi.fn(),
  submitToolChat: vi.fn(),
  cancelRun: vi.fn(),
}));
beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.clear();
  localStorage.clear();
  vi.mocked(runApi.getRunStatus).mockResolvedValue(reply(status));
  vi.mocked(runApi.getRunBudget).mockResolvedValue(reply([]));
  vi.mocked(runApi.replayRunEvents).mockImplementation(
    async (_scope, _id, after) =>
      reply(
        after
          ? []
          : [
            {
              runId: 'run',
              sequence: 1,
              state: 'ACCEPTED',
              checkpoint: status.checkpoint,
              occurredAt: status.acceptedAt,
            },
          ],
      ),
  );
  vi.mocked(runApi.getRunResult).mockResolvedValue(reply(answer));
  vi.mocked(api.getInvocationSnapshot).mockResolvedValue({
    invocationId: 'model-child',
    missing: true,
    status: null,
    result: null,
  });
  vi.mocked(api.watchInvocation).mockImplementation(
    async (_query, _event, options) =>
      new Promise((resolve) =>
        options.signal.addEventListener('abort', () => resolve(), {
          once: true,
        }),
      ),
  );
  vi.mocked(runApi.submitToolChat).mockResolvedValue(
    reply(
      {executionId: 'run', kind: 'RUN', acceptedAt: status.acceptedAt},
      202,
    ),
  );
  vi.mocked(runApi.cancelRun).mockResolvedValue(
    reply({...status, cancellationRequested: true}, 202),
  );
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});
it('persists before submission and repeated clicks submit once', async () => {
  const accepted = vi.fn();
  vi.mocked(runApi.submitToolChat).mockImplementation(async () => {
    expect(
      sessionStorage.getItem(
        storageKey(context, 'tool-chat-send', 'conversation'),
      ),
    ).toContain(request.budgetLimits[0].amount);
    return reply(
      {executionId: 'run', kind: 'RUN', acceptedAt: status.acceptedAt},
      202,
    );
  });
  const hook = renderHook(() =>
    useToolChatCommand(context, 'conversation', accepted),
  );
  await act(async () => {
    await Promise.all([
      hook.result.current.send(request),
      hook.result.current.send(request),
    ]);
  });
  expect(runApi.submitToolChat).toHaveBeenCalledTimes(1);
  expect(accepted).toHaveBeenCalledWith('run', request.text);
  expect(hook.result.current.saved?.acceptedId).toBe('run');
});
it('uncertain submission restores exactly the original request key and read-only intent', async () => {
  vi.mocked(runApi.submitToolChat).mockRejectedValueOnce(new Error('network'));
  const first = renderHook(() =>
    useToolChatCommand(context, 'conversation', vi.fn()),
  );
  await act(async () => {
    await first.result.current.send(request, true);
  });
  const key = vi.mocked(runApi.submitToolChat).mock.calls[0][1];
  first.unmount();
  const second = renderHook(() =>
    useToolChatCommand(context, 'conversation', vi.fn()),
  );
  await act(async () => {
    await second.result.current.send({...request, text: 'changed'}, false);
  });
  expect(vi.mocked(runApi.submitToolChat).mock.calls[1].slice(0, 3)).toEqual([
    request,
    key,
    true,
  ]);
});
it('accepted refresh never resubmits and current scope does not read another workspace record', async () => {
  writeValue(storageKey(context, 'tool-chat-send', 'conversation'), {
    request,
    key: 'fixed',
    readOnly: false,
    acceptedId: 'run',
  });
  const hook = renderHook(() =>
    useToolChatCommand(context, 'conversation', vi.fn()),
  );
  await act(async () => {
    await hook.result.current.send(request);
  });
  expect(runApi.submitToolChat).not.toHaveBeenCalled();
  expect(hook.result.current.saved?.acceptedId).toBe('run');
  const other = renderHook(() =>
    useToolChatCommand(
      {...context, scope: {...context.scope, workspaceId: 'other'}},
      'conversation',
      vi.fn(),
    ),
  );
  expect(other.result.current.saved).toBeNull();
});
it('corrupt recovery and unavailable storage prevent new submissions', async () => {
  writeValue(storageKey(context, 'tool-chat-send', 'conversation'), {
    key: 'bad',
    request: {},
  });
  const hook = renderHook(() =>
    useToolChatCommand(context, 'conversation', vi.fn()),
  );
  await act(async () => {
    await hook.result.current.send(request);
  });
  expect(hook.result.current.blocked).toBe(true);
  expect(runApi.submitToolChat).not.toHaveBeenCalled();
});
it('keeps idempotency conflicts pending and permits correcting an explicit rejection', async () => {
  vi.mocked(runApi.submitToolChat).mockRejectedValueOnce(
    new api.AiApiError(409, {code: '205027'}),
  );
  const hook = renderHook(() =>
    useToolChatCommand(context, 'conversation', vi.fn()),
  );
  await act(async () => {
    await hook.result.current.send(request);
  });
  expect(hook.result.current.saved).not.toBeNull();
  vi.mocked(runApi.submitToolChat).mockRejectedValueOnce(
    new api.AiApiError(400, {code: 'bad'}),
  );
  await act(async () => {
    await hook.result.current.send();
  });
  expect(hook.result.current.saved).toBeNull();
});
it('durable stop retry keeps the same key until authoritative observation', async () => {
  vi.mocked(runApi.cancelRun).mockRejectedValueOnce(new Error('network'));
  const first = renderHook(() =>
    useToolChatCommand(context, 'conversation', vi.fn()),
  );
  await act(async () => {
    await first.result.current.cancel('run');
  });
  const key = vi.mocked(runApi.cancelRun).mock.calls[0][2];
  first.unmount();
  const second = renderHook(() =>
    useToolChatCommand(context, 'conversation', vi.fn()),
  );
  await act(async () => {
    await second.result.current.cancel();
  });
  expect(vi.mocked(runApi.cancelRun).mock.calls[1][2]).toBe(key);
  act(() =>
    second.result.current.observed({...status, cancellationRequested: true}),
  );
  expect(second.result.current.stop).toBeNull();
});
it('reads Run final results through the Run API without sending its ID to Invocation', async () => {
  vi.mocked(runApi.getRunStatus).mockResolvedValue(
    reply({
      ...status,
      state: 'SUCCEEDED',
      checkpoint: {...status.checkpoint, phase: 'FINISHED'},
      resultAvailable: true,
    }),
  );
  const hook = renderHook(() => useToolChatRun(context, 'run', vi.fn()));
  await waitFor(() => expect(hook.result.current.answer).toEqual(answer));
  expect(api.getInvocationSnapshot).not.toHaveBeenCalled();
  expect(api.watchInvocation).not.toHaveBeenCalled();
});
it('streams only the current model child and merges same-version durable stop state', async () => {
  vi.mocked(runApi.getRunStatus).mockResolvedValue(
    reply({
      ...status,
      state: 'RUNNING',
      checkpoint: {
        roundIndex: 0,
        phase: 'WAITING_MODEL',
        children: ['model-child'],
      },
    }),
  );
  const hook = renderHook(() => useToolChatRun(context, 'run', vi.fn()));
  await waitFor(() => expect(api.watchInvocation).toHaveBeenCalledTimes(1));
  const [query, , options] = vi.mocked(api.watchInvocation).mock.calls[0];
  expect(query.invocationId).toBe('model-child');
  act(() =>
    options.onText?.({
      executionId: 'model-child',
      attemptId: 'attempt',
      offset: 0,
      text: '首段文字',
    }),
  );
  expect(hook.result.current.text).toBe('首段文字');
  vi.mocked(runApi.getRunStatus).mockResolvedValue(
    reply({...status, cancellationRequested: true}),
  );
  act(() => hook.result.current.refresh());
  await waitFor(() =>
    expect(hook.result.current.status?.cancellationRequested).toBe(true),
  );
});
it('independent event gaps pause without silently advancing the durable cursor', async () => {
  vi.mocked(runApi.replayRunEvents).mockResolvedValue(
    reply([
      {
        runId: 'run',
        sequence: 3,
        state: 'ACCEPTED',
        checkpoint: status.checkpoint,
        occurredAt: status.acceptedAt,
      },
    ]),
  );
  const hook = renderHook(() => useToolChatRun(context, 'run', vi.fn()));
  await waitFor(() => expect(hook.result.current.error).toBeTruthy());
  expect(hook.result.current.events).toEqual([]);
  act(() => hook.result.current.refresh());
  await waitFor(() => expect(runApi.replayRunEvents).toHaveBeenCalledTimes(2));
  expect(vi.mocked(runApi.replayRunEvents).mock.calls[1][2]).toBe(0);
});
it('permission revocation clears cached answer, stops observation and does not retry execution', async () => {
  vi.mocked(runApi.getRunStatus).mockResolvedValueOnce(
    reply({
      ...status,
      state: 'SUCCEEDED',
      checkpoint: {...status.checkpoint, phase: 'FINISHED'},
      resultAvailable: true,
    }),
  );
  const hook = renderHook(() => useToolChatRun(context, 'run', vi.fn()));
  await waitFor(() => expect(hook.result.current.answer).toEqual(answer));
  vi.mocked(runApi.getRunStatus).mockRejectedValue(
    new api.AiApiError(403, {code: 'denied'}),
  );
  act(() => hook.result.current.refresh());
  await waitFor(() => expect(hook.result.current.paused).toBe(true));
  expect(hook.result.current.answer).toBeNull();
  expect(hook.result.current.status).toBeNull();
  expect(runApi.submitToolChat).not.toHaveBeenCalled();
});
it('unmount aborts reads and SSE but never sends a business cancel', async () => {
  vi.mocked(runApi.getRunStatus).mockResolvedValue(
    reply({
      ...status,
      state: 'RUNNING',
      checkpoint: {
        roundIndex: 0,
        phase: 'WAITING_MODEL',
        children: ['model-child'],
      },
    }),
  );
  const hook = renderHook(() => useToolChatRun(context, 'run', vi.fn()));
  await waitFor(() => expect(api.watchInvocation).toHaveBeenCalled());
  const options = vi.mocked(api.watchInvocation).mock.calls[0][2];
  hook.unmount();
  expect(options.signal.aborted).toBe(true);
  expect(runApi.cancelRun).not.toHaveBeenCalled();
});

it('storage recheck clears an already observed terminal receipt without requiring another execution', async () => {
  const hook = renderHook(() => useToolChatCommand(context, 'conversation', vi.fn()));
  await act(async () => {
    await hook.result.current.send(request);
  });
  const target = storageKey(context, 'tool-chat-send', 'conversation');
  const original = storage.removeValue;
  const remove = vi.spyOn(storage, 'removeValue').mockImplementation((key, persistent) => key === target ? false : original(key, persistent));
  act(() => hook.result.current.observed({...status, state: 'SUCCEEDED'}));
  expect(hook.result.current.storageError).toBe(true);
  expect(hook.result.current.saved?.acceptedId).toBe('run');
  remove.mockRestore();
  act(() => hook.result.current.recheckStorage());
  expect(hook.result.current.storageError).toBe(false);
  expect(hook.result.current.saved).toBeNull();
  expect(runApi.submitToolChat).toHaveBeenCalledTimes(1);
});
