import {AiApiError} from '@/services/arte-ai/request';
import {type ModelCommand, modelCommandSchema} from '@/services/platform/model';
import {type BrowserContext, readValue, removeValue, sameScope, storageKey, writeValue} from './storage';

/** 命令恢复按账号及完整空间隔离；白名单校验保证凭据 token 不进入持久化。 */
export function readModelCommand(context: BrowserContext) {
  const stored = readValue<unknown>(storageKey(context, 'model-management'));
  if (!stored.value) return {command: null, blocked: !!stored.warning};
  const parsed = modelCommandSchema.safeParse(stored.value);
  if (!parsed.success || !sameScope(parsed.data.request.scope, context.scope)) return {command: null, blocked: true};
  return {command: parsed.data, blocked: false};
}

export function saveModelCommand(context: BrowserContext, command: ModelCommand | null) {
  if (!command) return removeValue(storageKey(context, 'model-management'));
  const parsed = modelCommandSchema.safeParse(command);
  return parsed.success && sameScope(parsed.data.request.scope, context.scope)
    && writeValue(storageKey(context, 'model-management'), parsed.data);
}

export function modelFailure(error: unknown, list = false): string {
  if (!(error instanceof AiApiError)) return '请求结果暂时无法确认，请检查网络后重试原操作。';
  if (error.httpStatus === 401) return '登录已失效，请重新登录后确认原操作。';
  if (error.httpStatus === 403) return '当前没有此项操作权限；管理权限与个人密钥维护责任分别核验。';
  if (error.code === 'PLATFORM_IDEMPOTENCY_CONFLICT') return '原操作参数不一致。请保留原操作记录；凭据操作须重新输入同一密钥确认。';
  if (error.httpStatus === 409) return '版本或验证依据已变化。请重新读取当前内容，再检查配置、密钥和验证结果；不会自动覆盖或重新发布。';
  if (error.httpStatus === 404) return list ? '当前部署尚未开放模型管理，请联系管理员确认功能开关与部署。' : '模型不存在、已失去读取资格，或当前部署未开放此功能。';
  if (error.httpStatus >= 500 || error.httpStatus < 300) return '请求结果暂时无法确认，请保留原操作并重试确认。';
  return '操作未完成，请检查当前版本、模板政策及输入内容。';
}

export function uncertainModelFailure(error: unknown) {
  return !(error instanceof AiApiError) || error.httpStatus >= 500 || error.httpStatus < 300 || error.httpStatus === 408
    || error.code === 'PLATFORM_IDEMPOTENCY_CONFLICT';
}

export const modelStatusText: Record<string, string> = {
  VALID: '发布依赖有效；聊天还需目标空间启用和本人预算。',
  READY: '当前可用于聊天，发送时仍会复核权限和预算余额。',
  MODEL_DISABLED: '模型已停用，全部发布版本停止新调用。',
  ADAPTER_UNAVAILABLE: '当前部署不支持此发布的生成协议或能力。',
  MODEL_USE_DENIED: '当前没有模型或空间 AI 使用权限。',
  CREDENTIAL_STALE: '凭据已撤销或轮换，请由责任主体重新验证并发布。',
  CREDENTIAL_USE_DENIED: '当前没有此凭据的使用资格；个人密钥仅本人可用，租户凭据须由责任主体单独授权。',
  TEMPLATE_STALE: '连接模板已变化，请重新检查配置、验证并发布。',
  TEMPLATE_UNAVAILABLE: '连接模板或当前政策已不可用，请联系空间负责人。',
  NO_COMPATIBLE_BUDGET: '已发布，但缺少本人兼容预算。请联系空间负责人；发布不会创建预算或提高额度。',
  WORKSPACE_AI_UNAVAILABLE: '已发布，当前空间 AI 尚未准备或不可用。',
};
export const validationText: Record<string, string> = {
  PENDING: '验证已受理，等待处理。',
  RUNNING: '验证处理中，可查询原验证；不要重复发起。',
  MODEL_FOUND: '认证成功且发现目标模型，可检查审核方案后发布。',
  AUTHENTICATION_FAILED: '供应商认证失败，请检查本人提供的密钥。',
  MODEL_NOT_FOUND: '供应商未列出目标模型，请检查远端模型名。',
  REMOTE_UNAVAILABLE: '供应商暂时不可达；本次没有通过验证。',
  INVALID_RESPONSE: '供应商模型列表响应不符合当前适配要求。',
  STALE: '验证已失效，请保存当前配置并重新验证。',
};
