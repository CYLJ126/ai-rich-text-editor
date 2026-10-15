import {z} from 'zod';
import {AiApiError} from './request';
import type {
  AiHttpResponse,
  AiResult,
  ConversationCommandResult,
  ConversationCursorPage,
  ConversationListItem,
  ConversationMetadataReceipt,
  ConversationWindow
} from './types';

const version = z.number().int().nonnegative();
const id = z.string().min(1).max(256);
const time = z.iso.datetime({offset: true});
export const conversationItemSchema: z.ZodType<ConversationListItem> = z.object({
  conversationId: id, title: z.string().min(1), metadataVersion: version, pinned: z.boolean(),
  titleManuallyEdited: z.boolean(), autoTitleResolved: z.boolean(), previewText: z.string().nullable(),
  userMessageCount: version.nullable(), lastMessageGeneratedAt: time.nullable(), sortActivityAt: time,
  createdAt: time, statisticsReady: z.boolean(),
});
export const conversationPageSchema: z.ZodType<ConversationCursorPage> = z.object({
  records: z.array(conversationItemSchema).max(100), nextCursor: z.string().min(1).max(2048).nullable(),
  hasMore: z.boolean(), total: version.nullable(),
}).refine(value => !value.hasMore || value.nextCursor != null);
export const conversationWindowSchema: z.ZodType<ConversationWindow> = z.object({
  pinned: conversationPageSchema,
  normal: conversationPageSchema,
  anchorConversationId: id.nullable(),
  anchorFallback: z.boolean(),
}).refine(value => value.pinned.records.length <= 7 && value.pinned.records.every(item => item.pinned)
  && value.normal.records.every(item => !item.pinned) && value.pinned.records.length + value.normal.records.length <= 100);
export const conversationReceiptSchema: z.ZodType<ConversationMetadataReceipt> = z.object({
  conversationId: id,
  commandId: id,
  conversation: conversationItemSchema,
  pinnedCount: version.max(7),
  changed: z.boolean(),
}).refine(value => value.conversationId === value.conversation.conversationId);
export const conversationCommandSchema: z.ZodType<ConversationCommandResult> = z.object({
  commandId: id, outcome: z.enum(['APPLIED', 'NOT_FOUND', 'OWNER_MISMATCH', 'VERSION_CONFLICT', 'IDEMPOTENCY_CONFLICT',
    'INVALID_STATE', 'PIN_LIMIT_REACHED']), receipt: conversationReceiptSchema.nullable(),
}).refine(value => value.outcome === 'APPLIED' ? value.receipt?.commandId === value.commandId : value.receipt === null);

export function directoryData<T>(schema: z.ZodType<T>) {
  return (response: AiHttpResponse<AiResult<T>>): AiHttpResponse<AiResult<T>> => {
    const parsed = schema.safeParse(response.body.data);
    if (!parsed.success) throw new AiApiError(response.httpStatus, response.body, 'Invalid conversation directory response');
    return {...response, body: {...response.body, data: parsed.data}};
  };
}
