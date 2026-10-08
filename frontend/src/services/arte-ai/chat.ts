import {type AiRequestOptions, postResult, requireIdempotencyKey,} from './request';
import type {ChatAcceptedResponse, SubmitChatRequest} from './types';

/** 返回 HTTP 202 受理回执；执行状态/结果由 invocation 接口查询。 */
export function turnsForChat(
  data: SubmitChatRequest,
  idempotencyKey: string,
  options?: AiRequestOptions,
) {
  return postResult<ChatAcceptedResponse>(
    'chat/turnsForChat',
    data,
    options,
    requireIdempotencyKey(idempotencyKey),
  );
}

/** 独立的新调用；固定原问题、模型参数、预算和原上下文。 */
export function regenerateChat(data: import('./types').RegenerateChatRequest, idempotencyKey: string, options?: AiRequestOptions) {
  return postResult<import('./types').RegenerateAcceptedResponse>('chat/regenerate', data, options, requireIdempotencyKey(idempotencyKey));
}
