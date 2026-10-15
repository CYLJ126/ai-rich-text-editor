import {type AiRequestOptions, postPage, postResult, requireIdempotencyKey,} from './request';
import {
  conversationCommandSchema,
  conversationItemSchema,
  conversationPageSchema,
  conversationReceiptSchema,
  conversationWindowSchema,
  directoryData
} from './conversationDirectorySchema';
import type {
  AiScope,
  ConversationCommandResult,
  ConversationCursorPage,
  ConversationListItem,
  ConversationMetadataReceipt,
  ConversationMetadataRequest,
  ConversationResponse,
  ConversationTurnResponse,
  ConversationWindow,
  CreateConversationRequest,
  CreateDefaultConversationRequest,
  GetConversationRequest,
  ListConversationsRequest,
  QueryTurnsRequest,
} from './types';

/** 同一次创建的重试应复用调用方保存的幂等键。 */
export function createConversation(
  data: CreateConversationRequest,
  idempotencyKey: string,
  options?: AiRequestOptions,
) {
  return postResult<ConversationResponse>(
    'conversation/createConversation',
    data,
    options,
    requireIdempotencyKey(idempotencyKey),
  );
}

export function listConversations(
  data: ListConversationsRequest,
  options?: AiRequestOptions,
) {
  return postPage<ConversationResponse>(
    'conversation/listConversations',
    data,
    options,
  );
}

export function getConversation(
  data: GetConversationRequest,
  options?: AiRequestOptions,
) {
  return postResult<ConversationResponse>(
    'conversation/getConversation',
    data,
    options,
  );
}

export function queryTurnsOfConversation(
  data: QueryTurnsRequest,
  options?: AiRequestOptions,
) {
  return postPage<ConversationTurnResponse>(
    'conversation/queryTurnsOfConversation',
    data,
    options,
  );
}


export function createDefaultConversation(data: CreateDefaultConversationRequest, key: string, options?: AiRequestOptions) {
  return postResult<ConversationResponse>('conversation/createDefaultConversation', data, options, requireIdempotencyKey(key));
}

export function getConversationMetadata(data: GetConversationRequest, options?: AiRequestOptions) {
  return postResult<ConversationListItem>('conversation/getConversationMetadata', data, options).then(directoryData(conversationItemSchema.refine(value => value.conversationId === data.conversationId)));
}

export function queryConversationWindow(data: {
  scope: AiScope;
  normalSize: number;
  anchorConversationId?: string
}, options?: AiRequestOptions) {
  return postResult<ConversationWindow>('conversation/queryConversationWindow', data, options).then(directoryData(conversationWindowSchema));
}

export function listConversationsCursor(data: {
  scope: AiScope;
  pinned: boolean;
  size: number;
  cursor?: string;
  includeTotal?: boolean
}, options?: AiRequestOptions) {
  return postResult<ConversationCursorPage>('conversation/listConversationsCursor', data, options).then(directoryData(conversationPageSchema.refine(value => value.records.length <= data.size && value.records.every(item => item.pinned === data.pinned))));
}

export function changeConversationMetadata(request: ConversationMetadataRequest, key: string, options?: AiRequestOptions) {
  const {kind, ...data} = request;
  return postResult<ConversationMetadataReceipt>(
    kind === 'rename' ? 'conversation/renameConversation' : 'conversation/setConversationPinned', data, options, requireIdempotencyKey(key)).then(directoryData(conversationReceiptSchema));
}

export function getMetadataCommandReceipt(data: { scope: AiScope; commandId: string }, options?: AiRequestOptions) {
  return postResult<ConversationCommandResult>('conversation/getMetadataCommandReceipt', data, options).then(directoryData(conversationCommandSchema));
}
