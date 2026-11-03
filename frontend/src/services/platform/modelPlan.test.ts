import {beforeEach, expect, it, vi} from 'vitest';
import {AiApiError, postResult} from '@/services/arte-ai/request';
import {getModelPlans, publishModelPlan} from './modelPlan';
import {managedPlan, planCommand} from '@root/tests/fixtures/modelPlan';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai/request', async original => ({
  ...(await original<typeof import('@/services/arte-ai/request')>()),
  postResult: vi.fn()
}));
const response = (data: unknown) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
const receipt = () => ({
  operationId: 'operation', status: 1, delivery: 0, resultVersion: 2, acceptedAt: 'now', completedAt: 'now',
  value: {version: 2, initialized: true, plans: [managedPlan]}
});
beforeEach(() => vi.resetAllMocks());

it('sends exact decimal strings, a fixed connection and the original key without file model references', async () => {
  vi.mocked(postResult).mockResolvedValue(response(receipt()));
  await publishModelPlan(planCommand, 'original-key');
  expect(postResult).toHaveBeenCalledWith('publishModelPlan', planCommand, undefined, 'original-key', '/arte/workspace/ai');
});
it('rejects injected secrets and floating or negative prices before transport', async () => {
  for (const extra of [{apiKey: 'secret'}, {inputPerMillion: 0.1}, {outputPerMillion: '-1'}, {inputPerMillion: '1e-8'}]) {
    await expect(publishModelPlan({
      ...planCommand,
      settings: {...planCommand.settings, ...extra}
    } as typeof planCommand, 'original-key')).rejects.toThrow();
  }
  expect(postResult).not.toHaveBeenCalled();
});
it('treats mismatching returned model, connection, price or version as uncertain', async () => {
  for (const extra of [{remoteModel: 'different'}, {inputPerMillion: '1'}, {
    connection: {
      ...managedPlan.connection,
      version: 'db-v99'
    }
  },
    {planRef: {...managedPlan.planRef, version: 'db-v99'}}, {rate: {...managedPlan.rate, version: 'db-v99'}}]) {
    const wrong = receipt();
    wrong.value.plans = [{...managedPlan, ...extra}];
    vi.mocked(postResult).mockResolvedValueOnce(response(wrong));
    await expect(publishModelPlan(planCommand, 'original-key')).rejects.toMatchObject({
      httpStatus: 200,
      code: 'INVALID_MODEL_PLAN_RESPONSE'
    });
  }
});
it('compares normalized decimal strings without floating point conversion', async () => {
  const value = receipt();
  value.value.plans[0] = {...managedPlan, inputPerMillion: '1', outputPerMillion: '2.123456789012345678'};
  vi.mocked(postResult).mockResolvedValue(response(value));
  await publishModelPlan({
    ...planCommand,
    settings: {...planCommand.settings, inputPerMillion: '01.00'}
  }, 'original-key');
});
it('rejects malformed or inconsistent directory data instead of presenting an empty list', async () => {
  vi.mocked(postResult).mockResolvedValue(response({version: 0, initialized: true, plans: []}));
  await expect(getModelPlans()).rejects.toMatchObject({code: 'INVALID_MODEL_PLAN_RESPONSE'});
});
it('returns a valid empty database catalog and preserves permission failures', async () => {
  vi.mocked(postResult).mockResolvedValueOnce(response({version: 0, initialized: false, plans: []}));
  expect((await getModelPlans()).plans).toEqual([]);
  const denied = new AiApiError(403, {});
  vi.mocked(postResult).mockRejectedValueOnce(denied);
  await expect(getModelPlans()).rejects.toBe(denied);
});
it('reads large valid historical prices without applying the new form input limit', async () => {
  vi.mocked(postResult).mockResolvedValue(response({
    version: 2, initialized: true,
    plans: [{...managedPlan, inputPerMillion: '123456789012345678901234567890'}]
  }));
  expect((await getModelPlans()).plans[0].inputPerMillion).toBe('123456789012345678901234567890');
});

it('accepts a new plan version that reuses its exact rate after an unrelated global revision', async () => {
  const original = {...managedPlan, inputPerMillion: planCommand.settings.inputPerMillion};
  vi.mocked(postResult).mockResolvedValue(response({
    operationId: 'revision-seven', status: 1, delivery: 0,
    resultVersion: 7, acceptedAt: 'now', completedAt: 'now', value: {
      version: 7, initialized: true,
      plans: [original, {
        ...original,
        planRef: {...original.planRef, version: 'v2'},
        contextWindowTokens: 16384,
        catalogRevision: 7
      }]
    }
  }));
  await publishModelPlan({
    expectedVersion: 6,
    settings: {...planCommand.settings, contextWindowTokens: 16384}
  }, 'same-rate-key');
});
it('does not accept an undeclared or missing tool capability in a successful response', async () => {
  vi.mocked(postResult).mockResolvedValue(response(receipt()));
  await expect(publishModelPlan({...planCommand, settings: {...planCommand.settings, toolCalls: true}}, 'tool-key'))
    .rejects.toMatchObject({code: 'INVALID_MODEL_PLAN_RESPONSE'});
});
