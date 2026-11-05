import {z} from 'zod';
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

export interface ModelSelection {
  resourceId: string;
  binding: DefinitionRef
}

export interface WorkspaceAiConfiguration {
  scope: WorkspaceScope;
  enabled: boolean;
  models: ModelSelection[];
  templateVersion: number;
  version: number
}

export interface AiModel {
  resourceId: string;
  kind: 'PERSONAL' | 'TENANT';
  enabled: boolean;
  binding: DefinitionRef;
  name: string;
  connection: DefinitionRef;
  rate: DefinitionRef
}

export interface WorkspaceAiView {
  mode: 'DATABASE';
  configuration: WorkspaceAiConfiguration | null;
  workspaceVersion: number;
  canManage: boolean;
  needsImport: boolean;
  templateVersion: number;
  models: AiModel[];
  toolRates?: { definition: DefinitionRef; toolPerCall: Money }[];
  defaults: { enabled: boolean; budgets: BudgetPreset[] } | null;
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
const configurationCapabilities = z.object({
  available: z.boolean(), manageConnections: z.boolean(), managePlans: z.boolean(), manageDefaultTemplate: z.boolean(),
}).strict().refine(value => value.available || !(value.manageConnections || value.managePlans || value.manageDefaultTemplate));
export type ConfigurationCapabilities = z.infer<typeof configurationCapabilities>;

export async function getConfigurationCapabilities(options?: AiRequestOptions): Promise<ConfigurationCapabilities> {
  const result = await postResult<unknown>('getConfigurationCapabilities', {}, options, undefined, base);
  const parsed = configurationCapabilities.safeParse(result.body.data);
  if (!parsed.success) throw new AiApiError(result.httpStatus, result.body, 'Invalid configuration capabilities');
  return parsed.data;
}

export const getWorkspaceAi = (scope: WorkspaceScope, options?: AiRequestOptions) => postResult<WorkspaceAiView>('getConfiguration', {scope}, options, undefined, base);
export const updateWorkspaceAi = async (request: {
  scope: WorkspaceScope;
  expectedWorkspaceVersion: number;
  expectedVersion: number;
  enabled: boolean;
  models: ModelSelection[]
}, key: string, options?: AiRequestOptions) => {
  requireIdempotencyKey(key);
  const response = await postResult<CommandReceipt<WorkspaceAiView>>('updateConfiguration', request, options, key, base);
  const receipt = response.body.data;
  const configuration = receipt?.value?.configuration;
  if (!validReceipt(receipt, request.expectedVersion + 1) || !configuration || !sameTarget(configuration.scope, request.scope)
    || receipt.value.workspaceVersion !== request.expectedWorkspaceVersion + 1 || configuration.version !== receipt.resultVersion
    || configuration.enabled !== request.enabled || modelKeys(configuration.models) !== modelKeys(request.models)) {
    throw new AiApiError(response.httpStatus, {code: 'INVALID_WORKSPACE_AI_RESPONSE'}, '空间模型回执无效，请确认原操作');
  }
  return response;
};

export interface WorkspaceBudgetRulesView {
  scope: WorkspaceScope;
  configuration: { scope: WorkspaceScope; rules: BudgetPreset[]; version: number } | null;
  workspaceVersion: number;
  canManage: boolean;
  canRaiseBudget: boolean;
  approvedCaps: BudgetPreset[];
}

export interface BudgetRateOption {
  rate: DefinitionRef;
  kind: 'MODEL' | 'TOOL';
  currency: string;
  configured: boolean;
  inputPerMillion: string | null;
  outputPerMillion: string | null;
  toolPerCall: string | null
}

export interface BudgetRatePage {
  items: BudgetRateOption[];
  nextCursor: string | null;
  scope: WorkspaceScope;
  workspaceVersion: number;
  ruleVersion: number | null;
  catalogVersion: number
}

export const getWorkspaceBudgetRules = (scope: WorkspaceScope, options?: AiRequestOptions) => postResult<WorkspaceBudgetRulesView>('getBudgetRules', {scope}, options, undefined, base);
export const listWorkspaceModelOptions = (scope: WorkspaceScope, cursor?: string, options?: AiRequestOptions) => postResult<{
  items: AiModel[];
  nextCursor: string | null
}>('listModelOptions', {scope, cursor}, options, undefined, base);
export const listWorkspaceBudgetRateOptions = (scope: WorkspaceScope, cursor?: string, options?: AiRequestOptions) => postResult<BudgetRatePage>('listBudgetRateOptions', {
  scope,
  cursor
}, options, undefined, base);
export const prepareWorkspaceAi = async (request: {
  scope: WorkspaceScope;
  expectedWorkspaceVersion: number;
  expectedTemplateVersion: number
}, key: string) => {
  requireIdempotencyKey(key);
  const response = await postResult<CommandReceipt<WorkspaceAiView>>('prepareConfiguration', request, undefined, key, base);
  const receipt = response.body.data;
  const configuration = receipt?.value?.configuration;
  if (!validReceipt(receipt, 0) || !configuration || !sameTarget(configuration.scope, request.scope)
    || configuration.version !== 0 || configuration.templateVersion !== request.expectedTemplateVersion
    || configuration.models.length !== 0 || receipt.value.workspaceVersion !== request.expectedWorkspaceVersion + 1) {
    throw new AiApiError(response.httpStatus, {code: 'INVALID_WORKSPACE_AI_RESPONSE'}, '空间首次准备回执无效，请确认原操作');
  }
  return response;
};
export const updateWorkspaceBudgetRules = async (request: {
  scope: WorkspaceScope;
  expectedWorkspaceVersion: number;
  expectedRuleVersion: number;
  rules: BudgetPreset[]
}, key: string) => {
  requireIdempotencyKey(key);
  const response = await postResult<CommandReceipt<WorkspaceBudgetRulesView>>('updateBudgetRules', request, undefined, key, base);
  const receipt = response.body.data;
  const configuration = receipt?.value?.configuration;
  if (!validReceipt(receipt, request.expectedRuleVersion + 1) || !configuration || !sameTarget(receipt.value.scope, request.scope)
    || !sameTarget(configuration.scope, request.scope) || configuration.version !== receipt.resultVersion
    || receipt.value.workspaceVersion !== request.expectedWorkspaceVersion + 1 || capKeys(configuration.rules) !== capKeys(request.rules)) {
    throw new AiApiError(response.httpStatus, {code: 'INVALID_WORKSPACE_RULE_RESPONSE'}, '预算规则回执无效，请确认原操作');
  }
  return response;
};
const sameTarget = (a: WorkspaceScope, b: WorkspaceScope) => a?.tenantId === b.tenantId && a?.workspaceId === b.workspaceId;
const validReceipt = (receipt: CommandReceipt<unknown>, version: number) => !!receipt && receipt.status === 1
  && (receipt.delivery === 0 || receipt.delivery === 1) && typeof receipt.operationId === 'string' && !!receipt.operationId
  && receipt.resultVersion === version;
const modelKeys = (models: ModelSelection[]) => JSON.stringify(models.map(model => [model.resourceId, refKey(model.binding)]).sort());
const capKeys = (items: BudgetPreset[]) => JSON.stringify(items.map(item => [refKey(item.rate),
  String(item.limit.amount).replace(/^(0+)(?=\d)/, '').replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, ''), item.limit.currency]).sort());

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
