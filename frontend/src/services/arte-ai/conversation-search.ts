import {type AiRequestOptions, postEmpty, postResult} from './request';
import type {
  CloseConversationSearchRequest,
  ConversationSearchPage,
  ConversationTurnsAroundPage,
  QueryTurnsAroundRequest,
  SearchConversationsRequest,
} from './conversation-search-contract';

/** 每次不带 cursor 的调用创建新快照；调用方负责合并进行中的同词请求。 */
export function searchConversations(data: SearchConversationsRequest, options?: AiRequestOptions) {
  return postResult<ConversationSearchPage>('conversation/searchConversations', data, options);
}

export function closeSearch(data: CloseConversationSearchRequest, options?: AiRequestOptions) {
  return postEmpty('conversation/closeSearch', data, options);
}

export function queryTurnsAround(data: QueryTurnsAroundRequest, options?: AiRequestOptions) {
  return postResult<ConversationTurnsAroundPage>('conversation/queryTurnsAround', data, options);
}
