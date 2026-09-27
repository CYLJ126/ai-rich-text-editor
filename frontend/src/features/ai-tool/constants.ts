import {AI_TOOL_MENU_CODE, AI_TOOL_OPERATION_CODES} from '@/access';
import type {
  ToolExecutionMode,
  ToolLifecycleState,
  ToolResultStatus,
  ToolRiskLevel,
  ToolTaskStatus,
} from '@/types/ai.tool.type';

export interface StatusPresentation {
  label: string;
  /** Ant Design Tag 支持预设色、状态色和自定义 CSS 颜色。 */
  color: string;
}

export {AI_TOOL_MENU_CODE, AI_TOOL_OPERATION_CODES};

export const TOOL_LIFECYCLE_PRESENTATION: Record<
  ToolLifecycleState,
  StatusPresentation
> = {
  draft: {label: '草稿', color: 'default'},
  published: {label: '已发布', color: 'success'},
  deprecated: {label: '已废弃', color: 'warning'},
  disabled: {label: '已禁用', color: 'error'},
};

export const TOOL_RESULT_PRESENTATION: Record<
  ToolResultStatus,
  StatusPresentation
> = {
  accepted: {label: '已接受', color: 'processing'},
  succeeded: {label: '执行成功', color: 'success'},
  failed: {label: '执行失败', color: 'error'},
  denied: {label: '已拒绝', color: 'error'},
  'requires-approval': {label: '等待审批', color: 'warning'},
  paused: {label: '已暂停', color: 'default'},
  cancelled: {label: '已取消', color: 'default'},
  'timed-out': {label: '已超时', color: 'error'},
};

export const TOOL_TASK_PRESENTATION: Record<
  ToolTaskStatus,
  StatusPresentation
> = {
  QUEUED: {label: '排队中', color: 'processing'},
  RUNNING: {label: '执行中', color: 'processing'},
  WAITING_APPROVAL: {label: '等待审批', color: 'warning'},
  PAUSED: {label: '已暂停', color: 'default'},
  SUCCEEDED: {label: '执行成功', color: 'success'},
  FAILED: {label: '执行失败', color: 'error'},
  CANCELLED: {label: '已取消', color: 'default'},
  TIMED_OUT: {label: '已超时', color: 'error'},
};

export const TOOL_RISK_PRESENTATION: Record<ToolRiskLevel, StatusPresentation> =
  {
    low: {label: '低风险', color: 'success'},
    medium: {label: '中风险', color: 'processing'},
    high: {label: '高风险', color: 'warning'},
    critical: {label: '严重风险', color: 'error'},
  };

export const TOOL_EXECUTION_MODE_PRESENTATION: Record<
  ToolExecutionMode,
  StatusPresentation
> = {
  blocking: {label: '同步执行', color: 'blue'},
  'non-blocking': {label: '非阻塞执行', color: 'cyan'},
  deferred: {label: '后台任务', color: 'purple'},
};

export function isTerminalToolStatus(
  status: ToolResultStatus | ToolTaskStatus,
): boolean {
  return [
    'succeeded',
    'failed',
    'denied',
    'cancelled',
    'timed-out',
    'SUCCEEDED',
    'FAILED',
    'CANCELLED',
    'TIMED_OUT',
  ].includes(status);
}
