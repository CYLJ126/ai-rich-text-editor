import {z} from 'zod';
import {type PolicyCommand, policyCommandSchema, type PolicyKind, type PolicyScope} from '@/services/platform/policy';
import {workspaceScopeSchema} from '@/services/platform/workspace';

const operationSchema = z.object({
  schemaVersion: z.literal(1),
  key: z.string().min(1).max(256).refine(value => value.trim() === value
    && Array.from(value).every(character => character.charCodeAt(0) >= 32 && character.charCodeAt(0) !== 127)),
  command: policyCommandSchema
}).strict();
export type PolicyOperation = z.infer<typeof operationSchema>;

/** 租户命令按租户恢复，空间命令按完整 scope 恢复；跨空间进入同租户时仍能找到原租户操作。 */
function key(userId: string, scope: PolicyScope, kind: PolicyKind) {
  if (kind === 'workspace') workspaceScopeSchema.parse(scope);
  return `arte.workspace.policy.v1.${encodeURIComponent(JSON.stringify([userId, scope.tenantId, kind === 'workspace' ? scope.workspaceId : '', kind]))}`;
}

function belongs(command: PolicyCommand, scope: PolicyScope, kind: PolicyKind) {
  return command.kind === kind && (command.kind === 'tenant' ? command.request.tenantId === scope.tenantId
    : command.request.scope.tenantId === scope.tenantId && command.request.scope.workspaceId === scope.workspaceId);
}

export function readPolicyOperation(userId: string, scope: PolicyScope, kind: PolicyKind): {
  operation: PolicyOperation | null;
  blocked: boolean
} {
  try {
    const raw = sessionStorage.getItem(key(userId, scope, kind));
    if (!raw) return {operation: null, blocked: false};
    const parsed = operationSchema.safeParse(JSON.parse(raw));
    if (!parsed.success || !belongs(parsed.data.command, scope, kind)) return {operation: null, blocked: true};
    return {operation: parsed.data, blocked: false};
  } catch {
    return {operation: null, blocked: true};
  }
}

export function savePolicyOperation(userId: string, scope: PolicyScope, kind: PolicyKind, operation: PolicyOperation | null) {
  try {
    if (operation) {
      const parsed = operationSchema.parse(operation);
      if (!belongs(parsed.command, scope, kind)) return false;
      sessionStorage.setItem(key(userId, scope, kind), JSON.stringify(parsed));
    } else sessionStorage.removeItem(key(userId, scope, kind));
    return true;
  } catch {
    return false;
  }
}
