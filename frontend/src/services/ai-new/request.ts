import {request} from '@umijs/max';
import type {ExecutionError} from '@/types/ai-new/conversation';

export class AiNewApiError extends Error {
  constructor(public readonly status: number, public readonly facts: ExecutionError | null) {
    super(facts?.code ?? 'AI_NEW_REQUEST_FAILED');
    this.name = 'AiNewApiError';
  }
}

export function normalizeApiError(error: unknown): AiNewApiError {
  if (error instanceof AiNewApiError) return error;
  const response = (error as { response?: { status?: number; data?: unknown } } | null)?.response;
  const data = response?.data as Partial<ExecutionError> | undefined;
  // Only retain the stable error envelope; never display transport / provider text.
  const facts = data && typeof data.code === 'string' && typeof data.failureStage === 'string'
  && typeof data.retryable === 'boolean'
  && ['NONE', 'OCCURRED', 'UNKNOWN'].includes(data.sideEffectStatus ?? '')
  && ['CONFIRMED', 'UNKNOWN'].includes(data.resultCertainty ?? '')
    ? {
      code: data.code, failureStage: data.failureStage, retryable: data.retryable,
      sideEffectStatus: data.sideEffectStatus, resultCertainty: data.resultCertainty
    } as ExecutionError : null;
  return new AiNewApiError(response?.status ?? 0, facts);
}

export interface AiNewRequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
  params?: Record<string, unknown>;
  data?: unknown;
  signal?: AbortSignal;
  headers?: Record<string, string>;
}

export async function requestAiNew<T>(path: string, options: AiNewRequestOptions = {}): Promise<T> {
  try {
    return await request<T>(`/arte/api/ai-new${path}`, {
      ...options,
      skipErrorHandler: true,
      headers: {...(options.data === undefined ? {} : {'Content-Type': 'application/json'}), ...options.headers},
    });
  } catch (error) {
    throw normalizeApiError(error);
  }
}
