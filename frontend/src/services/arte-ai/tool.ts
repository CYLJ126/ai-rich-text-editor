import {type AiRequestOptions, postResult, requireIdempotencyKey} from './request';
import type {AiScope, DefinitionRef} from './types';
import {parseConfiguration} from './toolJson';

export interface ToolPage<T> {
  records: T[];
  nextCursor: string | null;
  scanned: number;
  exhausted: boolean
}

export interface ToolPolicy {
  timeoutMillis: number;
  maxAttempts: number;
  retryBackoffMillis: number;
  maxInputBytes: number;
  maxOutputBytes: number;
  approvalMode: 'NONE' | 'REQUESTER_CONFIRMATION' | 'INDEPENDENT_APPROVAL';
}

export interface ToolSummary {
  tool: DefinitionRef;
  provider: DefinitionRef;
  title: string;
  description: string;
  tags: string[];
  features: string[];
  outcomeQuerySupported: boolean;
  risk: {
    sideEffect: string;
    destructive: boolean;
    idempotency: string;
    requiredScopes: string[];
    networkOrigins: string[]
  };
  defaultPolicy: ToolPolicy;
  release: {
    state: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED' | 'DISABLED';
    version: string;
    permitsNewBinding: boolean;
    existingBindingsUntil: string | null
  };
}

export interface ToolSchema {
  definition: DefinitionRef;
  document: Record<string, unknown>;
  documentJson: string;
  checksum: string
}

export interface ToolDetail {
  tool: ToolSummary;
  inputSchema: ToolSchema;
  outputSchema: ToolSchema;
  configurationSchema: ToolSchema
}

export interface ToolBinding {
  bindingId: string;
  toolId: string;
  version: string;
  name: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string
}

export interface ToolSettings {
  tool: DefinitionRef;
  versionPolicy: 'PINNED' | 'FOLLOW_COMPATIBLE';
  name: string;
  enabled: boolean;
  configurationJson: string;
  secrets: Record<string, DefinitionRef>;
  policy: ToolPolicy | null;
  networkOrigins: string[] | null;
}

export interface ToolRevision {
  revision: DefinitionRef;
  settings: ToolSettings;
  createdAt: string
}

export interface ToolBindingDetail {
  binding: ToolBinding;
  revision: ToolRevision
}

export interface ToolBindingCommand {
  delivery: 'APPLIED' | 'REPLAYED';
  commandId: string;
  bindingId: string;
  outcome: string;
  rejection: string;
  binding: ToolBinding;
  currentVersion: string;
  recordedAt: string;
}

export interface ToolUpgrade {
  bindingId: string;
  expectedVersion: string;
  currentBaseline: DefinitionRef;
  currentTool: DefinitionRef;
  compatible: boolean;
  changes: string[];
  valid: boolean;
  rejection: string;
  digest: string | null;
  proposed: { tool: DefinitionRef; policy: ToolPolicy; configurationJson: string } | null;
}

export interface ToolBindingAudit {
  receipt: ToolBindingCommand;
  action: string;
  reason: string
}

export type ToolWritePath = 'prepareDefaultBinding' | 'saveBinding' | 'setBindingState' | 'upgradeBinding';
const version = /^(0|[1-9][0-9]{0,18})$/;
export const toolRefKey = (ref: DefinitionRef) => JSON.stringify([ref.type, ref.id, ref.version]);
const read = <T>(path: string, data: unknown, options?: AiRequestOptions) => postResult<T>(`tool/${path}`, data, options);

export interface ToolFilters {
  query?: string;
  tag?: string;
  providerId?: string;
  state?: string
}

export const listTools = (scope: AiScope, cursor: string | null, options?: AiRequestOptions, filters?: ToolFilters) => read<ToolPage<ToolSummary>>('listTools', {
  scope,
  cursor,
  size: 30, ...filters
}, options);
export const getTool = (scope: AiScope, tool: DefinitionRef, options?: AiRequestOptions) => read<ToolDetail>('getTool', {
  scope,
  tool
}, options);
export const listToolBindings = (scope: AiScope, cursor: string | null, options?: AiRequestOptions) => read<ToolPage<ToolBinding>>('listBindings', {
  scope,
  cursor,
  size: 30
}, options);
export const getToolBinding = (scope: AiScope, bindingId: string, options?: AiRequestOptions) => read<ToolBindingDetail>('getBinding', {
  scope,
  bindingId
}, options);
export const getToolBindingHistory = (scope: AiScope, bindingId: string, cursor: string | null, options?: AiRequestOptions) => read<ToolPage<ToolBindingAudit>>('getBindingHistory', {
  scope,
  bindingId,
  cursor,
  size: 20
}, options);

/** 手工嵌入已严格校验的 JSON 对象原文；其他字段始终用 JSON.stringify 编码。 */
export function toolSettingsJson(settings: ToolSettings): string {
  parseConfiguration(settings.configurationJson);
  // 响应还保留旧 configuration 对象；只序列化请求白名单，避免重复键或意外 double 回写。
  const fields = {
    tool: settings.tool, versionPolicy: settings.versionPolicy, name: settings.name, enabled: settings.enabled,
    secrets: settings.secrets, policy: settings.policy, networkOrigins: settings.networkOrigins
  };
  return `${JSON.stringify(fields).slice(0, -1)},"configuration":${settings.configurationJson}}`;
}

export function bindingRequest(scope: AiScope, fields: {
  bindingId?: string;
  expectedVersion: string;
  reason?: string;
  settings: ToolSettings;
  expectedPreviewDigest?: string;
  acceptIncompatible?: boolean
}) {
  if (!version.test(fields.expectedVersion)) throw new Error('配置版本无效，请重新读取。');
  const {settings, ...rest} = fields;
  return `${JSON.stringify({scope, ...rest}).slice(0, -1)},"settings":${toolSettingsJson(settings)}}`;
}

export function defaultBindingRequest(scope: AiScope, tool: DefinitionRef, configurationJson: string) {
  parseConfiguration(configurationJson);
  return `${JSON.stringify({scope, tool}).slice(0, -1)},"configuration":${configurationJson}}`;
}

export const previewToolUpgrade = (requestJson: string, options?: AiRequestOptions) => read<ToolUpgrade>('previewBindingUpgrade', requestJson, options);

export function writeToolBinding(path: ToolWritePath, requestJson: string, key: string, options?: AiRequestOptions) {
  requireIdempotencyKey(key);
  return postResult<ToolBindingCommand>(`tool/${path}`, requestJson, options, key);
}
