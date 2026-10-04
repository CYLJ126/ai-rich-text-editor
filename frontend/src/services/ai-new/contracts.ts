import {z} from 'zod';
import {AiNewApiError} from './request';

const ref = z.object({definitionType: z.string(), definitionId: z.string(), version: z.string()});
const workspace = z.object({tenantId: z.string().min(1), workspaceId: z.string().min(1)});
export const conversationSchema = z.object({
  conversationId: z.string().min(1),
  scope: workspace.extend({principal: z.object({principalId: z.string(), type: z.enum(['USER', 'SERVICE'])})}),
  title: z.string(), modelBindingRef: ref, status: z.enum(['ACTIVE', 'DELETED']),
  version: z.number().int().positive().refine(Number.isSafeInteger), resources: z.array(z.unknown()),
  createdAt: z.iso.datetime(), updatedAt: z.iso.datetime(), deletedAt: z.iso.datetime().nullable(),
});
export const bootstrapSchema = z.object({
  enabled: z.boolean(), unavailableReason: z.enum(['CHAT_DISABLED', 'NO_ACCESS']).nullable(),
  workspaces: z.array(workspace.extend({allowedActions: z.array(z.string())})),
  defaultModel: z.object({
    name: z.string(), providerId: z.string(), bindingRef: ref,
    destination: z.url(), purpose: z.string(), inputTypes: z.array(z.string()), streaming: z.boolean(),
    retrievalEnabled: z.boolean().optional(),
    contextMaxBytes: z.number().int().positive(), maxOutputTokens: z.number().int().positive()
  }).nullable(),
});

export function readContract<T>(schema: z.ZodType<T>, data: unknown): T {
  const checked = schema.safeParse(data);
  if (!checked.success) throw new AiNewApiError(502, null);
  return checked.data;
}
