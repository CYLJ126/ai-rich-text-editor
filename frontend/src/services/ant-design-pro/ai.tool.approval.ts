import {encodeToolPathPart, requestToolApi, type ToolRequestOptions,} from './ai.tool.request';
import type {ApprovalDecisionRequest, ToolApprovalRecord,} from '@/types/ai.tool.type';

export type ToolApprovalStatus =
  | 'pending'
  | 'approved'
  | 'rejected'
  | 'expired'
  | 'all';

/** 查询当前用户审批记录 GET /arte/ai/tool-approvals */
export function listToolApprovals(
  status: ToolApprovalStatus = 'pending',
  limit = 100,
  options?: ToolRequestOptions,
) {
  return requestToolApi<ToolApprovalRecord[]>('/ai/tool-approvals', {
    method: 'GET',
    params: {status, limit},
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

