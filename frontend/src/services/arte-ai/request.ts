import {request} from '@umijs/max';
import type {AiHttpResponse, AiPage, AiResult, AiResultMetadata,} from './types';

export interface AiRequestOptions {
  signal?: AbortSignal;
}

/** HTTP/业务失败保留原始响应；网络错误与取消请求仍按原错误抛出。 */
export class AiApiError extends Error {
  readonly httpStatus: number;
  readonly code?: string;
  readonly desc?: string;
  readonly body: unknown;

  constructor(httpStatus: number, body: unknown, message?: string) {
    const metadata = isObject(body) ? body : {};
    const desc = typeof metadata.desc === 'string' ? metadata.desc : undefined;
    super(message || desc || `AI API response error (HTTP ${httpStatus})`);
    this.name = 'AiApiError';
    this.httpStatus = httpStatus;
    this.code = typeof metadata.code === 'string' ? metadata.code : undefined;
    this.desc = desc;
    this.body = body;
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

async function post<T extends AiResultMetadata>(
  path: string,
  data: unknown,
  options: AiRequestOptions | undefined,
  idempotencyKey?: string,
): Promise<AiHttpResponse<T>> {
  const response = await request<unknown>(`/arte/ai-new/${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(idempotencyKey === undefined
        ? {}
        : {'Idempotency-Key': idempotencyKey}),
    },
    data,
    signal: options?.signal,
    getResponse: true,
    // 页面自行处理 409/410 等业务状态，仍复用全局 Token 和语言拦截器。
    skipErrorHandler: true,
    validateStatus: () => true,
  });
  const body = response.data;
  if (
    response.status < 200 ||
    response.status >= 300 ||
    (isObject(body) && body.success === false)
  ) {
    throw new AiApiError(response.status, body);
  }
  if (
    !isObject(body) ||
    body.success !== true ||
    typeof body.code !== 'string' ||
    typeof body.desc !== 'string'
  ) {
    throw new AiApiError(
      response.status,
      body,
      'Invalid AI API response envelope',
    );
  }
  return {httpStatus: response.status, body: body as unknown as T};
}

export async function postResult<T>(
  path: string,
  data: unknown,
  options?: AiRequestOptions,
  idempotencyKey?: string,
): Promise<AiHttpResponse<AiResult<T>>> {
  const response = await post<AiResult<T>>(path, data, options, idempotencyKey);
  if (response.body.data === undefined || response.body.data === null) {
    throw new AiApiError(
      response.httpStatus,
      response.body,
      'AI API response is missing data',
    );
  }
  return response;
}

export async function postPage<T>(
  path: string,
  data: unknown,
  options?: AiRequestOptions,
): Promise<AiHttpResponse<AiPage<T>>> {
  const response = await post<AiPage<T>>(path, data, options);
  const {records, current, size, total} = response.body;
  if (
    !Array.isArray(records) ||
    !Number.isSafeInteger(current) ||
    current < 1 ||
    !Number.isSafeInteger(size) ||
    size < 1 ||
    !Number.isSafeInteger(total) ||
    total < 0
  ) {
    throw new AiApiError(
      response.httpStatus,
      response.body,
      'Invalid AI API page response',
    );
  }
  return response;
}

export function requireIdempotencyKey(key: string): string {
  if (
    typeof key !== 'string' ||
    !key.trim() ||
    key.length > 256 ||
    /[\r\n]/.test(key)
  ) {
    throw new TypeError(
      'Idempotency-Key must be a non-empty string of at most 256 characters',
    );
  }
  return key;
}
