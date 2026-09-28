import {LinkOutlined, ReloadOutlined} from '@ant-design/icons';
import {Button, Descriptions, Drawer, Input, message, Progress, Space, Table, Tabs, Tag, Typography,} from 'antd';
import dayjs from 'dayjs';
import React, {useCallback, useEffect, useState} from 'react';
import {JsonEditor} from '@/components';
import {formatDuration, isActiveWorkflowRun, normalizeToolError, WORKFLOW_RUN_PRESENTATION,} from '@/features/ai-tool';
import {cancelWorkflowRun, getWorkflowRun, resumeWorkflowRun,} from '@/services/ant-design-pro/ai.tool.workflow';
import type {WorkflowRunDetailView} from '@/types/ai.tool.type';

interface WorkflowRunDrawerProps {
  runId?: string;
  open: boolean;
  onClose: () => void;
  onChanged?: () => void;
}

export default function WorkflowRunDrawer({
                                            runId,
                                            open,
                                            onClose,
                                            onChanged,
                                          }: WorkflowRunDrawerProps) {
  const [detail, setDetail] = useState<WorkflowRunDetailView>();
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState(false);
  const [resumeToken, setResumeToken] = useState('');

  const load = useCallback(async () => {
    if (!runId) return;
    setLoading(true);
    try {
      setDetail((await getWorkflowRun(runId)) || undefined);
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    } finally {
      setLoading(false);
    }
  }, [runId]);

  useEffect(() => {
    if (open) load().then();
  }, [load, open]);

  useEffect(() => {
    if (!open || !detail || !isActiveWorkflowRun(detail.run.status)) return;
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') load().then();
    }, 4000);
    return () => window.clearInterval(timer);
  }, [detail, load, open]);

  const execute = async (
    operation: () => Promise<unknown>,
    success: string,
  ) => {
    setActionLoading(true);
    try {
      await operation();
      message.success(success).then();
      await load();
      onChanged?.();
    } catch (error) {
      message.error(normalizeToolError(error).message).then();
    } finally {
      setActionLoading(false);
    }
  };

  const run = detail?.run;
  const presentation = run ? WORKFLOW_RUN_PRESENTATION[run.status] : undefined;
  return (
    <Drawer
      title="工作流运行详情"
      width={820}
      open={open}
      loading={loading}
      onClose={onClose}
      extra={
        <Button icon={<ReloadOutlined/>} onClick={() => load()}>
          刷新
        </Button>
      }
    >
      {run && (
        <>
          <Descriptions column={2} bordered size="small">
            <Descriptions.Item label="状态">
              <Tag color={presentation?.color}>
                {presentation?.label || run.status}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="版本">
              {run.workflowId} / {run.workflowVersion}
            </Descriptions.Item>
            <Descriptions.Item label="运行 ID">
              <Typography.Text copyable>{run.runId}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Trace ID">
              <Typography.Text copyable>{run.traceId}</Typography.Text>
              <Button
                type="link"
                size="small"
                icon={<LinkOutlined/>}
                href={`/AI/ToolManagement/Observability?traceId=${encodeURIComponent(run.traceId)}`}
              >
                轨迹
              </Button>
            </Descriptions.Item>
            <Descriptions.Item label="开始时间">
              {run.startedAt
                ? dayjs(run.startedAt).format('YYYY-MM-DD HH:mm:ss')
                : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="结束时间">
              {run.completedAt
                ? dayjs(run.completedAt).format('YYYY-MM-DD HH:mm:ss')
                : '-'}
            </Descriptions.Item>
          </Descriptions>
          <Progress
            style={{marginTop: 16}}
            percent={Math.min(
              100,
              Math.round(
                (run.currentSteps / Math.max(1, run.maximumSteps)) * 100,
              ),
            )}
            format={() => `${run.currentSteps}/${run.maximumSteps} 步`}
            status={
              run.status === 'FAILED'
                ? 'exception'
                : run.status === 'SUCCEEDED'
                  ? 'success'
                  : 'active'
            }
          />
          <Space wrap style={{margin: '12px 0'}}>
            {isActiveWorkflowRun(run.status) && run.status !== 'CANCELLED' && (
              <Button
                danger
                loading={actionLoading}
                onClick={() =>
                  execute(() => cancelWorkflowRun(run.runId), '取消请求已提交')
                }
              >
                取消运行
              </Button>
            )}
            {['PAUSED', 'WAITING_APPROVAL', 'WAITING_TOOL'].includes(
              run.status,
            ) && (
              <>
                <Input.Password
                  style={{width: 320}}
                  value={resumeToken}
                  placeholder="输入启动/暂停时返回的恢复令牌"
                  onChange={(event) => setResumeToken(event.target.value)}
                />
                <Button
                  type="primary"
                  disabled={!resumeToken.trim()}
                  loading={actionLoading}
                  onClick={() =>
                    execute(
                      () =>
                        resumeWorkflowRun({resumeToken: resumeToken.trim()}),
                      '工作流已恢复',
                    )
                  }
                >
                  恢复运行
                </Button>
              </>
            )}
          </Space>
          <Tabs
            items={[
              {
                key: 'nodes',
                label: `节点记录 (${detail.nodes.length})`,
                children: (
                  <Table
                    rowKey="nodeRunId"
                    size="small"
                    pagination={false}
                    dataSource={detail.nodes}
                    columns={[
                      {title: '节点', dataIndex: 'nodeId'},
                      {title: '类型', dataIndex: 'nodeType', width: 90},
                      {
                        title: '状态',
                        dataIndex: 'status',
                        width: 110,
                        render: (value) => <Tag>{value}</Tag>,
                      },
                      {title: '尝试', dataIndex: 'attempt', width: 70},
                      {
                        title: '耗时',
                        dataIndex: 'latencyMs',
                        width: 100,
                        render: formatDuration,
                      },
                      {title: '调用 ID', dataIndex: 'callId', ellipsis: true},
                    ]}
                  />
                ),
              },
              {
                key: 'input',
                label: '输入',
                children: (
                  <JsonEditor value={run.inputs} readOnly height={300}/>
                ),
              },
              {
                key: 'output',
                label: '输出/错误',
                children: (
                  <JsonEditor
                    value={{outputs: run.outputs, error: run.errorInfo}}
                    readOnly
                    height={300}
                  />
                ),
              },
              {
                key: 'checkpoint',
                label: `检查点 #${detail.checkpointSequence}`,
                children: (
                  <JsonEditor
                    value={detail.checkpointState}
                    readOnly
                    height={300}
                  />
                ),
              },
            ]}
          />
        </>
      )}
    </Drawer>
  );
}
