import {request} from '@umijs/max';
import {beforeEach, expect, it, vi} from 'vitest';
import {getBudget, getConversation, getInvocationSnapshot, turnsForChat} from './index';
import type {SubmitChatRequest} from './types';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
const requestMock = vi.mocked(request as unknown as (url: string, options: {
  signal?: AbortSignal;
  data: any
}) => Promise<unknown>);
const scope = {tenantId: 'tenant', workspaceId: 'workspace'};
const response = (data: unknown) => ({status: 200, data: {success: true, code: '200', desc: 'OK', data}});
const deferred = () => {
  let resolve!: (value: ReturnType<typeof response>) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<ReturnType<typeof response>>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return {promise, resolve, reject};
};
const tick = async () => {
  for (let n = 0; n < 8; n++) await Promise.resolve();
};
beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});

it('shares equal reads; one cancellation preserves the other; fulfilled data is not cached', async () => {
  const pending = deferred();
  requestMock.mockReturnValue(pending.promise as never);
  const first = new AbortController();
  const a = getBudget({scope, budgetRef: 'budget'}, {signal: first.signal});
  const b = getBudget({budgetRef: 'budget', scope: {...scope}});
  const cancelled = expect(a).rejects.toMatchObject({name: 'AbortError'});
  await tick();
  expect(request).toHaveBeenCalledOnce();
  const transport = requestMock.mock.calls[0][1]!.signal as AbortSignal;
  first.abort();
  await cancelled;
  expect(transport.aborted).toBe(false);
  pending.resolve(response({budgetRef: 'budget', version: 2}));
  expect((await b).body.data.version).toBe(2);
  await getBudget({scope, budgetRef: 'budget'});
  expect(request).toHaveBeenCalledTimes(2);
});

it('cancels transport when the last consumer leaves and removes failed entries for retry', async () => {
  const pending = deferred();
  requestMock.mockReturnValue(pending.promise as never);
  const controller = new AbortController();
  const a = getConversation({scope, conversationId: 'a'}, {signal: controller.signal});
  const failed = expect(a).rejects.toMatchObject({name: 'AbortError'});
  await tick();
  const transport = requestMock.mock.calls[0][1]!.signal as AbortSignal;
  controller.abort();
  await failed;
  expect(transport.aborted).toBe(true);
  pending.reject(new Error('cancelled'));
  requestMock.mockResolvedValue(response({conversationId: 'a'}) as never);
  await getConversation({scope, conversationId: 'a'});
  expect(request).toHaveBeenCalledTimes(2);
});

it('isolates credentials and workspaces, and never merges mutations', async () => {
  const pending = deferred();
  requestMock.mockReturnValue(pending.promise as never);
  const a = getConversation({scope, conversationId: 'a'});
  const stale = expect(a).rejects.toMatchObject({name: 'AbortError'});
  await tick();
  localStorage.setItem('user_token', 'different-account');
  const b = getConversation({scope, conversationId: 'a'});
  const c = getConversation({scope: {...scope, workspaceId: 'other'}, conversationId: 'a'});
  const d = turnsForChat({scope} as SubmitChatRequest, 'key');
  const e = turnsForChat({scope} as SubmitChatRequest, 'key');
  await tick();
  expect(request).toHaveBeenCalledTimes(5);
  pending.resolve(response({}));
  await Promise.all([stale, b, c, d, e]);
});

it('batches distinct invocation resources with a 20 item limit and shares identical reads', async () => {
  requestMock.mockImplementation(async (_url, options: any) => response(options.data.invocationIds.map((id: string) => ({
    invocationId: id, status: null, result: null, missing: true,
  }))) as never);
  const reads = Array.from({length: 23}, (_, n) => getInvocationSnapshot({scope, invocationId: `i-${n}`}));
  reads.push(getInvocationSnapshot({scope, invocationId: 'i-0'}));
  expect(await Promise.all(reads)).toHaveLength(24);
  expect(request).toHaveBeenCalledTimes(2);
  expect(requestMock.mock.calls.map(([, options]: any) => options.data.invocationIds.length)).toEqual([20, 3]);
  expect(requestMock.mock.calls[0][0]).toBe('/arte/ai-new/invocation/getInvocationSnapshots');
});

it('a cancelled item cannot cancel the other invocation items in its batch', async () => {
  const pending = deferred();
  requestMock.mockReturnValue(pending.promise as never);
  const controller = new AbortController();
  const a = getInvocationSnapshot({scope, invocationId: 'a'}, {signal: controller.signal});
  const failed = expect(a).rejects.toMatchObject({name: 'AbortError'});
  const b = getInvocationSnapshot({scope, invocationId: 'b'});
  await tick();
  const transport = requestMock.mock.calls[0][1]!.signal as AbortSignal;
  controller.abort();
  await failed;
  expect(transport.aborted).toBe(false);
  pending.resolve(response(['a', 'b'].map(id => ({invocationId: id, status: null, result: null, missing: true}))));
  expect((await b).invocationId).toBe('b');
});

it('returns result bytes if any consumer of a shared invocation lacks that result version', async () => {
  requestMock.mockImplementation(async (_url, options) => response(options.data.invocationIds.map((id: string) => ({
    invocationId: id, status: null, result: null, missing: true,
  }))));
  await Promise.all([
    getInvocationSnapshot({scope, invocationId: 'same', knownResultVersion: 3}),
    getInvocationSnapshot({scope, invocationId: 'same'}),
  ]);
  expect(requestMock).toHaveBeenCalledOnce();
  expect(requestMock.mock.calls[0][1].data.invocationIds).toEqual(['same']);
  expect(requestMock.mock.calls[0][1].data.knownResultVersions).toEqual({});
});
