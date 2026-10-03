import {beforeEach, describe, expect, it, vi} from 'vitest';
import {request} from '@umijs/max';
import {cancelChat, getChatHistory, submitChat} from './chat';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
const scope = {tenantId: 'tenant', workspaceId: 'workspace', allowedActions: ['resource.egress']};
const command = {key: 'stable-key', body: {expectedVersion: 7, text: '问题', externalTransferConfirmed: true as const}};
const result = {
  turn: {
    turnId: 'turn',
    conversationId: 'c/1',
    sequence: 2,
    kind: 'MESSAGE', regeneratesTurnId: null,
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
      timeout: 30_000,
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
  it('regenerates via its own endpoint without sending preview text, retaining the original reference and version', async () => {
    const regeneration = {
      key: 'regen-key',
      kind: 'REGENERATION' as const,
      body: {...command.body, originalTurnId: 'original/1'}
    };
    vi.mocked(request).mockResolvedValue({
      ...result,
      turn: {...result.turn, kind: 'REGENERATION', regeneratesTurnId: 'original/1', idempotencyKey: {key: 'regen-key'}}
    });
    await submitChat(scope, 'c/1', regeneration);
    expect(request).toHaveBeenLastCalledWith('/arte/api/ai-new/conversations/c%2F1/regenerate', expect.objectContaining({
      method: 'POST', headers: {'Content-Type': 'application/json', 'Idempotency-Key': 'regen-key'},
      data: {
        tenantId: 'tenant',
        workspaceId: 'workspace',
        expectedVersion: 7,
        originalTurnId: 'original/1',
        externalTransferConfirmed: true
      },
    }));
    vi.mocked(request).mockResolvedValue({
      ...result,
      turn: {...result.turn, kind: 'REGENERATION', regeneratesTurnId: 'other', idempotencyKey: {key: 'regen-key'}}
    });
    await expect(submitChat(scope, 'c/1', regeneration)).rejects.toMatchObject({status: 502});
  });
  it('keeps cancellation receipts distinct from model outcomes and rejects unknown receipt values', async () => {
    for (const status of ['REQUEST_ACCEPTED', 'CANCELLING', 'CANCELLED', 'UNCONFIRMED', 'ALREADY_COMPLETED']) {
      vi.mocked(request).mockResolvedValue({status});
      expect(await cancelChat(scope, 'c/1', 'turn/1')).toBe(status);
    }
    expect(request).toHaveBeenLastCalledWith('/arte/api/ai-new/conversations/c%2F1/turns/turn%2F1/cancel', expect.objectContaining({
      method: 'POST',
      params: {tenantId: 'tenant', workspaceId: 'workspace'}
    }));
    vi.mocked(request).mockResolvedValue({status: 'SUCCEEDED'});
    await expect(cancelChat(scope, 'c/1', 'turn/1')).rejects.toMatchObject({status: 502});
  });
});
