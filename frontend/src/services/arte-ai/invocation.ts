import {type AiRequestOptions, postResult, requireIdempotencyKey} from './request';
import type {
  ControlReceipt,
  InvocationEventsRequest,
  InvocationEventsResponse,
  InvocationQuery,
  InvocationResultResponse,
  InvocationStatusResponse,
} from './types';

export function getInvocationStatus(
  data: InvocationQuery,
  options?: AiRequestOptions,
) {
  return postResult<InvocationStatusResponse>(
    'invocation/getInvocationStatus',
    data,
    options,
  );
}

export function getInvocationResult(
  data: InvocationQuery,
  options?: AiRequestOptions,
) {
  return postResult<InvocationResultResponse>(
    'invocation/getInvocationResult',
    data,
    options,
  );
}

/** 单页耐久事件重放，不是 SSE；调用方负责保存游标和去重。 */
export function invocationEvent(
  data: InvocationEventsRequest,
  options?: AiRequestOptions,
) {
  return postResult<InvocationEventsResponse>(
    'invocation/invocationEvent',
    data,
    options,
  );
}

/** 耐久停止请求；HTTP 202 不表示执行或费用已确认，继续观察权威状态。 */
export function cancelInvocation(data: InvocationQuery, idempotencyKey: string, options?: AiRequestOptions) {
  return postResult<ControlReceipt>('invocation/cancelInvocation', data, options, requireIdempotencyKey(idempotencyKey));
}
