import {CloseCircleOutlined, PlayCircleOutlined, ReloadOutlined,} from '@ant-design/icons';
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Input,
  message,
  Modal,
  Popconfirm,
  Progress,
  Space,
  Spin,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import React, {useCallback, useEffect, useState} from 'react';
import {ToolErrorAlert, ToolResultView, ToolStatusTag,} from '@/components/AITool';
import {
  normalizeToolError,
  shouldPollToolTask,
  toolErrorFromPayload,
  toolReferenceLabel,
  toolTaskProgressPercent,
} from '@/features/ai-tool';
import {cancelToolCall, getToolTask, getToolTaskResult, resumeTool,} from '@/services/ant-design-pro/ai.tool';
import type {ToolResult, ToolTaskHandle} from '@/types/ai.tool.type';

export interface TaskDetailDrawerProps {
  taskId?: string;
  open: boolean;
  canOperate: boolean;
  onClose: () => void;
  onChanged?: () => void;
}

export default function TaskDetailDrawer({
                                           taskId,
                                           open,
                                           canOperate,
                                           onClose,
                                           onChanged,
                                         }: TaskDetailDrawerProps) {
  const [task, setTask] = useState<ToolTaskHandle>();
  const [result, setResult] = useState<ToolResult>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>();
  const [cancelling, setCancelling] = useState(false);
  const [resumeOpen, setResumeOpen] = useState(false);
  const [resumeToken, setResumeToken] = useState('');
  const [resuming, setResuming] = useState(false);

  const load = useCallback(
    async (silent = false) => {
      if (!taskId || !open) return;
      if (!silent) setLoading(true);
      setError(undefined);
      try {
        const nextTask = await getToolTask(taskId);
        if (!nextTask) throw new Error('任务不存在，或当前用户无权查看该任务');
        setTask(nextTask);
        if (!shouldPollToolTask(nextTask.status)) {
          setResult((await getToolTaskResult(taskId)) || undefined);
        } else {
          setResult(undefined);
        }
      } catch (nextError) {
        setError(nextError);
      } finally {
        if (!silent) setLoading(false);
      }
    },
    [open, taskId],
  );

  useEffect(() => {
    if (!open) return;
    setTask(undefined);
    setResult(undefined);
    setResumeToken('');
    load().then();
  }, [load, open]);

  useEffect(() => {
    if (!open || !shouldPollToolTask(task?.status)) return;
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') load(true).then();
    }, 3000);
    return () => window.clearInterval(timer);
  }, [load, open, task?.status]);

  const cancel = async () => {
    if (!task) return;
    setCancelling(true);
    try {
      const changed = await cancelToolCall(task.callId);
      if (!changed) throw new Error('任务已进入终态，无法取消');
      message.success('取消请求已生效').then();
      await load();
      onChanged?.();
    } catch (nextError) {
      message.error(normalizeToolError(nextError).message).then();
    } finally {
      setCancelling(false);
    }
  };

  const resume = async (token = resumeToken) => {
    const normalized = token.trim();
    if (!normalized) {
      message.warning('请输入恢复令牌').then();
      return;
    }
    setResuming(true);
    try {
      const resumeResult = await resumeTool(normalized);
      if (
        resumeResult.status === 'failed' ||
        resumeResult.status === 'denied' ||
        resumeResult.status === 'cancelled' ||
        resumeResult.status === 'timed-out'
      ) {
        setResult(resumeResult);
        throw toolErrorFromPayload(resumeResult.error);
      }
      if (resumeResult.status !== 'succeeded') {
        throw new Error('恢复请求未被接受，请检查审批状态或恢复令牌');
      }
      message.success('任务已恢复并重新进入队列').then();
      setResumeOpen(false);
      setResumeToken('');
      await load();
      onChanged?.();
    } catch (nextError) {
      message.error(normalizeToolError(nextError).message).then();
    } finally {
      setResuming(false);
    }
  };

  const active = shouldPollToolTask(task?.status);

  return (
    <Drawer
      title="异步任务详情"
      open={open}
      width={760}
      destroyOnHidden
      onClose={onClose}
      extra={
        task && (
          <Space>
            <Button
              icon={<ReloadOutlined/>}
              onClick={() => load()}
              loading={loading}
            >
              刷新
            </Button>
            {canOperate && active && (
              <Popconfirm
                title="确认取消此调用？"
                description="取消后任务不可恢复。"
                onConfirm={cancel}
              >
                <Button
                  danger
                  icon={<CloseCircleOutlined/>}
                  loading={cancelling}
                >
                  取消调用
                </Button>
              </Popconfirm>
            )}
            {canOperate &&
              (task.status === 'PAUSED' ||
                task.status === 'WAITING_APPROVAL') && (
                <Button
                  icon={<PlayCircleOutlined/>}
                  onClick={() => setResumeOpen(true)}
                >
                  恢复任务
                </Button>
              )}
          </Space>
        )
      }
    >
      <Spin spinning={loading}>
        {Boolean(error) && <ToolErrorAlert error={error} showDetails/>}
        {task && (
          <Space direction="vertical" size="large" style={{width: '100%'}}>
            <Descriptions bordered size="small" column={{xs: 1, md: 2}}>
              <Descriptions.Item label="状态">
                <ToolStatusTag status={task.status}/>
              </Descriptions.Item>
              <Descriptions.Item label="固定工具版本">
                {toolReferenceLabel(task.tool)}
              </Descriptions.Item>
              <Descriptions.Item label="任务 ID">
                <Typography.Text copyable>{task.taskId}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="调用 ID">
                <Typography.Text copyable>{task.callId}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {formatTime(task.createdAt)}
              </Descriptions.Item>
              <Descriptions.Item label="更新时间">
                {formatTime(task.updatedAt)}
              </Descriptions.Item>
              <Descriptions.Item label="乐观锁版本">
                {task.version}
              </Descriptions.Item>
              <Descriptions.Item label="进度说明">
                {task.progressMessage || '-'}
              </Descriptions.Item>
            </Descriptions>
            <Progress
              percent={toolTaskProgressPercent(task.progress)}
              status={
                task.status === 'FAILED' || task.status === 'TIMED_OUT'
                  ? 'exception'
                  : undefined
              }
            />
            {task.status === 'WAITING_APPROVAL' && (
              <Alert
                showIcon
                type="warning"
                message="任务等待人工审批"
                description="只有审批通过后，原始调用方持有的恢复令牌才会生效。审批功能在审批中心完成。"
              />
            )}
            {!active && (
              <div>
                <Typography.Title level={5}>任务结果</Typography.Title>
                <ToolResultView result={result}/>
              </div>
            )}
          </Space>
        )}
      </Spin>

      <Modal
        title="使用恢复令牌"
        open={resumeOpen}
        okText="确认恢复"
        confirmLoading={resuming}
        onOk={() => resume()}
        onCancel={() => setResumeOpen(false)}
      >
        <Alert
          type="info"
          showIcon
          message="恢复令牌仅在调用首次暂停时返回"
          description="服务端只持久化令牌摘要，因此无法从任务详情中找回原始令牌。"
          style={{marginBottom: 16}}
        />
        <Input.Password
          value={resumeToken}
          onChange={(event) => setResumeToken(event.target.value)}
          placeholder="请输入原始恢复令牌"
          autoComplete="off"
        />
      </Modal>
    </Drawer>
  );
}

function formatTime(value: string): string {
  const time = dayjs(value);
  return time.isValid() ? time.format('YYYY-MM-DD HH:mm:ss') : value;
}
