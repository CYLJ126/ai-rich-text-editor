import {z} from 'zod';
import {type ModelUsageRequest, modelUsageRequestSchema} from '@/services/platform/modelUsage';
import {type BrowserContext, readValue, removeValue, sameScope, storageKey, writeValue} from './storage';

const key = z.string().min(1).max(256).refine(value => value.trim() === value && Array.from(value).every(character => character.charCodeAt(0) >= 32 && character.charCodeAt(0) !== 127));
const operationSchema = z.object({
  key,
  request: modelUsageRequestSchema,
  phase: z.enum(['pending', 'confirmed'])
}).strict();
export type ModelUsageOperation = z.infer<typeof operationSchema>;
export const MODEL_CHAT_HANDOFF_EVENT = 'arte-model-chat-handoff';
const handoffSchema = z.object({id: key, request: modelUsageRequestSchema}).strict();
export type ModelChatHandoff = z.infer<typeof handoffSchema>;

/** 原请求及原键按账号和执行空间隔离，禁止恢复记录携带额度、密钥或其他额外字段。 */
export function readModelUsageOperation(context: BrowserContext) {
  const saved = readValue<unknown>(storageKey(context, 'model-usage'));
  if (!saved.value) return {operation: null, blocked: !!saved.warning};
  const value = operationSchema.safeParse(saved.value);
  return value.success && sameScope(value.data.request.scope, context.scope)
    ? {operation: value.data, blocked: false} : {operation: null, blocked: true};
}

export function saveModelUsageOperation(context: BrowserContext, operation: ModelUsageOperation | null) {
  if (!operation) return removeValue(storageKey(context, 'model-usage'));
  const value = operationSchema.safeParse(operation);
  return value.success && sameScope(value.data.request.scope, context.scope)
    && writeValue(storageKey(context, 'model-usage'), value.data);
}

/** 聊天交接只记录选型意图；聊天必须重新发现精确版本和预算，并由用户明确应用。 */
export function readModelChatHandoff(context: BrowserContext) {
  const saved = readValue<unknown>(storageKey(context, 'model-chat-handoff'));
  if (!saved.value) return {handoff: null, warning: saved.warning};
  const value = handoffSchema.safeParse(saved.value);
  return value.success && sameScope(value.data.request.scope, context.scope)
    ? {handoff: value.data, warning: null} : {handoff: null, warning: '待选择模型记录无效，请回到模型管理重新检查。'};
}

export function saveModelChatHandoff(context: BrowserContext, request: ModelUsageRequest) {
  const value = handoffSchema.safeParse({id: crypto.randomUUID(), request});
  if (!value.success || !sameScope(value.data.request.scope, context.scope)
    || !writeValue(storageKey(context, 'model-chat-handoff'), value.data)) return false;
  window.dispatchEvent(new CustomEvent(MODEL_CHAT_HANDOFF_EVENT));
  return true;
}

export function clearModelChatHandoff(context: BrowserContext, id: string) {
  if (readModelChatHandoff(context).handoff?.id !== id) return false;
  if (!removeValue(storageKey(context, 'model-chat-handoff'))) return false;
  window.dispatchEvent(new CustomEvent(MODEL_CHAT_HANDOFF_EVENT));
  return true;
}
