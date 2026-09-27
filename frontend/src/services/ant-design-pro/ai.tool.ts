import {encodeToolPathPart, requestToolApi, type ToolRequestOptions,} from './ai.tool.request';
import type {
  AssistantToolCommand,
  AssistantToolConfigurationCommand,
  JsonValue,
  ModelToolDefinition,
  ResolvedToolBinding,
  RestToolInvokeRequest,
  ToolBindingCommand,
  ToolBindingView,
  ToolCatalogPage,
  ToolCatalogQuery,
  ToolDefinition,
  ToolProviderQuery,
  ToolProviderSyncResult,
  ToolProviderView,
  ToolQuery,
  ToolReference,
  ToolResult,
  ToolTaskHandle,
  ToolVersionDetailView,
  ToolVersionView,
} from '@/types/ai.tool.type';

function toolIdentityPath(reference: ToolReference): string {
  return [reference.namespace, reference.name, reference.version]
    .map(encodeToolPathPart)
    .join('/');
}

/** ----------------- ToolManagementController start ----------------- */

/** 查询工具提供者列表 GET /arte/ai/tools/providers */
export function listToolProviders(
  query: ToolProviderQuery = {},
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolProviderView[]>('/ai/tools/providers', {
    method: 'GET',
    params: {...query},
    ...options,
  });
}

/** 查询工具提供者详情 GET /arte/ai/tools/providers/{providerId} */
export function getToolProvider(
  providerId: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolProviderView | null>(
    `/ai/tools/providers/${encodeToolPathPart(providerId)}`,
    {method: 'GET', ...options},
  );
}

/** 查询管理态工具目录 GET /arte/ai/tools/catalog */
export function listToolCatalog(
  query: ToolCatalogQuery = {},
  options?: ToolRequestOptions,
) {
  const {pageSize, ...params} = query;
  return requestToolApi<ToolCatalogPage>('/ai/tools/catalog', {
    method: 'GET',
    params: {...params, size: pageSize},
    ...options,
  });
}

/** 同步单个工具提供者 POST /arte/ai/tools/providers/{providerId}/synchronize */
export function synchronizeToolProvider(
  providerId: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolProviderSyncResult>(
    `/ai/tools/providers/${encodeToolPathPart(providerId)}/synchronize`,
    {method: 'POST', ...options},
  );
}

/** 同步全部工具提供者 POST /arte/ai/tools/providers/synchronize */
export function synchronizeAllToolProviders(options?: ToolRequestOptions) {
  return requestToolApi<ToolProviderSyncResult[]>(
    '/ai/tools/providers/synchronize',
    {method: 'POST', ...options},
  );
}

/** 启用工具提供者 POST /arte/ai/tools/providers/{providerId}/enable */
export function enableToolProvider(
  providerId: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<boolean>(
    `/ai/tools/providers/${encodeToolPathPart(providerId)}/enable`,
    {method: 'POST', ...options},
  );
}

/** 禁用工具提供者 POST /arte/ai/tools/providers/{providerId}/disable */
export function disableToolProvider(
  providerId: string,
  reason?: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<boolean>(
    `/ai/tools/providers/${encodeToolPathPart(providerId)}/disable`,
    {method: 'POST', params: {reason}, ...options},
  );
}

/** 发布工具版本 POST /arte/ai/tools/{namespace}/{name}/{version}/publish */
export function publishToolVersion(
  reference: ToolReference,
  options?: ToolRequestOptions,
) {
  return requestToolApi<boolean>(
    `/ai/tools/${toolIdentityPath(reference)}/publish`,
    {method: 'POST', ...options},
  );
}

/** 废弃工具版本 POST /arte/ai/tools/{namespace}/{name}/{version}/deprecate */
export function deprecateToolVersion(
  reference: ToolReference,
  reason?: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<boolean>(
    `/ai/tools/${toolIdentityPath(reference)}/deprecate`,
    {method: 'POST', params: {reason}, ...options},
  );
}

/** 禁用工具版本 POST /arte/ai/tools/{namespace}/{name}/{version}/disable */
export function disableToolVersion(
  reference: ToolReference,
  reason?: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<boolean>(
    `/ai/tools/${toolIdentityPath(reference)}/disable`,
    {method: 'POST', params: {reason}, ...options},
  );
}

/** 搜索当前可见工具 POST /arte/ai/tools/search */
export function searchTools(
  query: ToolQuery = {},
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolDefinition[]>('/ai/tools/search', {
    method: 'POST',
    data: query,
    ...options,
  });
}

