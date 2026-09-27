import type {JsonObject, ToolApiEnvelope, ToolErrorCategory, ToolErrorPayload,} from '@/types/ai.tool.type';

export interface ToolApiErrorOptions {
  code?: string;
  category?: ToolErrorCategory;
  retryable?: boolean;
  details?: JsonObject;
  cause?: unknown;
}

export class ToolApiError extends Error {
  readonly code: string;
  readonly category: ToolErrorCategory;
  readonly retryable: boolean;
  readonly details: JsonObject;

  constructor(message: string, options: ToolApiErrorOptions = {}) {
    super(message || '工具服务请求失败', {cause: options.cause});
    this.name = 'ToolApiError';
    this.code = options.code || 'TOOL_REQUEST_FAILED';
    this.category = options.category || 'INTERNAL';
    this.retryable = options.retryable ?? false;
    this.details = options.details || {};
  }
}

export function createToolApiError(
  response: Pick<ToolApiEnvelope<unknown>, 'code' | 'desc'>,
): ToolApiError {
  return new ToolApiError(response.desc || '工具服务请求失败', {
    code: response.code,
    category: inferErrorCategory(response.code),
    retryable: isRetryableToolError(response.code),
  });
}

export function toolErrorFromPayload(payload: ToolErrorPayload): ToolApiError {
  return new ToolApiError(payload.message, payload);
}

export function normalizeToolError(error: unknown): ToolApiError {
  if (error instanceof ToolApiError) return error;
  if (error instanceof Error) {
    return new ToolApiError(error.message, {cause: error});
  }
  return new ToolApiError('未知工具服务错误', {
    details: {rawError: String(error)},
  });
}

export function isRetryableToolError(code?: string): boolean {
  if (!code) return false;
  const normalized = code.toUpperCase();
  return (
    normalized.includes('TIMEOUT') ||
    normalized.includes('RATE_LIMIT') ||
    normalized.includes('REMOTE') ||
    normalized.includes('DEPENDENCY') ||
    normalized.includes('UNAVAILABLE')
  );
}

function inferErrorCategory(code?: string): ToolErrorCategory {
  const normalized = code?.toUpperCase() || '';
  if (normalized.includes('VALID')) return 'VALIDATION';
  if (normalized.includes('AUTHENTIC')) return 'AUTHENTICATION';
  if (normalized.includes('AUTHORIZ') || normalized.includes('UNAUTHORIZED')) {
    return 'AUTHORIZATION';
  }
  if (normalized.includes('APPROVAL')) return 'APPROVAL';
  if (normalized.includes('RATE')) return 'RATE_LIMIT';
  if (normalized.includes('TIMEOUT')) return 'TIMEOUT';
  if (normalized.includes('CANCEL')) return 'CANCELLED';
  if (normalized.includes('CONFLICT') || normalized.includes('DUPLICATE')) {
    return 'CONFLICT';
  }
  if (normalized.includes('REMOTE') || normalized.includes('DEPENDENCY')) {
    return 'DEPENDENCY';
  }
  if (normalized.includes('GUARDRAIL') || normalized.includes('POLICY')) {
    return 'POLICY';
  }
  return 'INTERNAL';
}
