import {request} from '@umijs/max';
import type {AiHttpResponse, AiPage, AiResult, AiResultMetadata,} from './types';

export interface AiRequestOptions {
  signal?: AbortSignal;
}

/** 只合并正在进行的读取；完整请求参数和当前登录凭据隔离资源，不缓存授权或余额。 */
const reads = new Map<string, { controller: AbortController; users: Set<symbol>; promise: Promise<unknown> }>();

function sharedRead<T>(key: string, signal: AbortSignal | undefined, run: (signal: AbortSignal) => Promise<T>): Promise<T> {
  if (signal?.aborted) return Promise.reject(new DOMException('Aborted', 'AbortError'));
  let entry = reads.get(key);
  if (!entry || entry.controller.signal.aborted) {
    const controller = new AbortController();
    const pending = {
      controller, users: new Set<symbol>(), promise: Promise.resolve().then(() => {
        if (controller.signal.aborted) throw new DOMException('Aborted', 'AbortError');
        return run(controller.signal);
      })
    };
    entry = pending;
    reads.set(key, pending);
    void pending.promise.finally(() => {
      if (reads.get(key) === pending) reads.delete(key);
    }).catch(() => {
    });
  }
  const pending = entry;
  return new Promise<T>((resolve, reject) => {
    const user = Symbol();
    pending.users.add(user);
    const detach = () => {
      signal?.removeEventListener('abort', abort);
      pending.users.delete(user);
      if (!pending.users.size && reads.get(key) === pending) {
        reads.delete(key);
        pending.controller.abort();
      }
    };
    const abort = () => {
      detach();
      reject(new DOMException('Aborted', 'AbortError'));
    };
    signal?.addEventListener('abort', abort, {once: true});
    pending.promise.then(value => {
      detach();
      if (!signal?.aborted) resolve(value as T);
    }, error => {
      detach();
      reject(error);
    });
  });
}

const readPaths = new Set([
  'configuration/discoverChatOptions', 'budget/getBudget',
  'conversation/queryConversationWindow', 'conversation/listConversationsCursor', 'conversation/getConversationMetadata', 'conversation/getMetadataCommandReceipt',
  'conversation/getConversation', 'conversation/listConversations', 'conversation/queryTurnsOfConversation',
  'invocation/getInvocationStatus', 'invocation/getInvocationResult', 'invocation/getInvocationSnapshots',
  'tool-chat/discoverToolChat', 'tool-chat/getRunStatus', 'tool-chat/getRunResult',
  'tool-chat/getRunBudget', 'tool-chat/replayRunEvents', 'tool-chat/getRunActions',
]);

function stable(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => [key, stable(item)]));
  return value;
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

async function transport<T extends AiResultMetadata>(
  path: string,
  data: unknown,
  options: AiRequestOptions | undefined,
  idempotencyKey?: string,
  basePath = '/arte/ai-new',
): Promise<AiHttpResponse<T>> {
  const response = await request<unknown>(`${basePath}/${path}`, {
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

export function currentAiCredential(): string | null {
  try {
    return localStorage.getItem('user_token');
  } catch {
    return null;
  }
}

export function shareAiRead<T>(resource: unknown, signal: AbortSignal | undefined, run: (signal: AbortSignal) => Promise<T>) {
  const token = currentAiCredential();
  return sharedRead(JSON.stringify([token, stable(resource)]), signal, async transportSignal => {
    if (currentAiCredential() !== token) throw new DOMException('Account changed', 'AbortError');
    const value = await run(transportSignal);
    if (currentAiCredential() !== token) throw new DOMException('Account changed', 'AbortError');
    return value;
  });
}

async function post<T extends AiResultMetadata>(path: string, data: unknown, options: AiRequestOptions | undefined,
                                                idempotencyKey?: string, basePath = '/arte/ai-new'): Promise<AiHttpResponse<T>> {
  if (basePath !== '/arte/ai-new' || idempotencyKey || !readPaths.has(path))
    return transport<T>(path, data, options, idempotencyKey, basePath);
  return shareAiRead([basePath, path, data], options?.signal, signal => transport<T>(path, data, {signal}, undefined, basePath));
}

export async function postResult<T>(
  path: string,
  data: unknown,
  options?: AiRequestOptions,
  idempotencyKey?: string,
  basePath = '/arte/ai-new',
): Promise<AiHttpResponse<AiResult<T>>> {
  const response = await post<AiResult<T>>(path, data, options, idempotencyKey, basePath);
  if (response.body.data === undefined || response.body.data === null) {
    throw new AiApiError(
      response.httpStatus,
      response.body,
      'AI API response is missing data',
    );
  }
  return response;
}

/** 关闭等无返回数据的操作仍验证统一响应包装，允许 data 为空。 */
export function postEmpty(path: string, data: unknown, options?: AiRequestOptions): Promise<AiHttpResponse<AiResultMetadata>> {
  return post<AiResultMetadata>(path, data, options);
}

export async function postPage<T>(
  path: string,
  data: unknown,
  options?: AiRequestOptions,
  basePath = '/arte/ai-new',
): Promise<AiHttpResponse<AiPage<T>>> {
  const response = await post<AiPage<T>>(path, data, options, undefined, basePath);
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