/** 精确解析工具版本 GET /arte/ai/tools/{namespace}/{name}/{version} */
export function getToolDefinition(
  reference: ToolReference,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolDefinition | null>(
    `/ai/tools/${toolIdentityPath(reference)}`,
    {method: 'GET', ...options},
  );
}

/** 查询管理态工具版本详情 GET /arte/ai/tools/{namespace}/{name}/{version}/management */
export function getToolVersionDetail(
  reference: ToolReference,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolVersionDetailView | null>(
    `/ai/tools/${toolIdentityPath(reference)}/management`,
    {method: 'GET', ...options},
  );
}

/** 查询工具全部版本 GET /arte/ai/tools/{namespace}/{name}/versions */
export function listToolVersions(
  namespace: string,
  name: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolVersionView[]>(
    `/ai/tools/${encodeToolPathPart(namespace)}/${encodeToolPathPart(name)}/versions`,
    {method: 'GET', ...options},
  );
}

/** ----------------- ToolManagementController end ----------------- */

/** ----------------- ToolBindingController start ----------------- */

/** 保存当前用户工具绑定 POST /arte/ai/tool-bindings */
export function saveToolBinding(
  command: ToolBindingCommand,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ResolvedToolBinding>('/ai/tool-bindings', {
    method: 'POST',
    data: command,
    ...options,
  });
}

/** 查询当前用户工具绑定 GET /arte/ai/tool-bindings */
export function listToolBindings(
  workspaceId?: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolBindingView[]>('/ai/tool-bindings', {
    method: 'GET',
    params: {workspaceId},
    ...options,
  });
}

/** ----------------- ToolBindingController end ----------------- */

/** ----------------- AssistantToolController start ----------------- */

/** 替换助手的工具配置 POST /arte/ai/assistants/{assistantId}/tools */
export function replaceAssistantTools(
  assistantId: number,
  command: AssistantToolConfigurationCommand,
  options?: ToolRequestOptions,
) {
  return requestToolApi<boolean>(`/ai/assistants/${assistantId}/tools`, {
    method: 'POST',
    data: command,
    ...options,
  });
}

/** 查询助手工具配置 GET /arte/ai/assistants/{assistantId}/tools */
export function getAssistantToolConfiguration(
  assistantId: number,
  options?: ToolRequestOptions,
) {
  return requestToolApi<AssistantToolCommand[]>(
    `/ai/assistants/${assistantId}/tools`,
    {method: 'GET', ...options},
  );
}

/** 查询供模型使用的工具定义 GET /arte/ai/assistants/{assistantId}/tools/definitions */
export function getAssistantToolDefinitions(
  assistantId: number,
  workspaceId?: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ModelToolDefinition[]>(
    `/ai/assistants/${assistantId}/tools/definitions`,
    {method: 'GET', params: {workspaceId}, ...options},
  );
}

/** ----------------- AssistantToolController end ----------------- */

/** ----------------- ToolGatewayController start ----------------- */

/** 统一调用工具 POST /arte/ai/tools/invoke */
export function invokeTool<TOutput = JsonValue>(
  request: RestToolInvokeRequest,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolResult<TOutput>>('/ai/tools/invoke', {
    method: 'POST',
    data: request,
    ...options,
  });
}

/** 查询异步任务状态 GET /arte/ai/tools/tasks/{taskId} */
export function getToolTask(taskId: string, options?: ToolRequestOptions) {
  return requestToolApi<ToolTaskHandle | null>(
    `/ai/tools/tasks/${encodeToolPathPart(taskId)}`,
    {method: 'GET', ...options},
  );
}

/** 查询异步任务结果 GET /arte/ai/tools/tasks/{taskId}/result */
export function getToolTaskResult<TOutput = JsonValue>(
  taskId: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolResult<TOutput> | null>(
    `/ai/tools/tasks/${encodeToolPathPart(taskId)}/result`,
    {method: 'GET', ...options},
  );
}

/** 取消工具调用 POST /arte/ai/tools/calls/{callId}/cancel */
export function cancelToolCall(
  callId: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<boolean>(
    `/ai/tools/calls/${encodeToolPathPart(callId)}/cancel`,
    {method: 'POST', ...options},
  );
}

/** 恢复暂停的工具调用 POST /arte/ai/tools/resume */
export function resumeTool<TOutput = JsonValue>(
  resumeToken: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolResult<TOutput>>('/ai/tools/resume', {
    method: 'POST',
    data: {resumeToken},
    ...options,
  });
}

/** ----------------- ToolGatewayController end ----------------- */

export type {ToolRequestOptions} from './ai.tool.request';
