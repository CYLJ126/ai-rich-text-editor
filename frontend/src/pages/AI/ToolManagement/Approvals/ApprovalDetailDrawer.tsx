import {CheckCircleOutlined, CloseCircleOutlined, ReloadOutlined,} from '@ant-design/icons';
import {useNavigate} from '@umijs/max';
import {Alert, Button, Descriptions, Drawer, Form, Input, message, Modal, Space, Spin, Typography,} from 'antd';
import dayjs from 'dayjs';
import React, {useCallback, useEffect, useState} from 'react';
import {JsonEditor} from '@/components';
import {ToolErrorAlert, ToolStatusTag} from '@/components/AITool';
import {canDecideApproval, normalizeToolError} from '@/features/ai-tool';
import {decideToolApproval, getToolApproval,} from '@/services/ant-design-pro/ai.tool.approval';
import type {ToolApprovalRecord} from '@/types/ai.tool.type';

export interface ApprovalDetailDrawerProps {
  requestId?: string;
  open: boolean;
  canApprove: boolean;
  onClose: () => void;
  onChanged?: () => void;
}

interface DecisionFormValue {
  reason?: string;
}

export default function ApprovalDetailDrawer({
                                               requestId,
                                               open,
                                               canApprove,
                                               onClose,
                                               onChanged,
                                             }: ApprovalDetailDrawerProps) {
  const navigate = useNavigate();
  const [form] = Form.useForm<DecisionFormValue>();
  const [approval, setApproval] = useState<ToolApprovalRecord>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>();
  const [decision, setDecision] = useState<boolean>();
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    if (!requestId || !open) return;
    setLoading(true);
    setError(undefined);
    try {
      const nextApproval = await getToolApproval(requestId);
      if (!nextApproval) throw new Error('审批请求不存在，或当前用户无权查看');
      setApproval(nextApproval);
    } catch (nextError) {
      setError(nextError);
    } finally {
      setLoading(false);
    }
  }, [open, requestId]);

  useEffect(() => {
    if (!open) return;
    setApproval(undefined);
    setDecision(undefined);
    form.resetFields();
    load().then();
  }, [form, load, open]);

  const openDecision = (approved: boolean) => {
    form.resetFields();
    setDecision(approved);
  };

  const submitDecision = async () => {
    if (!approval || decision === undefined) return;
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      await decideToolApproval(approval.requestId, {
        approved: decision,
        reason: values.reason?.trim() || undefined,
      });
      message
        .success(decision ? '已批准，任务将自动恢复' : '已拒绝，任务将终止')
        .then();
      setDecision(undefined);
      await load();
      onChanged?.();
    } catch (nextError) {
      if (
        nextError &&
        typeof nextError === 'object' &&
        'errorFields' in nextError
      )
        return;
      message.error(normalizeToolError(nextError).message).then();
    } finally {
      setSubmitting(false);
    }
  };

  const actionable = Boolean(
    approval && canApproveApproval(approval, canApprove),
  );

  return (
    <Drawer
      title="工具调用审批详情"
      open={open}
      width={760}
      destroyOnHidden
      onClose={onClose}
      extra={
        approval && (
          <Space>
            <Button icon={<ReloadOutlined/>} loading={loading} onClick={load}>
              刷新
            </Button>
            <Button
              danger
              icon={<CloseCircleOutlined/>}
              disabled={!actionable}
              onClick={() => openDecision(false)}
            >
              拒绝
            </Button>
            <Button
              type="primary"
              icon={<CheckCircleOutlined/>}
              disabled={!actionable}
              onClick={() => openDecision(true)}
            >
              批准并恢复
            </Button>
          </Space>
        )
      }
    >
      <Spin spinning={loading}>
        {Boolean(error) && <ToolErrorAlert error={error} showDetails/>}
        {approval && (
          <Space direction="vertical" size="large" style={{width: '100%'}}>
            <Descriptions bordered size="small" column={{xs: 1, md: 2}}>
              <Descriptions.Item label="审批状态">
                <ToolStatusTag status={approval.status}/>
              </Descriptions.Item>
              <Descriptions.Item label="固定工具版本">
                {approval.toolId}@{approval.toolVersion}
              </Descriptions.Item>
              <Descriptions.Item label="审批请求 ID">
                <Typography.Text copyable>{approval.requestId}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="调用 ID">
                <Typography.Text copyable>{approval.callId}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="任务 ID">
                {approval.taskId ? (
                  <Typography.Link
                    copyable
                    onClick={() =>
                      navigate(
                        `/AI/ToolManagement/Tasks?taskId=${encodeURIComponent(approval.taskId || '')}`,
                      )
                    }
                  >
                    {approval.taskId}
                  </Typography.Link>
                ) : (
                  '-'
                )}
              </Descriptions.Item>
              <Descriptions.Item label="工作流运行 ID">
                {approval.workflowRunId || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {formatTime(approval.createTime)}
              </Descriptions.Item>
              <Descriptions.Item label="过期时间">
                {formatTime(approval.expiresAt)}
              </Descriptions.Item>
              <Descriptions.Item label="审批人">
                {approval.approverId || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="决策时间">
                {approval.decidedAt ? formatTime(approval.decidedAt) : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="决策理由" span={2}>
                {approval.decisionReason || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="审批摘要" span={2}>
                {approval.summary || '-'}
              </Descriptions.Item>
            </Descriptions>

            {approval.status === 'expired' && (
              <Alert
                type="warning"
                showIcon
                message="审批请求已过期"
                description="过期请求不能再次决策，对应任务将由服务端终止。"
              />
            )}

            <div>
              <Typography.Title level={5}>脱敏参数</Typography.Title>
              <Alert
                type="info"
                showIcon
                message="这里只展示服务端生成的脱敏快照"
                description="凭据值和原始敏感参数不会进入审批响应。请结合参数摘要确认参数在审批后未被替换。"
                style={{marginBottom: 12}}
              />
              <JsonEditor
                value={approval.displayArguments}
                readOnly
                height={260}
              />
            </div>
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label="参数 SHA-256 摘要">
                <Typography.Text copyable>
                  {approval.argumentsDigest}
                </Typography.Text>
              </Descriptions.Item>
            </Descriptions>
          </Space>
        )}
      </Spin>

      <Modal
        title={decision ? '批准工具调用' : '拒绝工具调用'}
        open={decision !== undefined}
        okText={decision ? '确认批准并恢复' : '确认拒绝'}
        okButtonProps={{danger: decision === false}}
        confirmLoading={submitting}
        onOk={submitDecision}
        onCancel={() => setDecision(undefined)}
      >
        <Alert
          type={decision ? 'warning' : 'error'}
          showIcon
          message={
            decision
              ? '批准后服务端会校验参数摘要并自动恢复原任务。'
              : '拒绝后对应任务会进入终态，不能使用恢复令牌重新执行。'
          }
          style={{marginBottom: 16}}
        />
        <Form form={form} layout="vertical">
          <Form.Item
            name="reason"
            label="决策理由"
            rules={[
              {
                validator: (_, value) =>
                  decision === false && !value?.trim()
                    ? Promise.reject(new Error('拒绝调用时必须填写理由'))
                    : Promise.resolve(),
              },
            ]}
          >
            <Input.TextArea
              rows={4}
              maxLength={1000}
              showCount
              placeholder={decision ? '可选：记录批准依据' : '请说明拒绝原因'}
            />
          </Form.Item>
        </Form>
      </Modal>
    </Drawer>
  );
}

function canApproveApproval(approval: ToolApprovalRecord, canApprove: boolean) {
  return canApprove && canDecideApproval(approval);
}

function formatTime(value: string): string {
  const time = dayjs(value);
  return time.isValid() ? time.format('YYYY-MM-DD HH:mm:ss') : value;
}
