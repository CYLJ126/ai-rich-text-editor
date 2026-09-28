import {ReloadOutlined} from '@ant-design/icons';
import type {ActionType, ProColumns} from '@ant-design/pro-components';
import {ProTable} from '@ant-design/pro-components';
import {useSearchParams} from '@umijs/max';
import {Button, message, Space, Tag, Typography} from 'antd';
import dayjs from 'dayjs';
import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ToolStatusTag} from '@/components/AITool';
import {formatDuration, isTerminalToolStatus, normalizeToolError, TOOL_RESULT_PRESENTATION,} from '@/features/ai-tool';
import {getToolCallStatistics, listToolCalls,} from '@/services/ant-design-pro/ai.tool.observability';
import type {ToolCallDetail, ToolCallStatistics, ToolResultStatus,} from '@/types/ai.tool.type';
import {ToolManagementPage} from '../components';
import CallDetailDrawer from './CallDetailDrawer';
import CallStatisticsOverview from './CallStatisticsOverview';

export default function ToolObservabilityPage() {
  const actionRef = useRef<ActionType>(null);
  const statisticsSequence = useRef(0);
  const [searchParams] = useSearchParams();
  const [selectedCallId, setSelectedCallId] = useState<string>();
  const [statistics, setStatistics] = useState<ToolCallStatistics>();
  const [statisticsLoading, setStatisticsLoading] = useState(false);
  const [statisticsToolId, setStatisticsToolId] = useState<string>();
  const [hasActiveCalls, setHasActiveCalls] = useState(false);

  useEffect(() => {
    const callId = searchParams.get('callId');
    if (callId) setSelectedCallId(callId);
  }, [searchParams]);

  const loadStatistics = useCallback(async (toolId?: string) => {
    const currentSequence = ++statisticsSequence.current;
    setStatisticsLoading(true);
    try {
      const next = await getToolCallStatistics(toolId?.trim() || undefined);
      if (statisticsSequence.current !== currentSequence) return;
      setStatistics(next);
      setStatisticsToolId(toolId?.trim() || undefined);
    } catch (error) {
      if (statisticsSequence.current === currentSequence) {
        message.error(normalizeToolError(error).message).then();
      }
    } finally {
      if (statisticsSequence.current === currentSequence) {
        setStatisticsLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    if (!hasActiveCalls) return;
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') actionRef.current?.reload();
    }, 5000);
    return () => window.clearInterval(timer);
  }, [hasActiveCalls]);

  const columns: ProColumns<ToolCallDetail>[] = [
    {
      title: '状态',
      dataIndex: 'status',
      width: 125,
      valueType: 'select',
      valueEnum: Object.fromEntries(
        Object.entries(TOOL_RESULT_PRESENTATION).map(
          ([status, presentation]) => [status, {text: presentation.label}],
        ),
      ),
      render: (_, record) => <ToolStatusTag status={record.call.status}/>,
    },
    {
      title: '工具 ID',
      dataIndex: 'toolId',
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <Typography.Text strong>{record.call.toolId}</Typography.Text>
          <Typography.Text type="secondary">
            版本 {record.call.toolVersion}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '来源',
      key: 'sourceType',
      search: false,
      width: 110,
      render: (_, record) => <Tag>{record.call.sourceType || 'UNKNOWN'}</Tag>,
    },
    {
      title: '调用 ID',
      key: 'callId',
      search: false,
      ellipsis: true,
      render: (_, record) => (
        <Typography.Text copyable>{record.call.callId}</Typography.Text>
      ),
    },
    {
      title: '耗时',
      key: 'latencyMs',
      search: false,
      width: 110,
      render: (_, record) => formatDuration(record.call.latencyMs),
    },
    {
      title: 'Token',
      key: 'tokens',
      search: false,
      width: 90,
      render: (_, record) => record.call.totalTokens || 0,
    },
    {
      title: '重试',
      dataIndex: 'retryCount',
      search: false,
      width: 75,
    },
    {
      title: '错误码',
      key: 'errorCode',
      search: false,
      hideInTable: true,
      render: (_, record) => record.call.errorCode || '-',
    },
    {
      title: '创建时间',
      key: 'createTime',
      search: false,
      width: 180,
      render: (_, record) => formatTime(record.call.createTime),
    },
    {
      title: '操作',
      valueType: 'option',
      width: 80,
      render: (_, record) => [
        <Typography.Link
          key="detail"
          onClick={() => setSelectedCallId(record.call.callId)}
        >
          详情
        </Typography.Link>,
      ],
    },
  ];

  const refresh = () => {
    actionRef.current?.reload();
  };

  return (
    <ToolManagementPage
      activeKey="observability"
      title="轨迹与统计"
      subTitle="查看当前用户的工具调用明细、成功率、耗时、Token、失败原因和完整执行事件"
      extra={
        <Button icon={<ReloadOutlined/>} onClick={refresh}>
          刷新
        </Button>
      }
    >
      <CallStatisticsOverview
        statistics={statistics}
        loading={statisticsLoading}
        toolId={statisticsToolId}
      />
      <ProTable<ToolCallDetail>
        headerTitle="调用明细"
        style={{marginTop: 16}}
        rowKey={(record) => record.call.callId}
        actionRef={actionRef}
        columns={columns}
        request={async (params) => {
          const toolId =
            typeof params.toolId === 'string' ? params.toolId : undefined;
          try {
            const page = await listToolCalls({
              toolId,
              status: params.status as ToolResultStatus | undefined,
              current: params.current,
              pageSize: params.pageSize,
            });
            setHasActiveCalls(
              page.records.some(
                (record) => !isTerminalToolStatus(record.call.status),
              ),
            );
            loadStatistics(toolId).then();
            return {data: page.records, total: page.total, success: true};
          } catch (error) {
            message.error(normalizeToolError(error).message).then();
            return {data: [], total: 0, success: false};
          }
        }}
        search={{labelWidth: 'auto'}}
        pagination={{defaultPageSize: 20, showSizeChanger: true}}
        options={{reload: false, density: true, setting: true}}
        onRow={(record) => ({
          onDoubleClick: () => setSelectedCallId(record.call.callId),
        })}
      />
      <CallDetailDrawer
        callId={selectedCallId}
        open={Boolean(selectedCallId)}
        onClose={() => setSelectedCallId(undefined)}
      />
    </ToolManagementPage>
  );
}

function formatTime(value: string): string {
  const time = dayjs(value);
  return time.isValid() ? time.format('YYYY-MM-DD HH:mm:ss') : value;
}
