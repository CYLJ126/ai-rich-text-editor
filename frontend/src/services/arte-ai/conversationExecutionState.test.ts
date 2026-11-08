import {request} from '@umijs/max';
import {beforeEach, expect, it, vi} from 'vitest';
import {getConversationExecutionState, getInvocationSnapshots, getInvocationStatus} from './index';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
const query = {scope: {tenantId: 'tenant', workspaceId: 'workspace'}, conversationId: 'conversation'};
const state = {conversationId: 'conversation', version: 3, activeExecution: null, observedAt: '2026-11-08T00:00:00Z'};
const response = (data: unknown) => ({status: 200, data: {success: true, code: '200', desc: 'OK', data}});
beforeEach(() => {
  vi.mocked(request).mockReset();
  localStorage.clear();
});

it('reads a typed invocation or run, including same-version occupancy changes without caching', async () => {
  for (const activeExecution of [{kind: 'INVOCATION', executionId: 'invocation'}, {
    kind: 'RUN',
    executionId: 'run'
  }, null]) {
    vi.mocked(request).mockResolvedValue(response({...state, activeExecution}));
    expect((await getConversationExecutionState(query)).body.data).toEqual({...state, activeExecution});
  }
  expect(request).toHaveBeenCalledTimes(3);
  expect(request).toHaveBeenLastCalledWith('/arte/ai-new/conversation/getConversationExecutionState', expect.objectContaining({
    method: 'POST', data: query, headers: {'Content-Type': 'application/json'},
  }));
});

it.each([
  {...state, activeExecution: undefined}, {...state, version: -1}, {...state, version: 0.5},
  {...state, activeExecution: {kind: 'TOOL_CHAT_RUN', executionId: 'run'}},
  {...state, activeExecution: {kind: 'INVOCATION'}}, {...state, conversationId: 'other'},
  {...state, observedAt: 'invalid'},
])('rejects incomplete or mismatched occupancy instead of assuming idle: %j', async value => {
  vi.mocked(request).mockResolvedValue(response(value));
  await expect(getConversationExecutionState(query)).rejects.toMatchObject({name: 'AiApiError'});
});

it('shares in-flight reads and forwards cancellation while preserving HTTP errors', async () => {
  let finish!: (value: ReturnType<typeof response>) => void;
  vi.mocked(request).mockReturnValue(new Promise(resolve => {
    finish = resolve;
  }));
  const controller = new AbortController();
  const cancelled = getConversationExecutionState(query, {signal: controller.signal});
  const other = getConversationExecutionState(query);
  const rejected = expect(cancelled).rejects.toMatchObject({name: 'AbortError'});
  await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(1));
  controller.abort();
  await rejected;
  finish(response(state));
  expect((await other).body.data).toEqual(state);
  vi.mocked(request).mockResolvedValue({status: 403, data: {success: false, code: 'denied', desc: 'Denied'}});
  await expect(getConversationExecutionState(query)).rejects.toMatchObject({httpStatus: 403});
});

it('requires the server blocking fact on both single and batch invocation projections', async () => {
  const invocation = {invocationId: 'invocation', state: 'UNKNOWN'};
  vi.mocked(request).mockResolvedValue(response(invocation));
  await expect(getInvocationStatus({
    scope: query.scope,
    invocationId: 'invocation'
  })).rejects.toMatchObject({name: 'AiApiError'});
  vi.mocked(request).mockResolvedValue(response([{
    invocationId: 'invocation',
    status: invocation,
    missing: false,
    result: null
  }]));
  await expect(getInvocationSnapshots({
    scope: query.scope,
    invocationIds: ['invocation']
  })).rejects.toMatchObject({name: 'AiApiError'});
  vi.mocked(request).mockResolvedValue(response({...invocation, blocksConversation: false}));
  expect((await getInvocationStatus({
    scope: query.scope,
    invocationId: 'invocation'
  })).body.data.blocksConversation).toBe(false);
});
