import {expect, it} from 'vitest';
import {deepSeekDraft, fromPolicyForm, policyChanges, toPolicyForm} from './policyForm';
import {policyRules, tenantPolicyView} from '@root/tests/fixtures/policy';

it('round-trips all v1 policy fields without dropping unrelated rules', () => {
  const snapshot = {kind: 'tenant' as const, value: tenantPolicyView};
  const draft = toPolicyForm(snapshot);
  expect(fromPolicyForm(draft)).toEqual(policyRules);
  expect(draft.membersMayCreateWorkspace).toBe(true);
  const supplemented = deepSeekDraft(draft);
  expect(supplemented.allowedProviders).toEqual(['existing-provider', 'deepseek']);
  expect(supplemented.allowedDestinations).toEqual(['existing.example', 'api.deepseek.com']);
  expect(supplemented.extraRoles).toEqual(draft.extraRoles);
  expect(supplemented.exportTypes).toEqual(draft.exportTypes);
  expect(policyChanges(snapshot, supplemented).map(change => change.key)).toEqual([
    'personalCredentialsAllowed', 'allowedProtocols', 'allowedProviders', 'allowedDestinations',
  ]);
});
it('rejects duplicate extra-role rows instead of silently overwriting one group', () => {
  const draft = toPolicyForm({kind: 'tenant', value: tenantPolicyView});
  expect(() => fromPolicyForm({
    ...draft,
    extraRoles: [...draft.extraRoles, draft.extraRoles[0]]
  })).toThrow('同一资源类型');
});
