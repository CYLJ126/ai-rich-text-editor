import {z} from 'zod';
import type {PendingChatCommand} from '@/types/ai-new/chat';
import type {WorkspaceSelection} from '@/types/ai-new/conversation';

const prefix = 'arte-ai-new:pending:';
export const pendingStorageKey = (
  userId: string,
  scope: WorkspaceSelection,
  id: string,
) => prefix + JSON.stringify([userId, scope.tenantId, scope.workspaceId, id]);
const body = z.object({
    expectedVersion: z.number().int().positive().refine(Number.isSafeInteger),
    text: z.string().min(1),
    externalTransferConfirmed: z.literal(true),
});
const schema = z.union([
  z.object({key: z.string().uuid(), kind: z.literal('MESSAGE').optional(), body}),
  z.object({
    key: z.string().uuid(),
    kind: z.literal('REGENERATION'),
    body: body.extend({originalTurnId: z.string().min(1)})
  }),
]);

export function readPendingCommand(key: string): PendingChatCommand | null {
  try {
    const value = sessionStorage.getItem(key);
    if (!value) return null;
    const parsed = schema.safeParse(JSON.parse(value));
    return parsed.success ? parsed.data : null;
  } catch {
    return null;
  }
}

// Persist BEFORE sending: if storage is unavailable, no paid command is dispatched.
export function storePendingCommand(key: string, command: PendingChatCommand) {
  sessionStorage.setItem(key, JSON.stringify(command));
}

export function removePendingCommand(key: string) {
  sessionStorage.removeItem(key);
}

export function clearPendingChatCommands() {
  try {
    for (let index = sessionStorage.length - 1; index >= 0; index--) {
      const key = sessionStorage.key(index);
      if (key?.startsWith(prefix)) sessionStorage.removeItem(key);
    }
  } catch {
    // A browser denying storage access must not prevent logout.
  }
}
