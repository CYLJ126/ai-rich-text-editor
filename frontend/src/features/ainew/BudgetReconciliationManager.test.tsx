import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import messages from '@/locales/zh-CN/aiChat';
import * as api from '@/services/arte-ai';
import * as governance from '@/services/platform/budgetAccount';
import {DEFAULT_CHAT_CONFIG} from './config';
import ReconciliationPanel from './ReconciliationPanel';
import Manager from './BudgetReconciliationManager';
import {readOperation, saveOperation} from './storage';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai', async original => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  managedPendingReconciliations: vi.fn(),
  confirmManagedReconciliation: vi.fn(),
  managedReconciliationReceipt: vi.fn(),
  confirmReconciliation: vi.fn(),
  pendingReconciliations: vi.fn(),
  reconciliationReceipt: vi.fn(),
  getInvocationSnapshot: vi.fn(),
}));
vi.mock('@/services/platform/budgetAccount', () => ({
  accountResponsibility: vi.fn(),
  assignAccountReconciler: vi.fn(),
  revokeAccountReconciler: vi.fn()
}));
const scope = {tenantId: 'tenant', workspaceId: 'workspace'};
const context = {userId: 'reviewer', scope};
const accountRef: api.BudgetAccountRef = {tenantId: 'tenant', type: 'budget-account', resourceId: 'other-budget'};
const config = {...DEFAULT_CHAT_CONFIG, ...scope, budgetRef: 'other-budget', temperature: null, topP: null};
const t = (key: string) => messages[`app.aiChat.${key}` as keyof typeof messages] ?? key;
const response = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
const item: api.PendingReconciliation = {
  invocationId: 'old-invocation',
  conversationId: null,
  state: 'UNKNOWN',
  invocationVersion: 3,
  reservationId: 'reservation',
  reservationVersion: 1,
  budgetRef: 'other-budget',
  reserved: {amount: '1.000000000000000001', currency: 'CNY'},
  remoteRequestId: 'provider-id',
  acceptedAt: '2026-11-08T00:00:00Z',
  pendingSince: '2026-11-08T00:01:00Z'
};
const request: api.ConfirmReconciliationRequest & { accountRef: api.BudgetAccountRef } = {
  scope,
  accountRef,
  budgetRef: 'other-budget',
  invocationId: 'old-invocation',
  invocationVersion: 3,
  reservationId: 'reservation',
  reservationVersion: 1,
  actualCharge: '0.25',
  currency: 'CNY',
  evidenceRef: 'bill-for-this-invocation',
  note: '账单归属及远端结束已核实',
  executionEnded: true
};
const receipt: api.ReconciliationReceipt = {
  key: 'original',
  invocationId: 'old-invocation',
  reservationId: 'reservation',
  budgetRef: 'other-budget',
  charge: {amount: '0.25', currency: 'CNY'},
  evidenceRef: request.evidenceRef,
  note: request.note,
  reviewer: {subjectId: 'reviewer', displayName: null, kind: 'USER'},
  traceId: 'trace',
  state: 'FAILED',
  invocationVersion: 4,
  reservationVersion: 2,
  confirmedAt: item.acceptedAt
};
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  vi.mocked(api.managedPendingReconciliations).mockResolvedValue(response({
    page: {
      current: 1,
      size: 10,
      total: 1,
      records: [item]
    }, canReconcile: false
  }));
  vi.mocked(governance.accountResponsibility).mockResolvedValue({aclVersion: 7, grants: []});
});
afterEach(cleanup);
const mount = () => render(<ReconciliationPanel config={config} context={context} accountRef={accountRef} t={t}
                                                onConfirmed={vi.fn()}/>);

async function open() {
  fireEvent.click(screen.getByRole('button', {name: t('reconciliation.managedOpen')}));
  await screen.findByText('old-invocation');
  await screen.findByRole('button', {name: t('refreshBudget')});
}

