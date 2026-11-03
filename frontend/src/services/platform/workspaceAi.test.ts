import {beforeEach, expect, it, vi} from 'vitest';
import {postResult, requireIdempotencyKey} from '@/services/arte-ai/request';
import {getWorkspaceAi, prepareWorkspaceAi, updateWorkspaceAi, updateWorkspaceBudgetRules} from './ai';

vi.mock('@/services/arte-ai/request', async original => ({
  ...(await original<typeof import('@/services/arte-ai/request')>()),
  postResult: vi.fn(),
  requireIdempotencyKey: vi.fn()
}));
const scope = {tenantId: 'tenant', workspaceId: 'space'};
const model = {resourceId: 'resource', binding: {type: 'binding', id: 'binding', version: 'v1'}};
const rules = [{
  rate: {type: 'rate', id: 'price', version: 'v1'},
  limit: {amount: '5.000000000000000001', currency: 'CNY'}
}];
const answer = (value: unknown, version: number) => ({
  httpStatus: 200, body: {
    data: {
      operationId: 'operation', status: 1, delivery: 0,
      resultVersion: version, acceptedAt: 'now', completedAt: 'now', value
    }
  }
});
beforeEach(() => vi.resetAllMocks());

it('ordinary reads call only getConfiguration with no operation key', async () => {
  await getWorkspaceAi(scope);
  expect(postResult).toHaveBeenCalledWith('getConfiguration', {scope}, undefined, undefined, '/arte/workspace/ai');
  expect(requireIdempotencyKey).not.toHaveBeenCalled();
});
it('rejects first preparation receipts that silently select a model', async () => {
  vi.mocked(postResult).mockResolvedValue(answer({
    configuration: {
      scope,
      version: 0,
      templateVersion: 2,
      models: [model]
    }, workspaceVersion: 4
  }, 0) as never);
  await expect(prepareWorkspaceAi({
    scope,
    expectedWorkspaceVersion: 3,
    expectedTemplateVersion: 2
  }, 'original-key')).rejects.toThrow('回执无效');
});
it('validates the original exact resource, version and target in a model save receipt', async () => {
  const request = {scope, expectedWorkspaceVersion: 3, expectedVersion: 1, enabled: true, models: [model]};
  vi.mocked(postResult).mockResolvedValue(answer({
    configuration: {
      scope,
      version: 2,
      enabled: true,
      models: [{...model, binding: {...model.binding, version: 'v2'}}]
    }, workspaceVersion: 4
  }, 2) as never);
  await expect(updateWorkspaceAi(request, 'original-key')).rejects.toThrow('回执无效');
  expect(postResult).toHaveBeenCalledWith('updateConfiguration', request, undefined, 'original-key', '/arte/workspace/ai');
});
it('preserves decimal rule amounts and rejects a mismatched rule receipt', async () => {
  const request = {scope, expectedWorkspaceVersion: 3, expectedRuleVersion: 1, rules};
  const value = {scope, configuration: {scope, version: 2, rules}, workspaceVersion: 4};
  vi.mocked(postResult).mockResolvedValue(answer(value, 2) as never);
  await updateWorkspaceBudgetRules(request, 'original-key');
  expect(postResult).toHaveBeenCalledWith('updateBudgetRules', request, undefined, 'original-key', '/arte/workspace/ai');
  vi.mocked(postResult).mockResolvedValue(answer({
    ...value,
    configuration: {...value.configuration, rules: []}
  }, 2) as never);
  await expect(updateWorkspaceBudgetRules(request, 'original-key')).rejects.toThrow('回执无效');
});
