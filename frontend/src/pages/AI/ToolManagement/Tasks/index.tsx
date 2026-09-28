import {ReloadOutlined} from '@ant-design/icons';
import type {ActionType, ProColumns} from '@ant-design/pro-components';
import {ProTable} from '@ant-design/pro-components';
import {useAccess, useSearchParams} from '@umijs/max';
import {Button, message, Progress, Space, Typography} from 'antd';
import dayjs from 'dayjs';
import React, {useEffect, useRef, useState} from 'react';
import {ToolStatusTag} from '@/components/AITool';
import {
  normalizeToolError,
  shouldPollToolTask,
  TOOL_TASK_PRESENTATION,
  toolTaskProgressPercent,
} from '@/features/ai-tool';
import {listToolTasks} from '@/services/ant-design-pro/ai.tool';
import type {ToolTaskHandle, ToolTaskStatus} from '@/types/ai.tool.type';
import {ToolManagementPage} from '../components';
import TaskDetailDrawer from './TaskDetailDrawer';

export default function ToolTasksPage() {
  const access = useAccess();
  const actionRef = useRef<ActionType>(null);
  const [searchParams] = useSearchParams();
  const [selectedTaskId, setSelectedTaskId] = useState<string>();
  const [hasActiveTasks, setHasActiveTasks] = useState(false);

  useEffect(() => {
    const taskId = searchParams.get('taskId');
    if (taskId) setSelectedTaskId(taskId);
  }, [searchParams]);

  useEffect(() => {
    if (!hasActiveTasks) return;
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') actionRef.current?.reload();
    }, 5000);
    return () => window.clearInterval(timer);
  }, [hasActiveTasks]);

  const columns: ProColumns<ToolTaskHandle>[] = [
    {
      title: '状态',
      dataIndex: 'status',
      width: 130,
      valueType: 'select',
      valueEnum: Object.fromEntries(
        Object.entries(TOOL_TASK_PRESENTATION).map(([status, presentation]) => [
          status,
          {text: presentation.label},
        ]),
      ),
      render: (_, record) => <ToolStatusTag status={record.status}/>,
    },
    {
      title: '固定工具版本',
      key: 'tool',
      search: false,
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <Typography.Text strong>
            {record.tool.namespace}.{record.tool.name}
          </Typography.Text>
          <Typography.Text type="secondary">
            版本 {record.tool.version}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '执行进度',
      dataIndex: 'progress',
      search: false,
      width: 180,
      render: (_, record) => (
        <Progress
          percent={toolTaskProgressPercent(record.progress)}
          size="small"
        />
      ),
    },
    {
      title: '任务 ID',
      dataIndex: 'taskId',
      search: false,
      ellipsis: true,
      render: (_, record) => (
        <Typography.Text copyable>{record.taskId}</Typography.Text>
      ),
    },
    {
      title: '调用 ID',
      dataIndex: 'callId',
      search: false,
      hideInTable: true,
      render: (_, record) => (
        <Typography.Text copyable>{record.callId}</Typography.Text>
      ),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      search: false,
      width: 180,
      sorter: false,
      renderText: (value) => formatTime(value as string),
    },
    {
      title: '操作',
      valueType: 'option',
      width: 90,
      render: (_, record) => [
        <Typography.Link
          key="detail"
          onClick={() => setSelectedTaskId(record.taskId)}
        >
          详情
        </Typography.Link>,
      ],
    },
  ];

  return (
    <ToolManagementPage
      activeKey="tasks"
      title="异步任务"
      subTitle="查看当前用户的持久化任务状态、执行结果，并对非终态调用执行取消或恢复"
      extra={
        <Button
          icon={<ReloadOutlined/>}
          onClick={() => actionRef.current?.reload()}
        >
          刷新
        </Button>
      }
    >
      <ProTable<ToolTaskHandle>
        rowKey="taskId"
        actionRef={actionRef}
        columns={columns}
        request={async (params) => {
          try {
            const page = await listToolTasks({
              current: params.current,
              pageSize: params.pageSize,
              status: params.status as ToolTaskStatus | undefined,
            });
            setHasActiveTasks(
              page.records.some((record) => shouldPollToolTask(record.status)),
            );
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
          onDoubleClick: () => setSelectedTaskId(record.taskId),
        })}
      />
      <TaskDetailDrawer
        taskId={selectedTaskId}
        open={Boolean(selectedTaskId)}
        canOperate={access.canInvokeAiTools}
        onClose={() => setSelectedTaskId(undefined)}
        onChanged={() => actionRef.current?.reload()}
      />
    </ToolManagementPage>
  );
}

function formatTime(value: string): string {
  const time = dayjs(value);
  return time.isValid() ? time.format('YYYY-MM-DD HH:mm:ss') : value;
}
