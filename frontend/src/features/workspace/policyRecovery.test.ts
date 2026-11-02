import {beforeEach, expect, it, vi} from 'vitest';
import {type PolicyOperation, readPolicyOperation, savePolicyOperation} from './policyRecovery';
import {policyRules, policyScope} from '@root/tests/fixtures/policy';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
const tenant: PolicyOperation = {
  schemaVersion: 1, key: 'original', command: {
    kind: 'tenant', request: {
      tenantId: policyScope.tenantId, expectedTenantVersion: 8, expectedPolicyVersion: 3,
      membersMayCreateWorkspace: true, rules: policyRules,
    }
  }
};
beforeEach(() => sessionStorage.clear());

it('recovers the same tenant operation across its spaces but isolates accounts and tenants', () => {
  expect(savePolicyOperation('alice', policyScope, 'tenant', tenant)).toBe(true);
  expect(readPolicyOperation('alice', {
    ...policyScope,
    workspaceId: 'another-space'
  }, 'tenant').operation).toEqual(tenant);
  expect(readPolicyOperation('alice', {tenantId: policyScope.tenantId}, 'tenant').operation).toEqual(tenant);
  expect(readPolicyOperation('bob', policyScope, 'tenant').operation).toBeNull();
  expect(readPolicyOperation('alice', {...policyScope, tenantId: 'another-tenant'}, 'tenant').operation).toBeNull();
});
it('keeps workspace operations bound to their full scope and rejects cross-target data', () => {
  const workspace: PolicyOperation = {
    schemaVersion: 1, key: 'original-space', command: {
      kind: 'workspace', request: {
        scope: policyScope, expectedWorkspaceVersion: 9, expectedPolicyVersion: 4, rules: policyRules,
      }
    }
  };
  expect(savePolicyOperation('alice', policyScope, 'workspace', workspace)).toBe(true);
  expect(readPolicyOperation('alice', {...policyScope, workspaceId: 'other'}, 'workspace').operation).toBeNull();
  expect(savePolicyOperation('alice', {...policyScope, workspaceId: 'other'}, 'workspace', workspace)).toBe(false);
});
it('blocks malformed recovery records instead of discarding them', () => {
  savePolicyOperation('alice', policyScope, 'tenant', tenant);
  const key = sessionStorage.key(0)!;
  sessionStorage.setItem(key, '{broken');
  expect(readPolicyOperation('alice', policyScope, 'tenant')).toEqual({operation: null, blocked: true});
  expect(sessionStorage.getItem(key)).toBe('{broken');
});
it('reports storage failure before a caller can submit a new command', () => {
  const write = vi.spyOn(sessionStorage, 'setItem').mockImplementation(() => {
    throw new Error('unavailable');
  });
  try {
    expect(savePolicyOperation('alice', policyScope, 'tenant', tenant)).toBe(false);
  } finally {
    write.mockRestore();
  }
});
