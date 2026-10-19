import {type AiRequestOptions, postResult, requireIdempotencyKey} from './request';
import type {AiScope, ControlReceipt, DefinitionRef, ExecutionError} from './types';
import {parseConfiguration} from './toolJson';
import type {ToolSchema} from './tool';

export interface ToolInvocationPreview {
  planDigest: string;
  binding: DefinitionRef;
  revision: DefinitionRef;
  tool: DefinitionRef;
  title: string;
  argumentsJson: string;
  configurationJson: string;
  inputSchema: ToolSchema;
  timeoutMillis: number;
  maxAttempts: number;
  maxInputBytes: number;
  maxOutputBytes: number;
  approvalMode: 'NONE';
  billingMode: 'FREE';
  costDescription: string;
}

export interface ToolAccepted {
  executionId: string;
  kind: 'INVOCATION';
  acceptedAt: string;
}

export interface ToolResult {
  callId: string;
  outcome: 'SUCCEEDED' | 'FAILED' | 'UNKNOWN';
  sideEffect: 'NONE' | 'POSSIBLE' | 'CONFIRMED';
  error: ExecutionError | null;
  sources: unknown[];
  artifacts: unknown[];
}

/** 参数以经过有界校验的原文嵌入；禁止透传 owner、预算、权限或运行时对象。 */
export function toolInvocationRequest(scope: AiScope, bindingId: string, argumentsJson: string, timeoutSeconds: number) {
  parseConfiguration(argumentsJson, 'arguments');
  if (!bindingId || !Number.isInteger(timeoutSeconds) || timeoutSeconds < 1 || timeoutSeconds > 3600)
    throw new Error('调用期限须为 1～3600 秒。');
  return `${JSON.stringify({scope}).slice(0, -1)},"input":${JSON.stringify({
    bindingId,
    timeoutSeconds
  }).slice(0, -1)},"arguments":${argumentsJson}}}`;
}

export function confirmedToolRequest(requestJson: string, digest: string) {
  if (!/^[0-9a-f]{64}$/.test(digest)) throw new Error('预览方案无效，请重新预览。');
  return `${requestJson.slice(0, -1)},"expectedPlanDigest":${JSON.stringify(digest)}}`;
}

export const previewToolInvocation = (requestJson: string, options?: AiRequestOptions) =>
  postResult<ToolInvocationPreview>('tool/previewInvocation', requestJson, options);
export const invokeTool = (requestJson: string, key: string, options?: AiRequestOptions) =>
  postResult<ToolAccepted>('tool/invokeTool', requestJson, options, requireIdempotencyKey(key));
export const cancelToolInvocation = (scope: AiScope, invocationId: string, key: string, options?: AiRequestOptions) =>
  postResult<ControlReceipt>('tool/cancelToolInvocation', {scope, invocationId}, options, requireIdempotencyKey(key));
