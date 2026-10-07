import {type AiRequestOptions, postResult} from './request';
import type {BudgetAccountResponse, BudgetQuery} from './types';

export function getBudget(data: BudgetQuery, options?: AiRequestOptions) {
  return postResult<BudgetAccountResponse>('budget/getBudget', data, options);
}