it('reads only the target financial records without inferring permission, model content, zero cost or automatic settlement', async () => {
  mount();
  expect(api.managedPendingReconciliations).not.toHaveBeenCalled();
  await open();
  expect(api.managedPendingReconciliations).toHaveBeenCalledWith({
    scope,
    accountRef,
    current: 1,
    size: 10
  }, expect.anything());
  expect(screen.getByText(item.pendingSince!)).toBeInTheDocument();
  expect(screen.getByText(t('reconciliation.evidenceUnavailable'))).toBeInTheDocument();
  expect(screen.getByText(t('reconciliation.permission'))).toBeInTheDocument();
  expect(screen.queryByRole('button', {name: t('reconciliation.review')})).toBeNull();
  expect(api.confirmManagedReconciliation).not.toHaveBeenCalled();
  expect(api.getInvocationSnapshot).not.toHaveBeenCalled();
  expect(api.pendingReconciliations).not.toHaveBeenCalled();
});
it('requires explicit attributable evidence, amount and remote-end confirmation and retains the original managed key after uncertainty', async () => {
  vi.mocked(api.managedPendingReconciliations).mockResolvedValue(response({
    page: {
      current: 1,
      size: 10,
      total: 1,
      records: [item]
    }, canReconcile: true
  }));
  vi.mocked(api.confirmManagedReconciliation).mockRejectedValue(new TypeError('offline'));
  mount();
  await open();
  fireEvent.click(screen.getByRole('button', {name: t('reconciliation.review')}));
  expect(screen.getByRole('textbox', {name: t('reconciliation.amount')})).toHaveValue('');
  const confirm = screen.getByRole('button', {name: t('reconciliation.confirm')});
  expect(confirm).toBeDisabled();
  fireEvent.change(screen.getByRole('textbox', {name: t('reconciliation.amount')}), {target: {value: '0.25'}});
  fireEvent.change(screen.getByRole('textbox', {name: t('reconciliation.evidence')}), {target: {value: request.evidenceRef}});
  fireEvent.change(screen.getByRole('textbox', {name: t('reconciliation.note')}), {target: {value: request.note}});
  expect(confirm).toBeDisabled();
  fireEvent.click(screen.getByRole('checkbox', {name: t('reconciliation.ended')}));
  fireEvent.click(confirm);
  await waitFor(() => expect(api.confirmManagedReconciliation).toHaveBeenCalledOnce());
  const first = vi.mocked(api.confirmManagedReconciliation).mock.calls[0];
  expect(first[0]).toEqual(request);
  expect(readOperation(context, 'reconcile-managed', 'other-budget').value?.key).toBe(first[1]);
  expect(api.confirmReconciliation).not.toHaveBeenCalled();
  cleanup();
  vi.mocked(api.managedReconciliationReceipt).mockResolvedValue(response(receipt));
  mount();
  expect(api.confirmManagedReconciliation).toHaveBeenCalledOnce();
  fireEvent.click(await screen.findByRole('button', {name: '查询原核对回执'}));
  await waitFor(() => expect(api.managedReconciliationReceipt).toHaveBeenCalledWith({
    scope,
    accountRef,
    invocationId: 'old-invocation',
    key: first[1]
  }, expect.anything()));
  expect(api.getInvocationSnapshot).not.toHaveBeenCalled();
  expect(api.reconciliationReceipt).not.toHaveBeenCalled();
});
it('clears formerly readable target records when account permission is revoked', async () => {
  mount();
  await open();
  vi.mocked(api.managedPendingReconciliations).mockRejectedValue(new api.AiApiError(403, {}));
  fireEvent.click(screen.getByRole('button', {name: t('refreshBudget')}));
  await waitFor(() => expect(screen.queryByText('old-invocation')).toBeNull());
  expect(api.confirmManagedReconciliation).not.toHaveBeenCalled();
});
it('assignment requires a separate explicit account governance read and uncertain writes retain their target and key', async () => {
  const changed = vi.fn();
  vi.mocked(governance.assignAccountReconciler).mockRejectedValue(new TypeError('offline'));
  render(<Manager config={config} context={context} t={t} onUnsafeToLeave={vi.fn()} onChanged={changed}/>);
  fireEvent.click(screen.getByRole('button', {name: t('reconciliation.openAccount')}));
  expect(governance.accountResponsibility).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: t('reconciliation.inspectAssignment')}));
  const subject = await screen.findByRole('textbox', {name: t('reconciliation.responsibleSubject')});
  fireEvent.change(subject, {target: {value: 'reconciler-subject'}});
  fireEvent.click(screen.getByRole('button', {name: t('reconciliation.assign')}));
  await screen.findByText(t('reconciliation.assignmentPending'));
  const first = vi.mocked(governance.assignAccountReconciler).mock.calls[0];
  expect(first.slice(0, 4)).toEqual([scope, accountRef, 'reconciler-subject', 7]);
  expect(screen.getByRole('textbox', {name: t('reconciliation.accountRef')})).toBeDisabled();
  await waitFor(() => expect(screen.getByRole('button', {name: t('reconciliation.confirmAssignment')})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: t('reconciliation.confirmAssignment')}));
  await waitFor(() => expect(governance.assignAccountReconciler).toHaveBeenCalledTimes(2));
  expect(vi.mocked(governance.assignAccountReconciler).mock.calls[1].slice(0, 5)).toEqual(first.slice(0, 5));
  expect(changed).not.toHaveBeenCalled();
  expect(api.confirmManagedReconciliation).not.toHaveBeenCalled();
});
it('restores assignment without automatically granting or pretending a workspace role is authorized', async () => {
  saveOperation(context, 'budget-responsibility', '', {
    key: 'saved',
    request: {scope, accountRef, kind: 'assign', expectedAclVersion: 7, subjectId: 'reconciler'}
  });
  render(<Manager config={config} context={context} t={t} onUnsafeToLeave={vi.fn()} onChanged={vi.fn()}/>);
  expect(screen.getByText(t('reconciliation.assignmentPending'))).toBeInTheDocument();
  expect(governance.assignAccountReconciler).not.toHaveBeenCalled();
  expect(api.confirmManagedReconciliation).not.toHaveBeenCalled();
});
