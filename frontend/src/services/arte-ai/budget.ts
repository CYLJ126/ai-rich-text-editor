import {type AiRequestOptions, postResult, requireIdempotencyKey} from './request';
import type {BudgetAccountResponse, BudgetQuery} from './types';

export function getBudget(data: BudgetQuery, options?: AiRequestOptions) {
  return postResult<BudgetAccountResponse>('budget/getBudget', data, options);
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
