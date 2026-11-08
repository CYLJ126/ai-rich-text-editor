import {z} from 'zod';
import {AiApiError, type AiRequestOptions, currentAiCredential, postResult, requireIdempotencyKey} from './request';
import type {BudgetAccountResponse, BudgetQuery} from './types';

export async function getBudget(data: BudgetQuery, options?: AiRequestOptions) {
  const token = currentAiCredential();
  const response = await postResult<BudgetAccountResponse>('budget/getBudget', data, options);
  const handling = z.object({
    status: z.enum(['SELF_SERVICE', 'ASSIGNED', 'UNASSIGNED']),
    responsible: z.object({subjectId: z.string().min(1), displayName: z.string().nullable()}).nullable()
  });
  const parsed = z.object({
    accountRef: z.object({
      tenantId: z.string().min(1),
      type: z.literal('budget-account'),
      resourceId: z.string().min(1)
    }), reconciliationHandling: handling
  }).safeParse(response.body.data);
  if (!parsed.success || parsed.data.accountRef.tenantId !== data.scope.tenantId || parsed.data.accountRef.resourceId !== data.budgetRef
    || (parsed.data.reconciliationHandling.status === 'ASSIGNED') !== !!parsed.data.reconciliationHandling.responsible)
    throw new AiApiError(response.httpStatus, response.body, '预算账户责任信息不完整。');
  if (!options?.signal?.aborted && currentAiCredential() === token) window.dispatchEvent(new CustomEvent('arte-ai-budget-updated', {
    detail: {
      scope: data.scope,
      account: response.body.data
    }
  }));
  return response;
}

export function pendingReconciliations(data: BudgetQuery & {
  current: number;
  size: number
}, options?: AiRequestOptions) {
  return postResult<import('./types').PendingReconciliationResponse>('budget/pendingReconciliations', data, options);
}

export function confirmReconciliation(data: import('./types').ConfirmReconciliationRequest, idempotencyKey: string, options?: AiRequestOptions) {
  return postResult<import('./types').ReconciliationReceipt>('budget/confirmReconciliation', data, options, requireIdempotencyKey(idempotencyKey));
}

export function reconciliationReceipt(data: BudgetQuery & {
  invocationId: string;
  key: string
}, options?: AiRequestOptions) {
  return postResult<import('./types').ReconciliationReceipt>('budget/reconciliationReceipt', data, options);
}

export function managedPendingReconciliations(data: {
  scope: import('./types').AiScope;
  accountRef: import('./types').BudgetAccountRef;
  current: number;
  size: number
}, options?: AiRequestOptions) {
  return postResult<import('./types').PendingReconciliationResponse>('budget/managedPendingReconciliations', data, options);
}

export function confirmManagedReconciliation(data: import('./types').ConfirmReconciliationRequest & {
  accountRef: import('./types').BudgetAccountRef
}, key: string, options?: AiRequestOptions) {
  return postResult<import('./types').ReconciliationReceipt>('budget/confirmManagedReconciliation', data, options, requireIdempotencyKey(key));
}

export function managedReconciliationReceipt(data: {
  scope: import('./types').AiScope;
  accountRef: import('./types').BudgetAccountRef;
  invocationId: string;
  key: string
}, options?: AiRequestOptions) {
  return postResult<import('./types').ReconciliationReceipt>('budget/managedReconciliationReceipt', data, options);
}
