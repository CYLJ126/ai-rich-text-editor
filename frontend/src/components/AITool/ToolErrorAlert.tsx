import {Alert, Button, Space, Typography} from 'antd';
import React from 'react';
import {normalizeToolError} from '@/features/ai-tool';

export interface ToolErrorAlertProps {
  error: unknown;
  onRetry?: () => void;
  retrying?: boolean;
  showDetails?: boolean;
}

export default function ToolErrorAlert({
                                         error,
                                         onRetry,
                                         retrying = false,
                                         showDetails = false,
                                       }: ToolErrorAlertProps) {
  const normalized = normalizeToolError(error);
  const canRetry = Boolean(onRetry && normalized.retryable);
  return (
    <Alert
      type="error"
      showIcon
      message={normalized.message}
      description={
        <Space direction="vertical" size={4}>
          <Typography.Text type="secondary">
            错误码：{normalized.code} · 类型：{normalized.category}
          </Typography.Text>
          {showDetails && Object.keys(normalized.details).length > 0 && (
            <Typography.Text code>
              {JSON.stringify(normalized.details)}
            </Typography.Text>
          )}
          {canRetry && (
            <Button size="small" loading={retrying} onClick={onRetry}>
              重试
            </Button>
          )}
        </Space>
      }
    />
  );
}
