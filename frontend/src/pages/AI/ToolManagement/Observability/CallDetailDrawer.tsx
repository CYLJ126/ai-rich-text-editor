import {ReloadOutlined} from '@ant-design/icons';
import {Alert, Button, Card, Descriptions, Drawer, Empty, Space, Spin, Tabs, Typography,} from 'antd';
import dayjs from 'dayjs';
import React, {useCallback, useEffect, useRef, useState} from 'react';
import {JsonEditor} from '@/components';
import {ToolErrorAlert, ToolStatusTag} from '@/components/AITool';
import {formatDuration} from '@/features/ai-tool';
import {getToolCall, getToolTrace,} from '@/services/ant-design-pro/ai.tool.observability';
import type {ToolCallDetail, ToolCallResultRecord, ToolExecutionTrace,} from '@/types/ai.tool.type';
import TraceTimeline from './TraceTimeline';

export interface CallDetailDrawerProps {
  callId?: string;
  open: boolean;
  onClose: () => void;
}

export default function CallDetailDrawer({
                                           callId,
                                           open,
                                           onClose,
                                         }: CallDetailDrawerProps) {
  const sequence = useRef(0);
  const [detail, setDetail] = useState<ToolCallDetail>();
  const [trace, setTrace] = useState<ToolExecutionTrace>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>();
  const [traceError, setTraceError] = useState<unknown>();

  const load = useCallback(async () => {
    if (!callId || !open) return;
    const currentSequence = ++sequence.current;
    setLoading(true);
    setError(undefined);
    setTraceError(undefined);
    try {
      const nextDetail = await getToolCall(callId);
      if (!nextDetail) throw new Error('调用不存在，或当前用户无权查看');
      if (sequence.current !== currentSequence) return;
      setDetail(nextDetail);
      try {
        const nextTrace = await getToolTrace(nextDetail.call.traceId);
        if (sequence.current === currentSequence) {
          setTrace(nextTrace || undefined);
        }
      } catch (nextTraceError) {
        if (sequence.current === currentSequence) {
          setTraceError(nextTraceError);
        }
      }
    } catch (nextError) {
      if (sequence.current === currentSequence) setError(nextError);
    } finally {
      if (sequence.current === currentSequence) setLoading(false);
    }
  }, [callId, open]);

  useEffect(() => {
    if (!open) {
      sequence.current += 1;
      return;
    }
    setDetail(undefined);
    setTrace(undefined);
    load().then();
  }, [load, open]);

  return (
    <Drawer
      title="工具调用与轨迹详情"
      open={open}
      width={900}
      destroyOnHidden
      onClose={onClose}
      extra={
        <Button icon={<ReloadOutlined/>} loading={loading} onClick={load}>
          刷新
        </Button>
      }
    >
      <Spin spinning={loading}>
        {Boolean(error) && <ToolErrorAlert error={error} showDetails/>}
        {detail && (
          <Tabs
            items={[
              {
                key: 'overview',
                label: '调用概览',
                children: <CallOverview detail={detail}/>,
              },
              {
                key: 'snapshots',
                label: '安全快照',
                children: <CallSnapshots detail={detail}/>,
              },
              {
                key: 'result',
                label: '调用结果',
                children: <CallResult result={detail.result}/>,
              },
              {
                key: 'trace',
                label: `事件轨迹${trace ? `（${trace.events.length}）` : ''}`,
                children: traceError ? (
                  <ToolErrorAlert error={traceError} showDetails/>
                ) : (
                  <TraceTimeline trace={trace}/>
                ),
              },
            ]}
          />
        )}
      </Spin>
    </Drawer>
  );
}

