import {beforeEach, expect, it, vi} from 'vitest';
import {request} from '@umijs/max';
import {getModelUsagePreparation, prepareModelUsage} from './modelUsage';
import {
  clearModelChatHandoff,
  readModelChatHandoff,
  readModelUsageOperation,
  saveModelChatHandoff,
  saveModelUsageOperation
} from '@/features/ainew/modelUsageRecovery';
import {storageKey} from '@/features/ainew/storage';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
const scope = {tenantId: 'tenant', workspaceId: 'space'};
const context = {userId: 'user', scope};
const target = {scope, resourceId: 'model', binding: {type: 'binding' as const, id: 'personal-model', version: 'v2'}};
const state = {
  scope, resource: {tenantId: 'tenant', type: 'model', resourceId: 'model'}, binding: target.binding,
  rate: {type: 'rate', id: 'price', version: 'v2'}, state: 'READY', reason: 'READY', budgetRefs: ['budget'],
  approvedLimit: {amount: '10.123456789012345678', currency: 'CNY'}, token: 'hidden'
};
const answer = (data: unknown) => ({status: 200, data: {success: true, code: '200', desc: 'OK', data}});
const receipt = (value: unknown) => ({
  operationId: 'operation',
  status: 1,
  delivery: 0,
  value,
  resultVersion: 0,
  acceptedAt: 'now',
  completedAt: 'now'
});
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
});

it('uses exact identities and the caller key, keeping server fields whitelisted and decimal strings intact', async () => {
  vi.mocked(request).mockResolvedValueOnce(answer(state)).mockResolvedValueOnce(answer(receipt(state)));
  expect((await getModelUsagePreparation(target)).approvedLimit?.amount).toBe('10.123456789012345678');
  expect(JSON.stringify(await prepareModelUsage(target, 'same-key'))).not.toContain('hidden');
  expect(request).toHaveBeenLastCalledWith('/arte/ai-new/model-resource/prepareModelUsage', expect.objectContaining({
    data: target, headers: expect.objectContaining({'Idempotency-Key': 'same-key'}),
  }));
});
it.each([
  {...state, scope: {...scope, workspaceId: 'other'}},
  {...state, resource: {...state.resource, tenantId: 'other'}},
  {...state, resource: {...state.resource, resourceId: 'other'}},
  {...state, binding: {...target.binding, version: 'v3'}},
  {...state, budgetRefs: []},
])('rejects substituted or malformed state before it can become a ready result', async value => {
  vi.mocked(request).mockResolvedValue(answer(value));
  await expect(getModelUsagePreparation(target)).rejects.toThrow();
});
it('rejects malformed successful receipts and client-supplied quota or identity without transport', async () => {
  vi.mocked(request).mockResolvedValue(answer(receipt({...state, state: 'BLOCKED'})));
  await expect(prepareModelUsage(target, 'key')).rejects.toThrow();
  vi.mocked(request).mockClear();
  await expect(prepareModelUsage({...target, amount: '1000'} as never, 'key')).rejects.toThrow();
  expect(request).not.toHaveBeenCalled();
});
it('restores only the original whitelisted request, phase and key for the same user and complete scope', () => {
  const operation = {key: 'original', request: target, phase: 'confirmed' as const};
  expect(saveModelUsageOperation(context, operation)).toBe(true);
  expect(readModelUsageOperation(context).operation).toEqual(operation);
  expect(readModelUsageOperation({...context, userId: 'other'}).operation).toBeNull();
  expect(readModelUsageOperation({...context, scope: {...scope, workspaceId: 'other'}}).operation).toBeNull();
  expect(saveModelUsageOperation(context, {...operation, token: 'secret'} as never)).toBe(false);
  sessionStorage.setItem(storageKey(context, 'model-usage'), '{bad');
  expect(readModelUsageOperation(context).blocked).toBe(true);
});
it('handoff stores only selection intent and is consumed by exact ID without clearing a newer intent', () => {
  expect(saveModelChatHandoff(context, target)).toBe(true);
  const first = readModelChatHandoff(context).handoff!;
  expect(readModelChatHandoff({...context, userId: 'other'}).handoff).toBeNull();
  expect(saveModelChatHandoff(context, target)).toBe(true);
  expect(clearModelChatHandoff(context, first.id)).toBe(false);
  expect(clearModelChatHandoff(context, readModelChatHandoff(context).handoff!.id)).toBe(true);
  expect(readModelChatHandoff(context).handoff).toBeNull();
});
