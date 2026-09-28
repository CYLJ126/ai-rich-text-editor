import type {ToolResult, ToolTaskHandle, ToolTaskStatus,} from '@/types/ai.tool.type';

const POLLING_TASK_STATUSES = new Set<ToolTaskStatus>([
  'QUEUED',
  'RUNNING',
  'WAITING_APPROVAL',
  'PAUSED',
]);

export function createIdempotencyKey(): string {
  if (
    typeof crypto !== 'undefined' &&
    typeof crypto.randomUUID === 'function'
  ) {
    return crypto.randomUUID();
  }
  return `tool-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
}

export function shouldPollToolTask(status?: ToolTaskStatus | null): boolean {
  return Boolean(status && POLLING_TASK_STATUSES.has(status));
}

export function toolTaskProgressPercent(progress?: number | null): number {
  if (
    progress === undefined ||
    progress === null ||
    !Number.isFinite(progress)
  ) {
    return 0;
  }
  return Math.round(Math.max(0, Math.min(1, progress)) * 100);
}

export function taskFromToolResult(
  result?: ToolResult | null,
): ToolTaskHandle | undefined {
  return result?.status === 'accepted' ? result.taskHandle : undefined;
}