function CallOverview({detail}: { detail: ToolCallDetail }) {
  const {call} = detail;
  return (
    <Space direction="vertical" size="middle" style={{width: '100%'}}>
      {call.errorCode && (
        <Alert
          type="error"
          showIcon
          message={`${call.errorCode}${call.errorCategory ? ` · ${call.errorCategory}` : ''}`}
          description={call.errorMessage || '调用执行失败'}
        />
      )}
      <Descriptions bordered size="small" column={{xs: 1, md: 2}}>
        <Descriptions.Item label="状态">
          <ToolStatusTag status={call.status}/>
        </Descriptions.Item>
        <Descriptions.Item label="固定工具版本">
          {call.toolId}@{call.toolVersion}
        </Descriptions.Item>
        <Descriptions.Item label="调用 ID">
          <Typography.Text copyable>{call.callId}</Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="轨迹 ID">
          <Typography.Text copyable>{call.traceId}</Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="Span ID">
          {call.spanId ? (
            <Typography.Text copyable>{call.spanId}</Typography.Text>
          ) : (
            '-'
          )}
        </Descriptions.Item>
        <Descriptions.Item label="调用来源">
          {call.sourceType || '-'}
          {call.sourceId ? ` · ${call.sourceId}` : ''}
        </Descriptions.Item>
        <Descriptions.Item label="执行模式">
          <ToolStatusTag status={call.executionMode}/>
        </Descriptions.Item>
        <Descriptions.Item label="重试次数">
          {detail.retryCount}
        </Descriptions.Item>
        <Descriptions.Item label="开始时间">
          {formatTime(call.startedAt || call.createTime)}
        </Descriptions.Item>
        <Descriptions.Item label="完成时间">
          {call.completedAt ? formatTime(call.completedAt) : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="执行耗时">
          {formatDuration(call.latencyMs)}
        </Descriptions.Item>
        <Descriptions.Item label="Token">
          输入 {call.inputTokens || 0} / 输出 {call.outputTokens || 0} / 合计{' '}
          {call.totalTokens || 0}
        </Descriptions.Item>
        <Descriptions.Item label="参数摘要" span={2}>
          <Typography.Text copyable>{call.argumentsDigest}</Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="幂等键" span={2}>
          {call.idempotencyKey ? (
            <Typography.Text copyable>{call.idempotencyKey}</Typography.Text>
          ) : (
            '-'
          )}
        </Descriptions.Item>
      </Descriptions>
    </Space>
  );
}

function CallSnapshots({detail}: { detail: ToolCallDetail }) {
  return (
    <Space direction="vertical" size="middle" style={{width: '100%'}}>
      <Alert
        type="info"
        showIcon
        message="以下内容均为服务端持久化的脱敏快照"
        description="凭据只保留引用，不应包含访问令牌、密码或原始敏感参数。"
      />
      <SnapshotCard title="调用参数" value={detail.call.argumentsSnapshot}/>
      <SnapshotCard title="执行上下文" value={detail.call.contextSnapshot}/>
      <SnapshotCard title="生效策略" value={detail.call.policySnapshot}/>
      <SnapshotCard title="调用元数据" value={detail.call.metadata}/>
    </Space>
  );
}

function SnapshotCard({title, value}: { title: string; value: unknown }) {
  return (
    <Card size="small" title={title}>
      <JsonEditor value={value || {}} readOnly height={220}/>
    </Card>
  );
}

function CallResult({result}: { result?: ToolCallResultRecord }) {
  if (!result) return <Empty description="调用尚未产生持久化结果"/>;
  return (
    <Space direction="vertical" size="middle" style={{width: '100%'}}>
      <Descriptions bordered size="small" column={{xs: 1, md: 2}}>
        <Descriptions.Item label="状态">
          <ToolStatusTag status={result.status}/>
        </Descriptions.Item>
        <Descriptions.Item label="完成时间">
          {result.completedAt ? formatTime(result.completedAt) : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="结果 ID">
          <Typography.Text copyable>{result.resultId}</Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="任务 ID">
          {result.taskId ? (
            <Typography.Text copyable>{result.taskId}</Typography.Text>
          ) : (
            '-'
          )}
        </Descriptions.Item>
      </Descriptions>
      {result.output && (
        <SnapshotCard title="结构化输出" value={result.output}/>
      )}
      {Boolean(result.content?.length) && (
        <SnapshotCard title="内容片段" value={result.content}/>
      )}
      {Boolean(result.artifacts?.length) && (
        <SnapshotCard title="产物" value={result.artifacts}/>
      )}
      {result.usageInfo && (
        <SnapshotCard title="用量信息" value={result.usageInfo}/>
      )}
      {result.errorInfo && (
        <SnapshotCard title="错误信息" value={result.errorInfo}/>
      )}
      {result.metadata && (
        <SnapshotCard title="结果元数据" value={result.metadata}/>
      )}
    </Space>
  );
}

function formatTime(value: string): string {
  const time = dayjs(value);
  return time.isValid() ? time.format('YYYY-MM-DD HH:mm:ss.SSS') : value;
}
