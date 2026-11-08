import {z} from 'zod';
import {assistantCommandSchema} from '@/services/arte-ai/assistant';
import {connectionCommandSchema} from '@/services/platform/connection';
import {modelPlanCommandSchema} from '@/services/platform/modelPlan';
import {egressPolicyUpdate} from '@/services/platform/modelEgress';
import {defaultAiPolicySchema} from '@/services/platform/defaultAiPolicyContract';
import type {AiScope} from '@/services/arte-ai';

export interface BrowserContext {
  userId: string;
  scope: AiScope
}

export const STORAGE_WARNING = '本地保存失败，请保留页面；刷新后可能需要重设或确认原操作。';
export const RECOVERY_WARNING = '恢复记录无法读取，请保留页面并检查浏览器存储，暂不能创建新操作。';
const prefix = 'arte.ainew.';
const id = z.string().min(1).max(256);
const scopeSchema = z.object({tenantId: id, workspaceId: id});
const ref = z.object({type: id, id, version: id});
const version = z.number().int().nonnegative();
const baseSchemas = {
  'assistant-command': assistantCommandSchema,
  'workspace-ai-governance': z.discriminatedUnion('kind', [
    z.object({
      scope: scopeSchema, kind: z.literal('prepare'), confirmed: z.boolean().optional(),
      request: z.object({
        scope: scopeSchema,
        expectedWorkspaceVersion: version,
        expectedTemplateVersion: version
      }).strict()
    }).strict(),
    z.object({
      scope: scopeSchema, kind: z.literal('models'), confirmed: z.boolean().optional(), request: z.object({
        scope: scopeSchema, expectedWorkspaceVersion: version, expectedVersion: version, enabled: z.boolean(),
        models: z.array(z.object({resourceId: id, binding: ref}).strict()).max(100)
      }).strict()
    }).strict(),
    z.object({
      scope: scopeSchema, kind: z.literal('rules'), confirmed: z.boolean().optional(), request: z.object({
        scope: scopeSchema, expectedWorkspaceVersion: version, expectedRuleVersion: version,
        rules: z.array(z.object({
          rate: ref,
          limit: z.object({amount: z.union([z.string().min(1), z.number()]), currency: id}).strict()
        }).strict()).max(100)
      }).strict()
    }).strict()
  ]),
  'model-plan-configuration': z.object({scope: scopeSchema, command: modelPlanCommandSchema}).strict(),
  'connection-configuration': z.object({scope: scopeSchema, command: connectionCommandSchema}).strict(),
  'ai-configuration': z.discriminatedUnion('kind', [
    z.object({scope: scopeSchema, kind: z.literal('initialize'), request: z.null()}),
    z.object({
      scope: scopeSchema, kind: z.literal('workspace'), request: z.object({
        scope: scopeSchema, expectedVersion: version, expectedWorkspaceVersion: version,
        preset: z.object({
          enabled: z.boolean(), bindings: z.array(ref).max(100), budgets: z.array(z.object({
            rate: ref,
            limit: z.object({amount: z.union([z.string().min(1), z.number()]), currency: id})
          })).max(100)
        })
      })
    }),
    z.object({
      scope: scopeSchema,
      kind: z.literal('policy'), confirmed: z.boolean().optional(),
      request: defaultAiPolicySchema
    })
  ]),
  send: z.object({
    scope: scopeSchema, conversationId: id, expectedVersion: version, text: z.string().min(1).max(1_000_000),
    capability: ref, binding: ref, budgetRef: id, maxInputTokens: z.number().int().positive(),
    generationOptions: z.object({
      maxOutputTokens: z.number().int().positive(),
      temperature: z.number().nullish(),
      topP: z.number().nullish(),
      stopSequences: z.array(z.string())
    }), timeoutSeconds: z.number().int().positive()
  }),
  regenerate: z.object({
    scope: scopeSchema,
    originalInvocationId: id,
    expectedConversationVersion: version,
    timeoutSeconds: z.number().int().positive()
  }),
  stop: z.object({scope: scopeSchema, invocationId: id}),
  'create-default': z.union([
    z.object({
      scope: scopeSchema,
      title: z.string().min(1).max(256),
      chatProfile: ref.extend({type: z.literal('chat-profile')})
    }).strict(),
    z.object({scope: scopeSchema, localDate: z.iso.date().optional()}).strict()
  ]),
  'model-egress-policy': z.object({
    scope: scopeSchema,
    command: egressPolicyUpdate,
  }).strict(),
  'conversation-metadata': z.discriminatedUnion('kind', [
    z.object({
      kind: z.literal('rename'),
      scope: scopeSchema,
      conversationId: id,
      expectedMetadataVersion: version,
      title: z.string().min(1).max(256)
    }),
    z.object({
      kind: z.literal('pin'),
      scope: scopeSchema,
      conversationId: id,
      expectedMetadataVersion: version,
      pinned: z.boolean()
    })
  ]),
  create: z.object({scope: scopeSchema, title: z.string().min(1).max(256)}),
  reconcile: z.object({
    scope: scopeSchema, budgetRef: id, invocationId: id, invocationVersion: version,
    reservationId: id, reservationVersion: version, actualCharge: z.string(), currency: id,
    evidenceRef: id, note: z.string().min(1).max(2000), executionEnded: z.boolean()
  }),
};
const schemas = {
  ...baseSchemas,
  'budget-responsibility': z.object({
    scope: scopeSchema, accountRef: z.object({tenantId: id, type: z.literal('budget-account'), resourceId: id}),
    kind: z.enum(['assign', 'revoke']), expectedAclVersion: version, subjectId: id.optional(),
    grantId: id.optional(), grantVersion: version.optional()
  }).refine(value => value.accountRef.tenantId === value.scope.tenantId &&
    (value.kind === 'assign' ? !!value.subjectId : !!value.grantId && value.grantVersion !== undefined)),
  'reconcile-managed': baseSchemas.reconcile.extend({
    accountRef: z.object({
      tenantId: id,
      type: z.literal('budget-account'),
      resourceId: id
    })
  })
    .refine(request => request.accountRef.tenantId === request.scope.tenantId && request.accountRef.resourceId === request.budgetRef)
};
export type OperationKind = keyof typeof schemas;

