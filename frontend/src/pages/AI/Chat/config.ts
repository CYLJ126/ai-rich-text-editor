import {z} from 'zod';
import type {ChatModelOption} from '@/services/arte-ai';

const identifier = z
  .string('app.aiChat.validation.required')
  .trim()
  .min(1, 'app.aiChat.validation.required')
  .max(256, 'app.aiChat.validation.maxLength');
const version = identifier.refine(
  (value) => value.toLowerCase() !== 'latest',
  'app.aiChat.validation.fixedVersion',
);
const integer = (max: number) =>
  z
    .number('app.aiChat.validation.number')
    .int('app.aiChat.validation.integer')
    .min(1, 'app.aiChat.validation.positive')
    .max(max, 'app.aiChat.validation.range');

export const chatScopeSchema = z.object({tenantId: identifier, workspaceId: identifier});

export function modelOptionKey(option: Pick<ChatModelOption, 'binding'>): string {
  return JSON.stringify([option.binding.id, option.binding.version]);
}

export function matchesModel(config: ChatConfigValues, option: ChatModelOption): boolean {
  return config.bindingId === option.binding.id && config.bindingVersion === option.binding.version
    && config.capabilityId === option.capability.id && config.capabilityVersion === option.capability.version;
}

/** 缓存及表单还需经过当前发现结果检查，前端检查不替代提交时的授权。 */
export function selectedChatConfigSchema(option: ChatModelOption) {
  return chatConfigSchema.superRefine((config, ctx) => {
    const issue = (field: keyof ChatTestConfig, message: string) => ctx.addIssue({
      code: 'custom',
      path: [field],
      message
    });
    if (!matchesModel(config, option)) issue('bindingId', 'app.aiChat.validation.modelUnavailable');
    if (!option.budgetRefs.includes(config.budgetRef)) issue('budgetRef', 'app.aiChat.validation.budgetUnavailable');
    if (config.maxInputTokens > option.limits.maxInputTokens) issue('maxInputTokens', 'app.aiChat.validation.serverLimit');
    if (config.maxOutputTokens > option.limits.maxOutputTokens) issue('maxOutputTokens', 'app.aiChat.validation.serverLimit');
    if (config.maxInputTokens + config.maxOutputTokens > option.contextWindowTokens) issue('maxInputTokens', 'app.aiChat.validation.contextWindow');
    if (config.timeoutSeconds > option.limits.maxTimeoutSeconds) issue('timeoutSeconds', 'app.aiChat.validation.serverLimit');
  });
}

export const chatConfigSchema = z.object({
  tenantId: identifier,
  workspaceId: identifier,
  capabilityId: identifier,
  capabilityVersion: version,
  bindingId: identifier,
  bindingVersion: version,
  budgetRef: identifier,
  maxInputTokens: integer(10_000_000),
  maxOutputTokens: integer(1_000_000),
  timeoutSeconds: integer(3600),
  temperature: z
    .number('app.aiChat.validation.number')
    .min(0, 'app.aiChat.validation.range')
    .max(2, 'app.aiChat.validation.range')
    .nullish()
    .transform((value) => value ?? null),
  topP: z
    .number('app.aiChat.validation.number')
    .gt(0, 'app.aiChat.validation.range')
    .max(1, 'app.aiChat.validation.range')
    .nullish()
    .transform((value) => value ?? null),
});

export type ChatTestConfig = z.output<typeof chatConfigSchema>;
export type ChatConfigValues = z.input<typeof chatConfigSchema>;

/** 引用必须填写真实发布值；多轮联调默认输入 32768、输出 512，需匹配后端绑定容量与预算。 */
export const DEFAULT_CHAT_CONFIG: ChatConfigValues = {
  tenantId: '',
  workspaceId: '',
  capabilityId: '',
  capabilityVersion: '',
  bindingId: '',
  bindingVersion: '',
  budgetRef: '',
  maxInputTokens: 32768,
  maxOutputTokens: 512,
  timeoutSeconds: 60,
  temperature: null,
  topP: null,
};

export const CHAT_CONFIG_STORAGE_KEY = 'arte.ai-new.chat.config.v1';
export type ConfigStorageWarning =
  | 'invalid'
  | 'unavailable'
  | 'saveFailed'
  | 'clearFailed';

export function loadChatConfig(): {
  config: ChatTestConfig | null;
  warning: ConfigStorageWarning | null;
} {
  let raw: string | null;
  try {
    raw = localStorage.getItem(CHAT_CONFIG_STORAGE_KEY);
  } catch {
    return {config: null, warning: 'unavailable'};
  }
  if (!raw) return {config: null, warning: null};
  try {
    const stored = JSON.parse(raw);
    if (stored?.version !== 1) return {config: null, warning: 'invalid'};
    const result = chatConfigSchema.safeParse(stored.config);
    return result.success
      ? {config: result.data, warning: null}
      : {config: null, warning: 'invalid'};
  } catch {
    return {config: null, warning: 'invalid'};
  }
}

export function saveChatConfig(config: ChatTestConfig): boolean {
  try {
    // 只保存校验后的白名单字段，不保存 Token、用户身份或供应商密钥。
    const validated = chatConfigSchema.parse(config);
    localStorage.setItem(
      CHAT_CONFIG_STORAGE_KEY,
      JSON.stringify({version: 1, config: validated}),
    );
    return true;
  } catch {
    return false;
  }
}

export function clearChatConfig(): boolean {
  try {
    localStorage.removeItem(CHAT_CONFIG_STORAGE_KEY);
    return true;
  } catch {
    return false;
  }
}
