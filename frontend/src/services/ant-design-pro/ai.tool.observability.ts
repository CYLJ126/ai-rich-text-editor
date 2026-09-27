import {encodeToolPathPart, requestToolApi, type ToolRequestOptions,} from './ai.tool.request';
import type {ToolCallDetail, ToolCallStatistics, ToolExecutionTrace,} from '@/types/ai.tool.type';

export interface ToolCallQuery {
  toolId?: string;
  status?: string;
  limit?: number;
}

/** 查询工具调用明细 GET /arte/ai/tool-observability/calls */
export function listToolCalls(
  query: ToolCallQuery = {},
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolCallDetail[]>('/ai/tool-observability/calls', {
    method: 'GET',
    params: {...query},
    ...options,
  });
}

/** 查询单次工具调用 GET /arte/ai/tool-observability/calls/{callId} */
export function getToolCall(callId: string, options?: ToolRequestOptions) {
  return requestToolApi<ToolCallDetail | null>(
    `/ai/tool-observability/calls/${encodeToolPathPart(callId)}`,
    {method: 'GET', ...options},
  );
}

/** 查询一期实时统计 GET /arte/ai/tool-observability/statistics */
export function getToolCallStatistics(
  toolId?: string,
  limit = 500,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolCallStatistics>(
    '/ai/tool-observability/statistics',
    {method: 'GET', params: {toolId, limit}, ...options},
  );
}

/** 查询调用轨迹 GET /arte/ai/tool-observability/traces/{traceId} */
export function getToolTrace(
  traceId: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolExecutionTrace | null>(
    `/ai/tool-observability/traces/${encodeToolPathPart(traceId)}`,
    {method: 'GET', ...options},
  );
}
