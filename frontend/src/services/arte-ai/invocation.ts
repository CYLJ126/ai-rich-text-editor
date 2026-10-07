import {type AiRequestOptions, postResult} from './request';
import type {
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
