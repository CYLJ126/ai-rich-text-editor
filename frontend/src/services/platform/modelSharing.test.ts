import {beforeEach, expect, it, vi} from 'vitest';
import {AiApiError, postResult} from '@/services/arte-ai/request';
import {
  executeSharingCommand,
  getModelAccess,
  getModelSharing,
  listModelGrants,
  type SharingCommand
} from './modelSharing';
import {sharingAccess, sharingModel, sharingScope, sharingView} from '@root/tests/fixtures/modelSharing';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai/request', async original => ({
  ...await original<typeof import('@/services/arte-ai/request')>(),
  postResult: vi.fn()
}));
const reply = (data: unknown) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
const receipt = (value: unknown, version = 1) => ({
  operationId: 'operation',
  status: 1,
  delivery: 0,
  value,
  resultVersion: version,
  acceptedAt: 'now',
  completedAt: 'now'
});
const command: SharingCommand = {
  kind: 'scopes',
  request: {scope: sharingScope, resourceId: sharingModel.resourceId, expectedVersion: 0, workspaceIds: ['target']}
};
beforeEach(() => vi.resetAllMocks());
it('submits exact scope targets and original key without implicit ACL or credential approval', async () => {
  vi.mocked(postResult).mockResolvedValue(reply(receipt({
    ...sharingView.policy,
    workspaceIds: ['target'],
    version: 1
  })));
  await executeSharingCommand(command, 'original');
  expect(postResult).toHaveBeenCalledWith('updateModelUseScopes', command.request, undefined, 'original', '/arte/ai-new/model-sharing');
  expect(postResult).toHaveBeenCalledTimes(1);
});
it('rejects forged subjects, versions and secrets before transport', async () => {
  for (const field of [{actor: 'forged'}, {token: 'secret'}, {consumers: {bob: 999}}]) {
    await expect(executeSharingCommand({
      ...command,
      request: {...command.request, ...field}
    } as SharingCommand, 'original')).rejects.toThrow();
  }
  expect(postResult).not.toHaveBeenCalled();
});
it('treats wrong model, scope, applied target lists or result versions as uncertain successes', async () => {
  for (const field of [{resourceId: 'other'}, {
    scope: {
      tenantId: 'other',
      workspaceId: 'home'
    }
  }, {workspaceIds: ['home']}, {version: 9}]) {
    vi.mocked(postResult).mockResolvedValueOnce(reply(receipt({
      ...sharingView.policy,
      workspaceIds: ['target'],
      version: 1, ...field
    })));
    await expect(executeSharingCommand(command, 'original')).rejects.toMatchObject({
      code: 'INVALID_MODEL_SHARING_RESPONSE',
      httpStatus: 200
    });
  }
});
it('binds credential approvals to the exact generation and does not accept an old-generation receipt', async () => {
  vi.mocked(postResult).mockResolvedValue(reply(receipt({
    ...sharingView.policy,
    subjectIds: ['bob'],
    credentialVersion: 1,
    version: 1
  })));
  await expect(executeSharingCommand({
    kind: 'consumers',
    request: {
      scope: sharingScope,
      resourceId: sharingModel.resourceId,
      expectedVersion: 0,
      credentialVersion: 2,
      subjectIds: ['bob']
    }
  }, 'original')).rejects.toMatchObject({code: 'INVALID_MODEL_SHARING_RESPONSE'});
});
it('validates tenant model creation and refuses a forged workspace resource result', async () => {
  vi.mocked(postResult).mockResolvedValueOnce(reply(receipt({
    ...sharingModel,
    version: 0,
    ownershipType: 'WORKSPACE'
  }, 0)));
  await expect(executeSharingCommand({
    kind: 'create',
    request: {scope: sharingScope, name: sharingModel.name}
  }, 'original')).rejects.toThrow();
  vi.mocked(postResult).mockResolvedValueOnce(reply(receipt({...sharingModel, version: 0}, 0)));
  expect(await executeSharingCommand({
    kind: 'create',
    request: {scope: sharingScope, name: sharingModel.name}
  }, 'original')).toMatchObject({ownershipType: 'TENANT'});
});
it('checks safe read identities and rejects another resource in a grant list', async () => {
  vi.mocked(postResult).mockResolvedValueOnce(reply(sharingView));
  expect(await getModelSharing(sharingScope, sharingModel.resourceId)).toEqual(sharingView);
  vi.mocked(postResult).mockResolvedValueOnce(reply({
    ...sharingAccess,
    resource: {...sharingAccess.resource, tenantId: 'other'}
  }));
  await expect(getModelAccess(sharingScope, sharingModel.resourceId)).rejects.toThrow();
  vi.mocked(postResult).mockResolvedValueOnce(reply({current: 2, size: 20, total: 0, records: []}));
  await expect(listModelGrants(sharingScope, sharingModel.resourceId, 1)).rejects.toThrow();
});
it('preserves permission errors and accepts only a matching revoke acknowledgement', async () => {
  const denial = new AiApiError(403, {});
  vi.mocked(postResult).mockRejectedValueOnce(denial);
  await expect(getModelSharing(sharingScope, sharingModel.resourceId)).rejects.toBe(denial);
  vi.mocked(postResult).mockResolvedValueOnce(reply(receipt(null)));
  await executeSharingCommand({
    kind: 'revoke',
    request: {
      target: {scope: sharingScope, resourceType: 'model', resourceId: sharingModel.resourceId},
      grantId: 'grant',
      expectedAclVersion: 0,
      expectedGrantVersion: 2
    }
  }, 'original');
});
