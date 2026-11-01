import {z} from 'zod';
import {AiApiError, type AiRequestOptions, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';

const id = z.string().min(1).max(256);
const version = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
const reference = (type: string) => z.object({type: z.literal(type), id, version: id}).strict();
const price = z.string().regex(/^[0-9]{1,18}(\.[0-9]{1,18})?$/);
// 历史 Money 支持更大的整数位；读取不能因新页面的输入限制而拒绝已有目录。
const publishedPrice = z.string().max(76).regex(/^[0-9]+(\.[0-9]+)?$/);
export const modelPlanSettingsSchema = z.object({
  id: z.string().regex(/^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/),
  connection: reference('connection').refine(ref => ref.version.toLowerCase() !== 'latest'),
  remoteModel: id.refine(value => value.trim() === value && Array.from(value).every(c => c.charCodeAt(0) >= 32 && c.charCodeAt(0) !== 127)),
  contextWindowTokens: z.number().int().min(2).max(10_000_000),
  currency: z.string().regex(/^[A-Z]{3}$/),
  inputPerMillion: price,
  outputPerMillion: price,
  usageReporting: z.boolean(),
}).strict();
export const modelPlanCommandSchema = z.object({expectedVersion: version, settings: modelPlanSettingsSchema}).strict();
const planSchema = z.object({
  binding: reference('binding'),
  connection: reference('connection'),
  rate: reference('rate'),
  remoteModel: id,
  contextWindowTokens: z.number().int().min(2).max(10_000_000),
  currency: z.string().regex(/^[A-Z]{3}$/),
  inputPerMillion: publishedPrice,
  outputPerMillion: publishedPrice,
  usageReporting: z.boolean()
});
const viewSchema = z.object({version, initialized: z.boolean(), plans: z.array(planSchema).max(256)})
  .refine(view => view.initialized === (view.version > 0))
  .refine(view => new Set(view.plans.map(plan => JSON.stringify(plan.binding))).size === view.plans.length);
export type ModelPlanSettings = z.infer<typeof modelPlanSettingsSchema>;
export type ModelPlanCommand = z.infer<typeof modelPlanCommandSchema>;
export type ModelPlanView = z.infer<typeof viewSchema>;
export type ManagedModelPlan = z.infer<typeof planSchema>;
const base = '/arte/workspace/ai';
const invalid = (status: number) => new AiApiError(status, {code: 'INVALID_MODEL_PLAN_RESPONSE'});

export async function getModelPlans(options?: AiRequestOptions): Promise<ModelPlanView> {
  const response = await postResult<unknown>('getModelPlans', {}, options, undefined, base);
  const result = viewSchema.safeParse(response.body.data);
  if (!result.success) throw invalid(response.httpStatus);
  return result.data;
}

/** 发布回执必须包含本次请求的完整固定版本；错误成功响应保留为待确认操作。 */
export async function publishModelPlan(command: ModelPlanCommand, key: string): Promise<void> {
  requireIdempotencyKey(key);
  const request = modelPlanCommandSchema.parse(command);
  const response = await postResult<unknown>('publishModelPlan', request, undefined, key, base);
  const receipt = z.object({
    operationId: id, status: z.literal(1), delivery: z.union([z.literal(0), z.literal(1)]),
    resultVersion: version, value: viewSchema, acceptedAt: z.string(), completedAt: z.string().nullable()
  }).safeParse(response.body.data);
  if (!receipt.success || receipt.data.resultVersion !== request.expectedVersion + 1 || receipt.data.value.version !== receipt.data.resultVersion)
    throw invalid(response.httpStatus);
  const settings = request.settings;
  const plan = receipt.data.value.plans.find(p => p.binding.id === `reviewed-${settings.id}` && p.binding.version === `db-v${receipt.data.resultVersion}`);
  if (!plan || plan.remoteModel !== settings.remoteModel || plan.contextWindowTokens !== settings.contextWindowTokens
    || plan.rate.id !== `reviewed-${settings.id}` || plan.rate.version !== `db-v${receipt.data.resultVersion}`
    || plan.connection.id !== settings.connection.id || plan.connection.version !== settings.connection.version
    || plan.currency !== settings.currency || plan.usageReporting !== settings.usageReporting
    || decimal(plan.inputPerMillion) !== decimal(settings.inputPerMillion) || decimal(plan.outputPerMillion) !== decimal(settings.outputPerMillion))
    throw invalid(response.httpStatus);
}

function decimal(value: string) {
  const [whole, fraction = ''] = value.split('.');
  return `${whole.replace(/^0+(?=\d)/, '')}.${fraction.replace(/0+$/, '')}`;
}
