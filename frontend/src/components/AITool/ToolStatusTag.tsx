import {Tag} from 'antd';
import React from 'react';
import {
  type StatusPresentation,
  TOOL_EXECUTION_MODE_PRESENTATION,
  TOOL_LIFECYCLE_PRESENTATION,
  TOOL_RESULT_PRESENTATION,
  TOOL_RISK_PRESENTATION,
  TOOL_TASK_PRESENTATION,
} from '@/features/ai-tool';
import type {
  ToolExecutionMode,
  ToolLifecycleState,
  ToolResultStatus,
  ToolRiskLevel,
  ToolTaskStatus,
} from '@/types/ai.tool.type';

type ToolStatus =
  | ToolLifecycleState
  | ToolResultStatus
  | ToolTaskStatus
  | ToolRiskLevel
  | ToolExecutionMode;

export interface ToolStatusTagProps {
  status?: ToolStatus | null;
  fallback?: string;
}

const PRESENTATIONS: Partial<Record<ToolStatus, StatusPresentation>> = {
  ...TOOL_LIFECYCLE_PRESENTATION,
  ...TOOL_RESULT_PRESENTATION,
  ...TOOL_TASK_PRESENTATION,
  ...TOOL_RISK_PRESENTATION,
  ...TOOL_EXECUTION_MODE_PRESENTATION,
};

export default function ToolStatusTag({
                                        status,
                                        fallback = '未知状态',
                                      }: ToolStatusTagProps) {
  if (!status) return <Tag>{fallback}</Tag>;
  const presentation = PRESENTATIONS[status];
  return (
    <Tag color={presentation?.color || 'default'}>
      {presentation?.label || status}
    </Tag>
  );
}
