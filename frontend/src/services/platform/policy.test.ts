import {beforeEach, expect, it, vi} from 'vitest';
import {AiApiError, postResult} from '@/services/arte-ai/request';
import {executePolicyCommand, getPolicy, type PolicyCommand, policyRulesSchema} from './policy';
import {policyRules, policyScope, tenantPolicyView, workspacePolicyView} from '@root/tests/fixtures/policy';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai/request', async original => ({
  ...(await original<typeof import('@/services/arte-ai/request')>()), postResult: vi.fn(),
}));
const reply = (data: unknown) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
const command: PolicyCommand = {
  kind: 'tenant', request: {
    tenantId: policyScope.tenantId, expectedTenantVersion: 8,
    expectedPolicyVersion: 3, membersMayCreateWorkspace: true, rules: policyRules
  }
};
const receipt = () => ({
  operationId: 'policy-operation', status: 1, delivery: 0, resultVersion: 4,
  acceptedAt: 'now', completedAt: 'now', value: {...tenantPolicyView.policy, version: 4}
});
beforeEach(() => vi.resetAllMocks());

it('reads verified personal metadata and exact scope without trusting roles', async () => {
  vi.mocked(postResult).mockResolvedValueOnce(reply(tenantPolicyView)).mockResolvedValueOnce(reply(workspacePolicyView));
  expect(await getPolicy('tenant', policyScope)).toEqual({kind: 'tenant', value: tenantPolicyView});
  expect(await getPolicy('workspace', policyScope)).toEqual({kind: 'workspace', value: workspacePolicyView});
  expect(postResult).toHaveBeenNthCalledWith(1, 'getTenantPolicy', {tenantId: policyScope.tenantId}, undefined, undefined, '/arte/workspace');
  expect(postResult).toHaveBeenNthCalledWith(2, 'getWorkspacePolicy', {scope: policyScope}, undefined, undefined, '/arte/workspace');
});
it('rejects foreign tenant or space policy responses', async () => {
  vi.mocked(postResult).mockResolvedValue(reply({
    ...tenantPolicyView,
    policy: {...tenantPolicyView.policy, tenantId: 'other'}
  }));
  await expect(getPolicy('tenant', policyScope)).rejects.toMatchObject({
    code: 'INVALID_POLICY_RESPONSE',
    httpStatus: 200
  });
  vi.mocked(postResult).mockResolvedValue(reply({
    ...workspacePolicyView,
    policy: {...workspacePolicyView.policy, scope: {...policyScope, workspaceId: 'other'}}
  }));
  await expect(getPolicy('workspace', policyScope)).rejects.toBeInstanceOf(AiApiError);
});
it('accepts a tenant-only target but requires a complete target for workspace policy', async () => {
  vi.mocked(postResult).mockResolvedValue(reply(tenantPolicyView));
  await expect(getPolicy('tenant', {tenantId: policyScope.tenantId})).resolves.toEqual({
    kind: 'tenant',
    value: tenantPolicyView
  });
  expect(postResult).toHaveBeenCalledWith('getTenantPolicy', {tenantId: policyScope.tenantId}, undefined, undefined, '/arte/workspace');
  vi.mocked(postResult).mockClear();
  await expect(getPolicy('workspace', {tenantId: policyScope.tenantId})).rejects.toThrow();
  expect(postResult).not.toHaveBeenCalled();
});
it('requires complete supported rules and keeps unknown schema fields from being silently lost', async () => {
  const rules = {...policyRules, futurePolicy: true};
  vi.mocked(postResult).mockResolvedValue(reply({...tenantPolicyView, policy: {...tenantPolicyView.policy, rules}}));
  await expect(getPolicy('tenant', policyScope)).rejects.toMatchObject({code: 'INVALID_POLICY_RESPONSE'});
  vi.mocked(postResult).mockResolvedValue(reply({
    ...tenantPolicyView,
    policy: {...tenantPolicyView.policy, policySchemaVersion: 2}
  }));
  await expect(getPolicy('tenant', policyScope)).rejects.toMatchObject({code: 'INVALID_POLICY_RESPONSE'});
  expect(policyRulesSchema.safeParse({...policyRules, allowedCreationTypes: []}).success).toBe(false);
});
it('submits original versions, complete rules and the same key without read-only metadata', async () => {
  vi.mocked(postResult).mockResolvedValue(reply(receipt()));
  await executePolicyCommand(command, 'original-policy-key');
  expect(postResult).toHaveBeenCalledWith('updateTenantPolicy', command.request, undefined, 'original-policy-key', '/arte/workspace');
  expect((vi.mocked(postResult).mock.calls[0][1] as object)).not.toHaveProperty('tenantEpoch');
});
it('uses the workspace parent version independently from the tenant version', async () => {
  const workspace: PolicyCommand = {
    kind: 'workspace', request: {
      scope: policyScope, expectedWorkspaceVersion: 9,
      expectedPolicyVersion: 4, rules: policyRules
    }
  };
  vi.mocked(postResult).mockResolvedValue(reply({
    ...receipt(),
    resultVersion: 5,
    value: {...workspacePolicyView.policy, version: 5}
  }));
  await executePolicyCommand(workspace, 'workspace-key');
  expect(postResult).toHaveBeenCalledWith('updateWorkspacePolicy', workspace.request, undefined, 'workspace-key', '/arte/workspace');
});
it('rejects caller-supplied metadata and incomplete rules before transport', async () => {
  const forged = {...command, request: {...command.request, rolePresetVersion: 1}};
  await expect(executePolicyCommand(forged as PolicyCommand, 'key')).rejects.toThrow();
  const partial = {...command, request: {...command.request, rules: {personalCredentialsAllowed: true}}};
  await expect(executePolicyCommand(partial as PolicyCommand, 'key')).rejects.toThrow();
  expect(postResult).not.toHaveBeenCalled();
});
it('treats a foreign or inconsistent success receipt as uncertain', async () => {
  vi.mocked(postResult).mockResolvedValue(reply({...receipt(), value: {...receipt().value, tenantId: 'other'}}));
  await expect(executePolicyCommand(command, 'key')).rejects.toMatchObject({
    httpStatus: 200,
    code: 'INVALID_POLICY_RESPONSE'
  });
  vi.mocked(postResult).mockResolvedValue(reply({...receipt(), resultVersion: 9}));
  await expect(executePolicyCommand(command, 'key')).rejects.toMatchObject({code: 'INVALID_POLICY_RESPONSE'});
});
it('keeps permission and policy-ceiling failures distinct from empty data', async () => {
  const denied = new AiApiError(403, {code: 'PLATFORM_POLICY_DENIED'});
  vi.mocked(postResult).mockRejectedValue(denied);
  await expect(executePolicyCommand(command, 'key')).rejects.toBe(denied);
  await expect(getPolicy('tenant', policyScope)).rejects.toBe(denied);
});
