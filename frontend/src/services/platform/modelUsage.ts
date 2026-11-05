import {z} from 'zod';
import {AiApiError, type AiRequestOptions, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';
import {modelRefSchema} from './model';
import {workspaceScopeSchema} from './workspace';

const id = z.string().min(1).max(256);
const binding = modelRefSchema.extend({type: z.literal('binding')});
const rate = z.object({type: z.literal('rate'), id, version: id.refine(value => value.toLowerCase() !== 'latest')});
export const modelUsageRequestSchema = z.object({scope: workspaceScopeSchema, resourceId: id, binding}).strict();
export type ModelUsageRequest = z.infer<typeof modelUsageRequestSchema>;
const amount = z.union([z.string().regex(/^\d+(?:\.\d+)?$/), z.number().finite().nonnegative()]).transform(String);
const actions = z.array(z.enum(['PREPARE_OWN_BUDGET', 'CONFIGURE_WORKSPACE_AI'])).max(2);
export const spaceConditions = ['PUBLICATION', 'RUNTIME_ADAPTER', 'EXECUTION_POLICY', 'WORKSPACE_SELECTION', 'SPACE_BUDGET_RULE'] as const;
export const personalConditions = ['WORKSPACE_AI_ACCESS', 'MODEL_ACCESS', 'CREDENTIAL_USE', 'OWN_BUDGET'] as const;
const check = z.object({
  status: z.enum(['PASS', 'BLOCKED', 'UNKNOWN', 'NOT_APPLICABLE']),
  reason: z.string().regex(/^[A-Z][A-Z0-9_]{0,95}$/),
});
const group = <T extends readonly [string, ...string[]]>(keys: T) =>
  z.array(check.extend({condition: z.enum(keys)})).length(keys.length)
    .refine(items => new Set(items.map(item => item.condition)).size === keys.length);
const checksSchema = z.object({
  scope: workspaceScopeSchema,
  resource: z.object({tenantId: id, type: z.literal('model'), resourceId: id}),
  binding,
  spaceConditions: group(spaceConditions),
  personalConditions: group(personalConditions),
  allowedActions: actions,
  canConfigureBudget: z.boolean().default(false),
});
export type ModelUsageChecks = z.infer<typeof checksSchema>;

const preparationSchema = z.object({
  scope: workspaceScopeSchema,
  resource: z.object({tenantId: id, type: z.literal('model'), resourceId: id}),
  binding, rate,
  state: z.enum(['READY', 'PREPARABLE', 'BLOCKED']), reason: z.string().min(1).max(96),
  budgetRefs: z.array(id).max(256),
  allowedActions: actions.default([]),
  approvedLimit: z.object({amount, currency: z.string().regex(/^[A-Z]{3}$/)}).nullable(),
}).refine(value => value.state !== 'READY' || value.reason === 'READY' && value.budgetRefs.length > 0)
  .refine(value => new Set(value.budgetRefs).size === value.budgetRefs.length);
export type ModelUsagePreparation = z.infer<typeof preparationSchema>;
const receiptSchema = z.object({
  operationId: id, status: z.literal(1), delivery: z.union([z.literal(0), z.literal(1)]),
  value: preparationSchema.refine(value => value.state === 'READY'), resultVersion: z.number().int().nonnegative(),
  acceptedAt: z.string(), completedAt: z.string().nullable(),
});

function checkIdentity(value: Pick<ModelUsagePreparation, 'scope' | 'resource' | 'binding'>, request: ModelUsageRequest) {
  if (value.scope.tenantId !== request.scope.tenantId || value.scope.workspaceId !== request.scope.workspaceId || value.resource.tenantId !== request.scope.tenantId
    || value.resource.resourceId !== request.resourceId || value.binding.id !== request.binding.id
    || value.binding.version !== request.binding.version) {
    throw new AiApiError(200, {code: 'INVALID_MODEL_USAGE_RESPONSE'}, '模型准备响应的目标不一致');
  }
}

/** 返回安全白名单字段；准备回执仅证明原操作，不作为当前可执行授权。 */
export async function getModelUsagePreparation(target: ModelUsageRequest, options?: AiRequestOptions) {
  const request = modelUsageRequestSchema.parse(target);
  const response = await postResult<unknown>('getModelUsagePreparation', request, options, undefined, '/arte/ai-new/model-resource');
  const value = preparationSchema.safeParse(response.body.data);
  if (!value.success) throw new AiApiError(response.httpStatus, {code: 'INVALID_MODEL_USAGE_RESPONSE'}, '模型准备状态无效');
  checkIdentity(value.data, request);
  return value.data;
}

/** 只读取指定执行空间与固定版本的检查结果，不创建预算或写入授权。 */
export async function getModelUsageChecks(target: ModelUsageRequest, options?: AiRequestOptions) {
  const request = modelUsageRequestSchema.parse(target);
  const response = await postResult<unknown>('getModelUsageChecks', request, options, undefined, '/arte/ai-new/model-resource');
  const value = checksSchema.safeParse(response.body.data);
  if (!value.success) throw new AiApiError(response.httpStatus, {code: 'INVALID_MODEL_USAGE_RESPONSE'}, '模型使用条件响应无效');
  checkIdentity(value.data, request);
  return value.data;
}

export async function prepareModelUsage(target: ModelUsageRequest, key: string) {
  requireIdempotencyKey(key);
  const request = modelUsageRequestSchema.parse(target);
  const response = await postResult<unknown>('prepareModelUsage', request, undefined, key, '/arte/ai-new/model-resource');
  const value = receiptSchema.safeParse(response.body.data);
  if (!value.success) throw new AiApiError(response.httpStatus, {code: 'INVALID_MODEL_USAGE_RESPONSE'}, '模型准备回执无效');
  checkIdentity(value.data.value, request);
  return value.data;
}
