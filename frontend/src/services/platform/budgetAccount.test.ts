import {beforeEach, expect, it, vi} from 'vitest';
import {request} from '@umijs/max';
import {accountResponsibility, assignAccountReconciler, revokeAccountReconciler} from './budgetAccount';
import {
  confirmManagedReconciliation,
  getBudget,
  managedPendingReconciliations,
  managedReconciliationReceipt
} from '@/services/arte-ai';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
const scope = {tenantId: 'tenant', workspaceId: 'workspace'};
const accountRef = {tenantId: 'tenant', type: 'budget-account' as const, resourceId: 'target-budget'};
const response = (data: unknown) => ({status: 200, data: {success: true, code: '200', desc: 'OK', data}});
const grant = {
  id: 'grant', resource: accountRef, recipient: {kind: 'SUBJECT' as const, subjectId: 'reviewer'},
  action: 'budget-account:reconcile', state: 'ACTIVE' as const, version: 2
};
beforeEach(() => {
  vi.resetAllMocks();
  localStorage.clear();
});
it('uses public account governance commands with explicit target, CAS and original key', async () => {
  vi.mocked(request).mockResolvedValue(response({ok: true}));
  await assignAccountReconciler(scope, accountRef, 'reviewer', 7, 'original-key');
  expect(request).toHaveBeenLastCalledWith('/arte/resource/access/grantResourceAccess', expect.objectContaining({
    data: {
      target: {scope, resourceType: 'budget-account', resourceId: 'target-budget'}, expectedAclVersion: 7,
      recipient: {kind: 'SUBJECT', subjectId: 'reviewer'}, actions: ['budget-account:reconcile']
    }, headers: expect.objectContaining({'Idempotency-Key': 'original-key'})
  }));
  await revokeAccountReconciler(scope, accountRef, grant, 8, 'revoke-key');
  expect(request).toHaveBeenLastCalledWith('/arte/resource/access/revokeResourceGrant', expect.objectContaining({
    data: expect.objectContaining({
      grantId: 'grant',
      expectedAclVersion: 8,
      expectedGrantVersion: 2
    })
  }));
});
it('checks authorized account facts and rejects mismatched financial responsibility records', async () => {
  vi.mocked(request).mockResolvedValueOnce(response({aclVersion: 7})).mockResolvedValueOnce(response({
    current: 1,
    size: 100,
    total: 1,
    records: [grant]
  }));
  expect((await accountResponsibility(scope, accountRef)).grants).toEqual([grant]);
  vi.mocked(request).mockResolvedValueOnce(response({aclVersion: 7})).mockResolvedValueOnce(response({
    current: 1,
    size: 100,
    total: 1,
    records: [{...grant, resource: {...accountRef, resourceId: 'other-account'}}]
  }));
  await expect(accountResponsibility(scope, accountRef)).rejects.toMatchObject({httpStatus: 200});
});
it('requires the current owner budget response to carry a matching account reference and honest handling state', async () => {
  vi.mocked(request).mockResolvedValue(response({
    accountRef,
    reconciliationHandling: {status: 'ASSIGNED', responsible: null}
  }));
  await expect(getBudget({scope, budgetRef: 'target-budget'})).rejects.toMatchObject({httpStatus: 200});
  vi.mocked(request).mockResolvedValue(response({
    accountRef: {...accountRef, resourceId: 'not-this'},
    reconciliationHandling: {status: 'UNASSIGNED', responsible: null}
  }));
  await expect(getBudget({scope, budgetRef: 'target-budget'})).rejects.toMatchObject({httpStatus: 200});
});
it('keeps the managed route, account and key explicit without substituting an owner or starting a model', async () => {
  vi.mocked(request).mockResolvedValue(response({ok: true}));
  await managedPendingReconciliations({scope, accountRef, current: 1, size: 10});
  expect(request).toHaveBeenLastCalledWith('/arte/ai-new/budget/managedPendingReconciliations', expect.objectContaining({
    data: {
      scope,
      accountRef,
      current: 1,
      size: 10
    }
  }));
  const bill = {
    scope,
    accountRef,
    budgetRef: 'target-budget',
    invocationId: 'invocation',
    invocationVersion: 3,
    reservationId: 'reservation',
    reservationVersion: 1,
    actualCharge: '0.000000000000000001',
    currency: 'CNY',
    evidenceRef: 'bill',
    note: 'exact charge and remote end verified',
    executionEnded: true
  };
  await confirmManagedReconciliation(bill, 'bill-key');
  expect(request).toHaveBeenLastCalledWith('/arte/ai-new/budget/confirmManagedReconciliation', expect.objectContaining({
    data: bill,
    headers: expect.objectContaining({'Idempotency-Key': 'bill-key'})
  }));
  await managedReconciliationReceipt({scope, accountRef, invocationId: 'invocation', key: 'bill-key'});
  expect(request).toHaveBeenLastCalledWith('/arte/ai-new/budget/managedReconciliationReceipt', expect.objectContaining({
    data: {
      scope,
      accountRef,
      invocationId: 'invocation',
      key: 'bill-key'
    }
  }));
});
