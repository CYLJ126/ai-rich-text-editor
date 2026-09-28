import {ReloadOutlined} from '@ant-design/icons';
import type {ActionType, ProColumns} from '@ant-design/pro-components';
import {ProTable} from '@ant-design/pro-components';
import {useAccess} from '@umijs/max';
import {Button, Card, Col, message, Result, Row, Statistic, Tabs, Typography,} from 'antd';
import dayjs from 'dayjs';
import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ToolStatusTag} from '@/components/AITool';
import {effectiveApprovalStatus, normalizeToolError, TOOL_APPROVAL_PRESENTATION,} from '@/features/ai-tool';
import {listToolApprovals, listToolGuardrails,} from '@/services/ant-design-pro/ai.tool.approval';
import type {ToolApprovalRecord, ToolApprovalStatus, ToolGuardrailView,} from '@/types/ai.tool.type';
import {ToolManagementPage} from '../components';
import ApprovalDetailDrawer from './ApprovalDetailDrawer';
import SecurityOverview from './SecurityOverview';

type ApprovalFilter = ToolApprovalStatus | 'all';
type ApprovalCounts = Record<ToolApprovalStatus, number>;

const emptyCounts: ApprovalCounts = {
  pending: 0,
  approved: 0,
  rejected: 0,
  expired: 0,
};

