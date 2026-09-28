import {encodeToolPathPart, requestToolApi, type ToolRequestOptions,} from './ai.tool.request';
import type {
  ApprovalDecisionRequest,
  ToolApprovalPage,
  ToolApprovalQuery,
  ToolApprovalRecord,
  ToolGuardrailView,
} from '@/types/ai.tool.type';

/** 查询当前用户审批记录 GET /arte/ai/tool-approvals */
export function listToolApprovals(
  query: ToolApprovalQuery = {},
  options?: ToolRequestOptions,
) {
  const {pageSize, ...params} = query;
  return requestToolApi<ToolApprovalPage>('/ai/tool-approvals', {
    method: 'GET',
    params: {...params, size: pageSize},
    ...options,
  });
}

/** 查询服务端固定 Guardrail 执行链 GET /arte/ai/tool-security/guardrails */
export function listToolGuardrails(options?: ToolRequestOptions) {
  return requestToolApi<ToolGuardrailView[]>('/ai/tool-security/guardrails', {
    method: 'GET',
    ...options,
  });
}

/** 查询当前用户审批详情 GET /arte/ai/tool-approvals/{requestId} */
export function getToolApproval(
  requestId: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolApprovalRecord | null>(
    `/ai/tool-approvals/${encodeToolPathPart(requestId)}`,
    {method: 'GET', ...options},
  );
}

/** 审批工具调用 POST /arte/ai/tool-approvals/{requestId}/decision */
export function decideToolApproval(
  requestId: string,
  decision: ApprovalDecisionRequest,
  options?: ToolRequestOptions,
) {
  return requestToolApi<void>(
    `/ai/tool-approvals/${encodeToolPathPart(requestId)}/decision`,
    {method: 'POST', data: decision, ...options},
  );
}
