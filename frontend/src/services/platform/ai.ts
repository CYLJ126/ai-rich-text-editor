import {type DefaultAiPolicyCommand, defaultAiPolicySchema} from './defaultAiPolicyContract';
import {AiApiError, type AiRequestOptions, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';
import type {CommandReceipt, WorkspaceScope} from './workspaceTypes';

export {defaultAiPolicySchema, type DefaultAiPolicyCommand} from './defaultAiPolicyContract';

export interface DefinitionRef {
  type: string;
  id: string;
  version: string
}

export interface Money {
  amount: string | number;
  currency: string
}

export interface BudgetPreset {
  rate: DefinitionRef;
  limit: Money
}

export interface AiPreset {
  enabled: boolean;
  bindings: DefinitionRef[];
  budgets: BudgetPreset[]
}

export interface AiModel {
  binding: DefinitionRef;
  name: string;
  connection: DefinitionRef;
  rate: DefinitionRef
}

export interface WorkspaceAiView {
  mode: 'DATABASE';
  configuration: { scope: WorkspaceScope; preset: AiPreset; templateVersion: number; version: number } | null;
  workspaceVersion: number;
  canManage: boolean;
  needsImport: boolean;
  models: AiModel[];
  toolRates?: { definition: DefinitionRef; toolPerCall: Money }[];
  defaults: AiPreset | null;
}

export interface AiCatalog {
  reviewedPlans: import('./modelPlan').ManagedModelPlan[];
  connections: import('./connection').ConnectionView['connections'];
  rates: {
    definition: DefinitionRef;
    inputPerMillion: Money | null;
    outputPerMillion: Money | null;
    toolPerCall?: Money | null
  }[];
  defaults: { enabled: boolean; rateCaps: BudgetPreset[] };
}

export interface CatalogView {
  version: number;
  catalog: AiCatalog | null
}

const base = '/arte/workspace/ai';
export const ensureWorkspaceAi = (scope: WorkspaceScope, options?: AiRequestOptions) => postResult<WorkspaceAiView>('ensure', {scope}, options, undefined, base);
export const getWorkspaceAi = (scope: WorkspaceScope, options?: AiRequestOptions) => postResult<WorkspaceAiView>('getConfiguration', {scope}, options, undefined, base);
export const updateWorkspaceAi = (request: {
  scope: WorkspaceScope;
  expectedWorkspaceVersion: number;
  expectedVersion: number;
  preset: AiPreset
}, key: string, options?: AiRequestOptions) => {
  requireIdempotencyKey(key);
  return postResult<CommandReceipt<WorkspaceAiView>>('updateConfiguration', request, options, key, base);
};
export const getAiCatalog = (options?: AiRequestOptions) => postResult<CatalogView>('getCatalog', {}, options, undefined, base);
export const initializeAiCatalog = (key: string) => {
  requireIdempotencyKey(key);
  return postResult<CommandReceipt<CatalogView>>('initializeCatalog', {}, undefined, key, base);
};

/** 只发送默认开关和精确费率上限，不能注入执行绑定或主体授权。 */
export async function updateDefaultAiPolicy(command: DefaultAiPolicyCommand, key: string) {
  requireIdempotencyKey(key);
  const request = defaultAiPolicySchema.parse(command);
  const response = await postResult<CommandReceipt<CatalogView>>('updateDefaultAiPolicy', request, undefined, key, base);
  const receipt = response.body.data;
  const actual = receipt?.value?.catalog?.defaults;
  const normalize = (items: BudgetPreset[]) => JSON.stringify(items.map(item => [refKey(item.rate),
    String(item.limit.amount).replace(/^(0+)(?=\d)/, '').replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, ''), item.limit.currency]).sort());
  if (!receipt || receipt.status !== 1 || receipt.resultVersion !== request.expectedCatalogVersion + 1
    || receipt.value.version !== receipt.resultVersion || !actual || actual.enabled !== request.enabled
    || normalize(actual.rateCaps) !== normalize(request.rateCaps)) {
    throw new AiApiError(response.httpStatus, {code: 'INVALID_DEFAULT_AI_POLICY_RESPONSE'}, '默认模板回执无效，请确认原操作');
  }
  return response;
}
export const refKey = (ref: DefinitionRef) => JSON.stringify([ref.type, ref.id, ref.version]);