export interface Operation<T> {
  key: string;
  request: T;
  acceptedId?: string
}

export const workspaceStorageKey = (userId: string, kind: string) =>
  `${prefix}${kind}.v1.${encodeURIComponent(JSON.stringify([userId]))}`;

export function storageKey(context: BrowserContext, kind: string, target = '') {
  return `${prefix}${kind}.v1.${encodeURIComponent(JSON.stringify([context.userId, context.scope.tenantId, context.scope.workspaceId, target]))}`;
}

export function sameScope(a: AiScope | null | undefined, b: AiScope | null | undefined) {
  return !!a && !!b && a.tenantId === b.tenantId && a.workspaceId === b.workspaceId;
}

export function readValue<T>(key: string, persistent = false): { value: T | null; warning: string | null } {
  try {
    const raw = (persistent ? localStorage : sessionStorage).getItem(key);
    if (!raw) return {value: null, warning: null};
    const parsed = JSON.parse(raw);
    if (parsed.schemaVersion !== 1 || !('value' in parsed)) throw new Error('schema');
    return {value: parsed.value as T, warning: null};
  } catch {
    return {value: null, warning: RECOVERY_WARNING};
  }
}

export function writeValue(key: string, value: unknown, persistent = false) {
  try {
    (persistent ? localStorage : sessionStorage).setItem(key, JSON.stringify({schemaVersion: 1, value}));
    return true;
  } catch {
    return false;
  }
}

export function removeValue(key: string, persistent = false) {
  try {
    (persistent ? localStorage : sessionStorage).removeItem(key);
    return true;
  } catch {
    return false;
  }
}

export function readOperation<T>(context: BrowserContext | undefined, kind: OperationKind, target: string) {
  if (!context) return {value: null, warning: null, knownId: undefined as string | undefined};
  const key = storageKey(context, kind, target);
  const stored = readValue<Operation<T>>(key);
  if (stored.warning) return {...stored, knownId: undefined as string | undefined};
  try {
    if (stored.value === null && sessionStorage.getItem(key) === null)
      return {...stored, knownId: undefined as string | undefined};
  } catch {
    return {value: null, warning: RECOVERY_WARNING, knownId: undefined as string | undefined};
  }
  // 已知调用 ID 可以独立恢复；损坏的请求／键不能被拼成新写操作。
  const known = z.object({acceptedId: id}).safeParse(stored.value);
  const stop = kind === 'stop' ? z.object({request: z.object({invocationId: id})}).safeParse(stored.value) : undefined;
  const knownId = ['send', 'regenerate', 'stop'].includes(kind) && known.success ? known.data.acceptedId
    : stop?.success ? stop.data.request.invocationId : undefined;
  const parsed = z.object({key: id, request: schemas[kind], acceptedId: id.optional()}).safeParse(stored.value);
  if (!parsed.success || !sameScope(parsed.data.request.scope, context.scope)) return {
    value: null,
    warning: RECOVERY_WARNING, knownId
  };
  const request = parsed.data.request;
  if ((kind === 'send' && 'conversationId' in request && request.conversationId !== target)
    || (['reconcile', 'reconcile-managed'].includes(kind) && 'budgetRef' in request && request.budgetRef !== target)) return {
    value: null,
    warning: RECOVERY_WARNING, knownId
  };
  return {value: parsed.data as unknown as Operation<T>, warning: null, knownId};
}

export function saveOperation<T>(context: BrowserContext | undefined, kind: OperationKind, target: string, operation: Operation<T> | null) {
  if (!context) return true;
  return operation ? writeValue(storageKey(context, kind, target), operation) : removeValue(storageKey(context, kind, target));
}

// Logout clears this account's tab-local drafts and requests, while retaining explicitly local preferences.
export function clearAccountSession(userId: string) {
  try {
    const keys = Array.from({length: sessionStorage.length}, (_, i) => sessionStorage.key(i));
    for (const key of keys) {
      if (!key?.startsWith(prefix)) continue;
      const suffix = key.slice(key.indexOf('.v1.') + 4);
      try {
        if (JSON.parse(decodeURIComponent(suffix))[0] === userId) sessionStorage.removeItem(key);
      } catch { /* unrelated key */
      }
    }
    window.dispatchEvent(new CustomEvent('arte-account-session-cleared', {detail: userId}));
  } catch { /* logout must still complete */
  }
}