export default function ToolApprovalsPage() {
  const access = useAccess();
  const actionRef = useRef<ActionType>(null);
  const [selectedRequestId, setSelectedRequestId] = useState<string>();
  const [counts, setCounts] = useState<ApprovalCounts>(emptyCounts);
  const [countsLoading, setCountsLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('approvals');
  const [currentFilter, setCurrentFilter] = useState<ApprovalFilter>('pending');
  const [guardrails, setGuardrails] = useState<ToolGuardrailView[]>([]);
  const [guardrailLoading, setGuardrailLoading] = useState(false);
  const [guardrailError, setGuardrailError] = useState<unknown>();

  const loadCounts = useCallback(async () => {
    setCountsLoading(true);
    try {
      const statuses: ToolApprovalStatus[] = [
        'pending',
        'approved',
        'rejected',
        'expired',
      ];
      const pages = await Promise.all(
        statuses.map((status) =>
          listToolApprovals({status, current: 1, pageSize: 1}),
        ),
      );
      setCounts(
        Object.fromEntries(
          statuses.map((status, index) => [status, pages[index].total]),
        ) as ApprovalCounts,
      );
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    } finally {
      setCountsLoading(false);
    }
  }, []);

  const loadGuardrails = useCallback(async () => {
    setGuardrailLoading(true);
    setGuardrailError(undefined);
    try {
      setGuardrails(await listToolGuardrails());
    } catch (error) {
      setGuardrailError(error);
    } finally {
      setGuardrailLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!access.canApproveAiTools) return;
    loadCounts().then();
    loadGuardrails().then();
  }, [access.canApproveAiTools, loadCounts, loadGuardrails]);

  useEffect(() => {
    if (activeTab !== 'approvals' || currentFilter !== 'pending') return;
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') actionRef.current?.reload();
    }, 5000);
    return () => window.clearInterval(timer);
  }, [activeTab, currentFilter]);

  if (!access.canApproveAiTools) {
    return (
      <ToolManagementPage activeKey="approvals" title="审批与安全">
        <Result
          status="403"
          title="403"
          subTitle="你没有审批 AI 工具调用的权限"
        />
      </ToolManagementPage>
    );
  }

  const refresh = () => {
    actionRef.current?.reload();
    loadCounts().then();
    if (activeTab === 'security') loadGuardrails().then();
  };

  const columns: ProColumns<ToolApprovalRecord>[] = [
    {
      title: '状态',
      dataIndex: 'status',
      width: 120,
      valueType: 'select',
      initialValue: 'pending',
      valueEnum: {
        all: {text: '全部'},
        ...Object.fromEntries(
          Object.entries(TOOL_APPROVAL_PRESENTATION).map(
            ([status, presentation]) => [status, {text: presentation.label}],
          ),
        ),
      },
      render: (_, record) => (
        <ToolStatusTag status={effectiveApprovalStatus(record)}/>
      ),
    },
    {
      title: '工具版本',
      key: 'tool',
      search: false,
      render: (_, record) => (
        <Typography.Text strong>
          {record.toolId}@{record.toolVersion}
        </Typography.Text>
      ),
    },
    {
      title: '审批摘要',
      dataIndex: 'summary',
      search: false,
      ellipsis: true,
      renderText: (value) => value || '-',
    },
    {
      title: '任务 ID',
      dataIndex: 'taskId',
      search: false,
      hideInTable: true,
      render: (_, record) =>
        record.taskId ? (
          <Typography.Text copyable>{record.taskId}</Typography.Text>
        ) : (
          '-'
        ),
    },
    {
      title: '过期时间',
      dataIndex: 'expiresAt',
      search: false,
      width: 180,
      render: (_, record) => (
        <Typography.Text
          type={
            effectiveApprovalStatus(record) === 'expired' ? 'danger' : undefined
          }
        >
          {formatTime(record.expiresAt)}
        </Typography.Text>
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      search: false,
      width: 180,
      renderText: (value) => formatTime(value as string),
    },
    {
      title: '操作',
      valueType: 'option',
      width: 90,
      render: (_, record) => [
        <Typography.Link
          key="detail"
          onClick={() => setSelectedRequestId(record.requestId)}
        >
          审批详情
        </Typography.Link>,
      ],
    },
  ];

  const approvalContent = (
    <>
      <Row gutter={[16, 16]} style={{marginBottom: 16}}>
        {(Object.keys(emptyCounts) as ToolApprovalStatus[]).map((status) => (
          <Col key={status} xs={12} lg={6}>
            <Card size="small" loading={countsLoading}>
              <Statistic
                title={TOOL_APPROVAL_PRESENTATION[status].label}
                value={counts[status]}
                valueStyle={{color: statisticColor(status)}}
              />
            </Card>
          </Col>
        ))}
      </Row>
      <ProTable<ToolApprovalRecord>
        rowKey="requestId"
        actionRef={actionRef}
        columns={columns}
        request={async (params) => {
          const status = (params.status || 'pending') as ApprovalFilter;
          setCurrentFilter(status);
          try {
            const page = await listToolApprovals({
              status,
              current: params.current,
              pageSize: params.pageSize,
            });
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
          onDoubleClick: () => setSelectedRequestId(record.requestId),
        })}
      />
      <ApprovalDetailDrawer
        requestId={selectedRequestId}
        open={Boolean(selectedRequestId)}
        canApprove={access.canApproveAiTools}
        onClose={() => setSelectedRequestId(undefined)}
        onChanged={refresh}
      />
    </>
  );

  return (
    <ToolManagementPage
      activeKey="approvals"
      title="审批与安全"
      subTitle="审批高风险或有副作用的调用，并查看服务端固定 Guardrail 执行链"
      extra={
        <Button icon={<ReloadOutlined/>} onClick={refresh}>
          刷新
        </Button>
      }
    >
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {key: 'approvals', label: '人工审批', children: approvalContent},
          {
            key: 'security',
            label: '安全执行链',
            children: (
              <SecurityOverview
                guardrails={guardrails}
                loading={guardrailLoading}
                error={guardrailError}
              />
            ),
          },
        ]}
      />
    </ToolManagementPage>
  );
}

function statisticColor(status: ToolApprovalStatus): string | undefined {
  if (status === 'approved') return '#52c41a';
  if (status === 'rejected') return '#ff4d4f';
  if (status === 'pending') return '#faad14';
  return undefined;
}

function formatTime(value: string): string {
  const time = dayjs(value);
  return time.isValid() ? time.format('YYYY-MM-DD HH:mm:ss') : value;
}
