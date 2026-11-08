import {act, renderHook, waitFor} from '@testing-library/react';
import {beforeEach, expect, it, vi} from 'vitest';
import type {ConversationExecutionState} from '@/services/arte-ai';
import {getConversationExecutionState} from '@/services/arte-ai';
import {AiApiError} from '@/services/arte-ai/request';
import {useConversationExecutionState} from './useConversationExecutionState';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai', () => ({getConversationExecutionState: vi.fn()}));
const scope = {tenantId: 'tenant', workspaceId: 'workspace'};
const state = (id = 'one', active = true): ConversationExecutionState => ({
  conversationId: id, version: 3,
  activeExecution: active ? {kind: 'INVOCATION', executionId: `invocation-${id}`} : null,
  observedAt: '2026-11-08T00:00:00Z',
});
const response = (data: ConversationExecutionState) => ({
  httpStatus: 200,
  body: {success: true as const, code: '200', desc: 'OK', data}
});
const deferred = () => {
  let resolve!: (value: ReturnType<typeof response>) => void;
  const promise = new Promise<ReturnType<typeof response>>(yes => {
    resolve = yes;
  });
  return {promise, resolve};
};
beforeEach(() => {
  vi.mocked(getConversationExecutionState).mockReset();
  localStorage.clear();
});

it('accepts same-version release and coalesces concurrent refreshes', async () => {
  vi.mocked(getConversationExecutionState).mockResolvedValue(response(state()));
  const {result} = renderHook(() => useConversationExecutionState(scope, 'one'));
  await waitFor(() => expect(result.current.state).toEqual(state()));
  const read = deferred();
  vi.mocked(getConversationExecutionState).mockReturnValue(read.promise);
  let a!: Promise<ConversationExecutionState | undefined>;
  let b!: Promise<ConversationExecutionState | undefined>;
  act(() => {
    a = result.current.refresh();
    b = result.current.refresh();
  });
  expect(a).toBe(b);
  expect(result.current.state).toBeUndefined();
  await act(async () => {
    read.resolve(response(state('one', false)));
    await a;
  });
  expect(result.current.state).toEqual(state('one', false));
  expect(getConversationExecutionState).toHaveBeenCalledTimes(2);
});

it('rejects a late busy result after switching and returning to the same conversation', async () => {
  const first = deferred();
  const second = deferred();
  const third = deferred();
  vi.mocked(getConversationExecutionState).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise).mockReturnValueOnce(third.promise);
  const {
    result,
    rerender
  } = renderHook(({id}) => useConversationExecutionState(scope, id), {initialProps: {id: 'one'}});
  await waitFor(() => expect(getConversationExecutionState).toHaveBeenCalledTimes(1));
  const firstSignal = vi.mocked(getConversationExecutionState).mock.calls[0][1]?.signal;
  rerender({id: 'two'});
  expect(firstSignal?.aborted).toBe(true);
  rerender({id: 'one'});
  await act(async () => {
    third.resolve(response(state('one', false)));
  });
  expect(result.current.state).toEqual(state('one', false));
  await act(async () => {
    first.resolve(response(state()));
    second.resolve(response(state('two')));
  });
  expect(result.current.state).toEqual(state('one', false));
});

it('scope changes invalidate existing state and errors keep occupancy unconfirmed without automatic retries', async () => {
  vi.mocked(getConversationExecutionState).mockResolvedValueOnce(response(state())).mockRejectedValueOnce(new AiApiError(403, {desc: 'denied'}));
  const {result, rerender} = renderHook(({workspaceId}) => useConversationExecutionState({
    ...scope,
    workspaceId
  }, 'one'), {initialProps: {workspaceId: 'workspace'}});
  await waitFor(() => expect(result.current.state).toEqual(state()));
  rerender({workspaceId: 'other'});
  expect(result.current.state).toBeUndefined();
  await waitFor(() => expect(result.current.loading).toBe(false));
  expect(result.current.error).toBeInstanceOf(Error);
  expect(result.current.state).toBeUndefined();
  expect(getConversationExecutionState).toHaveBeenCalledTimes(2);
});

it('unmount cancels the active read and ignores its completion', async () => {
  const read = deferred();
  vi.mocked(getConversationExecutionState).mockReturnValue(read.promise);
  const {unmount} = renderHook(() => useConversationExecutionState(scope, 'one'));
  await waitFor(() => expect(getConversationExecutionState).toHaveBeenCalledTimes(1));
  const signal = vi.mocked(getConversationExecutionState).mock.calls[0][1]?.signal;
  unmount();
  expect(signal?.aborted).toBe(true);
  await act(async () => read.resolve(response(state())));
  expect(getConversationExecutionState).toHaveBeenCalledTimes(1);
});


it('changing credentials hides the old projection before a new account read finishes', async () => {
  vi.mocked(getConversationExecutionState).mockResolvedValueOnce(response(state()));
  const {result, rerender} = renderHook(() => useConversationExecutionState(scope, 'one'));
  await waitFor(() => expect(result.current.state).toEqual(state()));
  const read = deferred();
  vi.mocked(getConversationExecutionState).mockReturnValueOnce(read.promise);
  localStorage.setItem('user_token', 'new-account');
  rerender();
  expect(result.current.state).toBeUndefined();
  await act(async () => read.resolve(response(state('one', false))));
  expect(result.current.state).toEqual(state('one', false));
});
