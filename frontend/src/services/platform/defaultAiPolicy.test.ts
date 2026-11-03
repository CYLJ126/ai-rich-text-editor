import {beforeEach, expect, it, vi} from 'vitest';
import {postResult} from '@/services/arte-ai/request';
import {type DefaultAiPolicyCommand, updateDefaultAiPolicy} from './ai';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai/request', async original => ({
  ...(await original<typeof import('@/services/arte-ai/request')>()), postResult: vi.fn()
}));
const command: DefaultAiPolicyCommand = {
  expectedCatalogVersion: 2, enabled: true,
  rateCaps: [{
    rate: {type: 'rate', id: 'reviewed-model', version: 'v1'},
    limit: {amount: '0.000000000000000001', currency: 'CNY'}
  }]
};
const response = (defaults = {enabled: true, rateCaps: command.rateCaps}) => ({
  httpStatus: 200,
  body: {
    success: true as const, code: '200', desc: '成功', data: {
      operationId: 'policy', status: 1, delivery: 0,
      resultVersion: 3, acceptedAt: 'now', completedAt: 'now', value: {
        version: 3,
        catalog: {reviewedPlans: [], connections: [], rates: [], defaults}
      }
    }
  }
});
beforeEach(() => vi.resetAllMocks());
it('sends only the default switch and exact decimal rate caps using the original operation key', async () => {
  vi.mocked(postResult).mockResolvedValue(response());
  await updateDefaultAiPolicy(command, 'original-key');
  expect(postResult).toHaveBeenCalledWith('updateDefaultAiPolicy', command, undefined, 'original-key', '/arte/workspace/ai');
});
it('rejects executable bindings, grants, duplicate rates and floating amounts before transmission', async () => {
  const invalid = [{...command, bindings: []}, {...command, legacyGrants: []}, {
    ...command,
    rateCaps: [...command.rateCaps, ...command.rateCaps]
  },
    {...command, rateCaps: [{...command.rateCaps[0], limit: {amount: 0.1, currency: 'CNY'}}]}];
  for (const value of invalid) await expect(updateDefaultAiPolicy(value as DefaultAiPolicyCommand, 'key')).rejects.toThrow();
  expect(postResult).not.toHaveBeenCalled();
});
it('keeps mismatched success receipts uncertain instead of accepting a different approved amount', async () => {
  vi.mocked(postResult).mockResolvedValue(response({
    enabled: true,
    rateCaps: [{...command.rateCaps[0], limit: {amount: '100', currency: 'CNY'}}]
  }));
  await expect(updateDefaultAiPolicy(command, 'key')).rejects.toMatchObject({
    httpStatus: 200,
    code: 'INVALID_DEFAULT_AI_POLICY_RESPONSE'
  });
});
it('requires an explicit idempotency key', async () => {
  await expect(updateDefaultAiPolicy(command, '')).rejects.toThrow();
  expect(postResult).not.toHaveBeenCalled();
});
