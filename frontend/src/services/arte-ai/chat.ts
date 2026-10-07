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
