import {type AiRequestOptions, currentAiCredential, postResult, requireIdempotencyKey} from './request';
import type {BudgetAccountResponse, BudgetQuery} from './types';

export async function getBudget(data: BudgetQuery, options?: AiRequestOptions) {
  const token = currentAiCredential();
  const response = await postResult<BudgetAccountResponse>('budget/getBudget', data, options);
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
