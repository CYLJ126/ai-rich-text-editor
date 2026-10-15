import {z} from 'zod';
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
const schemas = {
  'ai-configuration': z.discriminatedUnion('kind', [
    z.object({scope: scopeSchema, kind: z.literal('import'), request: z.null()}),
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
      kind: z.literal('publish'),
      request: z.object({expectedVersion: version, catalog: z.record(z.string(), z.unknown())})
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
  'create-default': z.object({scope: scopeSchema}),
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
export type OperationKind = keyof typeof schemas;

export interface Operation<T> {
  key: string;
  request: T;
  acceptedId?: string
}

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
  if (!context) return {value: null, warning: null};
  const stored = readValue<Operation<T>>(storageKey(context, kind, target));
  if (!stored.value) return stored;
  const parsed = z.object({key: id, request: schemas[kind], acceptedId: id.optional()}).safeParse(stored.value);
  if (!parsed.success || !sameScope(parsed.data.request.scope, context.scope)) return {
    value: null,
    warning: RECOVERY_WARNING
  };
  const request = parsed.data.request;
  if ((kind === 'send' && 'conversationId' in request && request.conversationId !== target)
    || (kind === 'reconcile' && 'budgetRef' in request && request.budgetRef !== target)) return {
    value: null,
    warning: RECOVERY_WARNING
  };
  return {value: parsed.data as unknown as Operation<T>, warning: null};
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
