import {z} from 'zod';
import {type SharingCommand, sharingCommandSchema,} from '@/services/platform/modelSharing';
import {workspaceScopeSchema} from '@/services/platform/workspace';
import {type BrowserContext, readValue, removeValue, sameScope, storageKey, writeValue,} from './storage';

const schema = z
  .object({
    key: z.string().min(1).max(256).refine(value => value.trim() === value
      && Array.from(value).every(character => character.charCodeAt(0) >= 32 && character.charCodeAt(0) !== 127)),
    scope: workspaceScopeSchema,
    command: sharingCommandSchema,
  })
  .strict();
export type SharingOperation = z.infer<typeof schema>;

function belongs(context: BrowserContext, operation: SharingOperation) {
  const request = operation.command.request;
  return (
    sameScope(context.scope, operation.scope) &&
    ('scope' in request
      ? sameScope(request.scope, context.scope)
      : request.target.scope.tenantId === context.scope.tenantId)
  );
}

export function readSharingOperation(context: BrowserContext) {
  const stored = readValue<unknown>(storageKey(context, 'model-sharing'));
  if (!stored.value) return {operation: null, blocked: !!stored.warning};
  const parsed = schema.safeParse(stored.value);
  return parsed.success && belongs(context, parsed.data)
    ? {operation: parsed.data, blocked: false}
    : {operation: null, blocked: true};
}

export function saveSharingOperation(
  context: BrowserContext,
  operation: SharingOperation | null,
) {
  if (!operation) return removeValue(storageKey(context, 'model-sharing'));
  const parsed = schema.safeParse(operation);
  return (
    parsed.success &&
    belongs(context, parsed.data) &&
    writeValue(storageKey(context, 'model-sharing'), parsed.data)
  );
}

export function newSharingOperation(
  context: BrowserContext,
  command: SharingCommand,
): SharingOperation {
  return {key: crypto.randomUUID(), scope: context.scope, command};
}
