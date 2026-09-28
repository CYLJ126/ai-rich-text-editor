import type {ToolExecutionEvent, ToolExecutionEventType,} from '@/types/ai.tool.type';

export interface ToolEventPresentation {
  label: string;
  color: string;
}

export const TOOL_EVENT_PRESENTATION: Record<
  ToolExecutionEventType,
  ToolEventPresentation
> = {
  REQUESTED: {label: '收到请求', color: 'blue'},
  RESOLVED: {label: '工具解析', color: 'blue'},
  VALIDATED: {label: 'Schema 校验', color: 'blue'},
  AUTHORIZED: {label: '授权通过', color: 'green'},
  GUARDRAIL_EVALUATED: {label: 'Guardrail', color: 'cyan'},
  APPROVAL_REQUESTED: {label: '请求审批', color: 'orange'},
  APPROVAL_APPROVED: {label: '审批通过', color: 'green'},
  APPROVAL_REJECTED: {label: '审批拒绝', color: 'red'},
  APPROVAL_EXPIRED: {label: '审批过期', color: 'gray'},
  TASK_QUEUED: {label: '任务入队', color: 'blue'},
  STARTED: {label: '开始执行', color: 'blue'},
  TASK_PROGRESS_CHANGED: {label: '进度更新', color: 'cyan'},
  RETRIED: {label: '执行重试', color: 'orange'},
  SUCCEEDED: {label: '执行成功', color: 'green'},
  FAILED: {label: '执行失败', color: 'red'},
  DENIED: {label: '执行拒绝', color: 'red'},
  PAUSED: {label: '执行暂停', color: 'gray'},
  RESUMED: {label: '恢复执行', color: 'blue'},
  CANCELLED: {label: '执行取消', color: 'gray'},
  TIMED_OUT: {label: '执行超时', color: 'red'},
};

export function traceDurationMs(
  events: Pick<ToolExecutionEvent, 'occurredAt'>[],
): number | undefined {
  if (events.length < 2) return undefined;
  const timestamps = events
    .map((event) => Date.parse(event.occurredAt))
    .filter(Number.isFinite);
  if (timestamps.length < 2) return undefined;
  return Math.max(...timestamps) - Math.min(...timestamps);
}

export function formatDuration(value?: number | null): string {
  if (value === undefined || value === null || !Number.isFinite(value)) {
    return '-';
  }
  if (value < 1000) return `${Math.max(0, Math.round(value))} ms`;
  if (value < 60_000) return `${(value / 1000).toFixed(2)} s`;
  return `${(value / 60_000).toFixed(2)} min`;
}

export function formatSuccessRate(value: number): string {
  if (!Number.isFinite(value)) return '0.0%';
  return `${(Math.min(1, Math.max(0, value)) * 100).toFixed(1)}%`;
}
