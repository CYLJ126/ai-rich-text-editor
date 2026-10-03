import {beforeEach, describe, expect, it, vi} from 'vitest';
import {request} from '@umijs/max';
import {getChatHistory, submitChat} from './chat';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
const scope = {tenantId: 'tenant', workspaceId: 'workspace', allowedActions: ['resource.egress']};
const command = {key: 'stable-key', body: {expectedVersion: 7, text: '问题', externalTransferConfirmed: true as const}};
const result = {
  turn: {
    turnId: 'turn',
    conversationId: 'c/1',
    sequence: 2,
    kind: 'MESSAGE',
    status: 'ACCEPTED',
    input: [{role: 'USER', parts: [{text: '问题'}]}],
    idempotencyKey: {key: 'stable-key'},
    rejectionError: null,
    createdAt: '2026-10-03T00:00:00Z'
  },
  execution: {
    executionId: 'execution',
    status: 'SUCCEEDED',
    result: {output: [{text: '<script>literal text</script>'}]},
    error: null
  },
};
beforeEach(() => {
  vi.mocked(request).mockReset();
});
describe('chat turn HTTP contracts', () => {
  it('sends only permitted fields and preserves the idempotency header and version', async () => {
    vi.mocked(request).mockResolvedValue(result);
    await submitChat(scope, 'c/1', command);
    expect(request).toHaveBeenCalledWith('/arte/api/ai-new/conversations/c%2F1/turns', expect.objectContaining({
      method: 'POST', headers: {'Content-Type': 'application/json', 'Idempotency-Key': 'stable-key'},
      data: {tenantId: 'tenant', workspaceId: 'workspace', ...command.body}, skipErrorHandler: true,
    }));
  });
  it('reads nullable execution and forwards cursor and abort signal without losing text', async () => {
    vi.mocked(request).mockResolvedValue([{...result, turn: {...result.turn, status: 'READY'}, execution: null}]);
    const controller = new AbortController();
    expect((await getChatHistory(scope, 'c/1', 3, controller.signal))[0].execution).toBeNull();
    expect(request).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({
      params: {tenantId: 'tenant', workspaceId: 'workspace', beforeSequence: 3, limit: 20}, signal: controller.signal,
    }));
  });
  it('rejects malformed success, unsupported content, wrong conversation and mismatched keys', async () => {
    for (const invalid of [
      {...result, execution: null},
      {...result, execution: {...result.execution, result: null}},
      {...result, execution: {...result.execution, result: {output: [{artifactRef: 'not-text'}]}}},
      {...result, turn: {...result.turn, conversationId: 'other'}},
      {...result, turn: {...result.turn, idempotencyKey: {key: 'different'}}},
    ]) {
      vi.mocked(request).mockResolvedValue(invalid);
      await expect(submitChat(scope, 'c/1', command)).rejects.toMatchObject({status: 502});
    }
  });
});
