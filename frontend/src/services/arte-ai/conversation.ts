import {type AiRequestOptions, postPage, postResult, requireIdempotencyKey,} from './request';
import type {
  ConversationResponse,
  ConversationTurnResponse,
  CreateConversationRequest,
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
