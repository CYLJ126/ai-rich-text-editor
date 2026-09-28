import {Button, Card, Descriptions, Empty, Space, Typography} from 'antd';
import dayjs from 'dayjs';
import React from 'react';
import {toolErrorFromPayload, toolReferenceLabel} from '@/features/ai-tool';
import type {ToolResult} from '@/types/ai.tool.type';
import {JsonEditor} from '../JsonEditor';
import ToolErrorAlert from './ToolErrorAlert';
import ToolStatusTag from './ToolStatusTag';

export interface ToolResultViewProps {
  result?: ToolResult | null;
  loading?: boolean;
  onOpenTask?: (taskId: string) => void;
  onResume?: (resumeToken: string) => void;
  resuming?: boolean;
}

export default function ToolResultView({
                                         result,
                                         loading = false,
                                         onOpenTask,
                                         onResume,
                                         resuming = false,
                                       }: ToolResultViewProps) {
  if (!result) {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={loading ? '正在等待结果…' : '暂无执行结果'}
      />
    );
  }

  if (result.status === 'accepted') {
    const {taskHandle} = result;
    return (
      <Card
        size="small"
        title={
          <Space>
            <ToolStatusTag status={result.status}/>
            后台任务已创建
          </Space>
        }
      >
        <Descriptions column={1} size="small">
          <Descriptions.Item label="任务 ID">
            <Typography.Text copyable>{taskHandle.taskId}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="调用 ID">
            <Typography.Text copyable>{taskHandle.callId}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="固定工具版本">
            {toolReferenceLabel(taskHandle.tool)}
          </Descriptions.Item>
          <Descriptions.Item label="任务状态">
            <ToolStatusTag status={taskHandle.status}/>
          </Descriptions.Item>
        </Descriptions>
        {onOpenTask && (
          <Button type="primary" onClick={() => onOpenTask(taskHandle.taskId)}>
            查看任务
          </Button>
        )}
      </Card>
    );
  }

  if (result.status === 'requires-approval' || result.status === 'paused') {
    const taskId =
      typeof result.metadata.taskId === 'string'
        ? result.metadata.taskId
        : undefined;
    return (
      <Card
        size="small"
        title={
          <Space>
            <ToolStatusTag status={result.status}/>
            {result.status === 'requires-approval'
              ? '调用等待人工审批'
              : '调用已暂停'}
          </Space>
        }
      >
        <Space direction="vertical" style={{width: '100%'}}>
          {result.approvalRequestId && (
            <Typography.Text>
              审批请求 ID：
              <Typography.Text copyable>
                {result.approvalRequestId}
              </Typography.Text>
            </Typography.Text>
          )}
          {taskId && (
            <Typography.Text>
              任务 ID：<Typography.Text copyable>{taskId}</Typography.Text>
            </Typography.Text>
          )}
          <Typography.Text type="secondary">
            恢复令牌只在创建暂停任务时返回一次，请勿写入日志或分享给其他用户。
          </Typography.Text>
          <Space>
            {taskId && onOpenTask && (
              <Button onClick={() => onOpenTask(taskId)}>查看任务</Button>
            )}
            {onResume && (
              <Button
                type="primary"
                loading={resuming}
                onClick={() => onResume(result.resumeToken)}
              >
                {result.status === 'requires-approval'
                  ? '审批通过后恢复'
                  : '使用令牌恢复'}
              </Button>
            )}
          </Space>
        </Space>
      </Card>
    );
  }

  if (
    result.status === 'failed' ||
    result.status === 'denied' ||
    result.status === 'cancelled' ||
    result.status === 'timed-out'
  ) {
    return (
      <Space direction="vertical" style={{width: '100%'}}>
        <ToolStatusTag status={result.status}/>
        <ToolErrorAlert
          error={toolErrorFromPayload(result.error)}
          showDetails
        />
        {result.usage && <UsageDescription usage={result.usage}/>}
      </Space>
    );
  }

  if (result.status !== 'succeeded') return null;

  return (
    <Space direction="vertical" size="middle" style={{width: '100%'}}>
      <Space>
        <ToolStatusTag status={result.status}/>
        <Typography.Text type="secondary">
          工具已返回并通过输出校验
        </Typography.Text>
      </Space>
      <Card size="small" title="结构化输出">
        <JsonEditor value={result.output} readOnly height={280}/>
      </Card>
      {result.content.length > 0 && (
        <Card size="small" title={`内容片段（${result.content.length}）`}>
          <JsonEditor value={result.content} readOnly height={220}/>
        </Card>
      )}
      {result.artifacts.length > 0 && (
        <Card size="small" title={`产物（${result.artifacts.length}）`}>
          <JsonEditor value={result.artifacts} readOnly height={180}/>
        </Card>
      )}
      {result.usage && <UsageDescription usage={result.usage}/>}
    </Space>
  );
}

function UsageDescription({
                            usage,
                          }: {
  usage: NonNullable<Extract<ToolResult, { status: 'succeeded' }>['usage']>;
}) {
  return (
    <Descriptions size="small" bordered column={{xs: 1, sm: 2, lg: 3}}>
      <Descriptions.Item label="开始时间">
        {formatTime(usage.startedAt)}
      </Descriptions.Item>
      <Descriptions.Item label="完成时间">
        {formatTime(usage.completedAt)}
      </Descriptions.Item>
      <Descriptions.Item label="耗时">{usage.duration}</Descriptions.Item>
      <Descriptions.Item label="输入 Token">
        {usage.inputTokens ?? '-'}
      </Descriptions.Item>
      <Descriptions.Item label="输出 Token">
        {usage.outputTokens ?? '-'}
      </Descriptions.Item>
      <Descriptions.Item label="费用">
        {usage.cost === undefined
          ? '-'
          : `${usage.cost} ${usage.currency || ''}`.trim()}
      </Descriptions.Item>
    </Descriptions>
  );
}

function formatTime(value: string): string {
  const time = dayjs(value);
  return time.isValid() ? time.format('YYYY-MM-DD HH:mm:ss') : value;
}
