import type {
  CompiledWorkflow,
  ToolResumeRequest,
  WorkflowDraftRequest,
  WorkflowRunActionResult,
  WorkflowRunDetailView,
  WorkflowRunPage,
  WorkflowRunStatus,
  WorkflowStartRequest,
  WorkflowSummaryPage,
  WorkflowValidationResult,
  WorkflowVersionView,
} from '@/types/ai.tool.type';
import {encodeToolPathPart, requestToolApi, type ToolRequestOptions,} from './ai.tool.request';

export interface WorkflowListQuery {
  keyword?: string;
  status?: string;
  current?: number;
  pageSize?: number;
}

export interface WorkflowRunQuery {
  workflowId?: string;
  status?: WorkflowRunStatus;
  current?: number;
  pageSize?: number;
}

/** 查询当前用户工作流目录。 */
export function listWorkflows(
  query: WorkflowListQuery = {},
  options?: ToolRequestOptions,
) {
  const {pageSize, ...params} = query;
  return requestToolApi<WorkflowSummaryPage>('/ai/workflows', {
    method: 'GET',
    params: {...params, size: pageSize},
    ...options,
  });
}

/** 新建或基于 rowVersion 更新草稿。 */
export function saveWorkflowDraft(
  draft: WorkflowDraftRequest,
  options?: ToolRequestOptions,
) {
  return requestToolApi<WorkflowVersionView>('/ai/workflows/drafts', {
    method: 'POST',
    data: draft,
    ...options,
  });
}

export function validateWorkflow(
  draft: WorkflowDraftRequest,
  options?: ToolRequestOptions,
) {
  return requestToolApi<WorkflowValidationResult>('/ai/workflows/validate', {
    method: 'POST',
    data: draft,
    ...options,
  });
}

export function listWorkflowVersions(
  workflowId: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<WorkflowVersionView[]>(
    `/ai/workflows/${encodeToolPathPart(workflowId)}/versions`,
    {method: 'GET', ...options},
  );
}

export function getWorkflowVersion(
  workflowId: string,
  version: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<WorkflowVersionView | null>(
    `/ai/workflows/${encodeToolPathPart(workflowId)}/versions/${encodeToolPathPart(version)}`,
    {method: 'GET', ...options},
  );
}

export function publishWorkflowVersion(
  workflowId: string,
  version: string,
  expectedRowVersion: number,
  options?: ToolRequestOptions,
) {
  return requestToolApi<CompiledWorkflow>(
    `/ai/workflows/${encodeToolPathPart(workflowId)}/versions/${encodeToolPathPart(version)}/publish`,
    {method: 'POST', params: {expectedRowVersion}, ...options},
  );
}

export function startWorkflowRun(
  request: WorkflowStartRequest,
  options?: ToolRequestOptions,
) {
  return requestToolApi<WorkflowRunActionResult>('/ai/workflows/runs', {
    method: 'POST',
    data: request,
    ...options,
  });
}

export function listWorkflowRuns(
  query: WorkflowRunQuery = {},
  options?: ToolRequestOptions,
) {
  const {pageSize, ...params} = query;
  return requestToolApi<WorkflowRunPage>('/ai/workflows/runs', {
    method: 'GET',
    params: {...params, size: pageSize},
    ...options,
  });
}

export function getWorkflowRun(
  runId: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<WorkflowRunDetailView | null>(
    `/ai/workflows/runs/${encodeToolPathPart(runId)}`,
    {method: 'GET', ...options},
  );
}

export function cancelWorkflowRun(
  runId: string,
  options?: ToolRequestOptions,
) {
  return requestToolApi<WorkflowRunActionResult>(
    `/ai/workflows/runs/${encodeToolPathPart(runId)}/cancel`,
    {method: 'POST', ...options},
  );
}

export function resumeWorkflowRun(
  request: ToolResumeRequest,
  options?: ToolRequestOptions,
) {
  return requestToolApi<WorkflowRunActionResult>('/ai/workflows/runs/resume', {
    method: 'POST',
    data: request,
    ...options,
  });
}
