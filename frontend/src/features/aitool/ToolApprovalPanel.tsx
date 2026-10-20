import React, {useCallback, useEffect, useRef, useState} from 'react';
import {Alert, Button, Card, Descriptions, Flex, Modal, Space, Typography} from 'antd';
import type {BrowserContext} from '@/features/ainew/storage';
import {readValue, removeValue, storageKey, writeValue} from '@/features/ainew/storage';
import {currentAiCredential} from '@/services/arte-ai/request';
import {
  type ApprovalDecision,
  type ApprovalOperation,
  decideToolApproval,
  getToolApproval,
  type ToolApprovalDetail
} from '@/services/arte-ai/toolApproval';
import {toolFailure} from './useToolCommand';
import {z} from 'zod';

const operationSchema = z.object({
  key: z.string().min(1).max(256), body: z.object({
    scope: z.object({tenantId: z.string().min(1).max(256), workspaceId: z.string().min(1).max(256)}),
    approvalId: z.string().min(1).max(256), expectedVersion: z.string().regex(/^(0|[1-9][0-9]{0,17})$/),
    subjectDigest: z.string().regex(/^[0-9a-f]{64}$/), decision: z.enum(['APPROVE', 'REJECT']),
  })
});

const states = {
  PENDING: '等待决定',
  APPROVED: '已批准，执行结果请查看原调用',
  REJECTED: '已拒绝',
  EXPIRED: '已到期',
  CANCELLED: '已取消'
};
const executionStates: Record<string, string> = {
  ACCEPTED: '已受理',
  WAITING_APPROVAL: '等待人工决定',
  QUEUED: '排队中',
  RUNNING: '执行中',
  SUCCEEDED: '执行成功',
  FAILED: '执行失败',
  TIMED_OUT: '已到期',
  CANCELLED: '已取消',
  INTERRUPTED: '已中断',
  UNKNOWN: '结果待核对'
};

/** 固定内容人工决定；先持久化命令再提交，超时仅重发原命令，不改键或反向决定。 */
export default function ToolApprovalPanel({context, approvalId, invocationId, onDecided}: {
  context: BrowserContext; approvalId?: string; invocationId?: string; onDecided?: () => void;
}) {
  const [detail, setDetail] = useState<ToolApprovalDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState<ApprovalOperation | null>(null);
  const [busy, setBusy] = useState(false);
  const [storageBlocked, setStorageBlocked] = useState(false);
  const [remaining, setRemaining] = useState(0);
  const active = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const anchor = useRef({received: 0, millis: 0});
  const operationKey = detail ? storageKey(context, `tool-approval-${detail.approvalId}`) : null;
  const [modal, modalContext] = Modal.useModal();
  const load = useCallback(async () => {
    active.current?.abort();
    const controller = new AbortController();
    active.current = controller;
    const version = ++generation.current, credential = currentAiCredential();
    setError(null);
    try {
      const {body} = await getToolApproval(context.scope, approvalId ? {approvalId} : {invocationId: invocationId!}, {signal: controller.signal});
      if (controller.signal.aborted || version !== generation.current || credential !== currentAiCredential()) return;
      const value = body.data;
      setDetail(value);
      anchor.current = {
        received: performance.now(),
        millis: Date.parse(value.expiresAt) - Date.parse(value.serverTime)
      };
      setRemaining(Math.max(0, anchor.current.millis));
      const saved = readValue<unknown>(storageKey(context, `tool-approval-${value.approvalId}`));
      const parsed = operationSchema.safeParse(saved.value);
      const valid = parsed.success && parsed.data.body.approvalId === value.approvalId
        && parsed.data.body.scope.tenantId === context.scope.tenantId && parsed.data.body.scope.workspaceId === context.scope.workspaceId;
      const blocked = !!saved.warning || (saved.value !== null && !valid);
      setStorageBlocked(blocked);
      if (blocked) setError('无法读取决定恢复记录；请恢复浏览器存储后再操作，暂不发送新决定。');
      setPending(valid ? parsed.data as ApprovalOperation : null);
    } catch (failure) {
      if (!controller.signal.aborted && version === generation.current && credential === currentAiCredential()) {
        setDetail(null);
        setError(toolFailure(failure));
      }
    }
  }, [approvalId, invocationId, context.scope, context.userId]);
  useEffect(() => {
    setDetail(null);
    setPending(null);
    setNotice(null);
    setError(null);
    setBusy(false);
    void load();
    return () => {
      generation.current++;
      active.current?.abort();
    };
  }, [load]);
  useEffect(() => {
    if (!detail || detail.state !== 'PENDING') return;
    const timer = window.setInterval(() => setRemaining(Math.max(0, anchor.current.millis - (performance.now() - anchor.current.received))), 1000);
    return () => window.clearInterval(timer);
  }, [detail]);

  const send = async (operation: ApprovalOperation) => {
    if (busy) return;
    const controller = new AbortController();
    active.current?.abort();
    active.current = controller;
    const version = ++generation.current, credential = currentAiCredential();
    setBusy(true);
    setError(null);
    try {
      const {body} = await decideToolApproval(operation, {signal: controller.signal});
      if (controller.signal.aborted || version !== generation.current || credential !== currentAiCredential()) return;
      if (!removeValue(storageKey(context, `tool-approval-${operation.body.approvalId}`))) {
        setError('决定已提交，恢复记录清理失败；保留原键继续确认即可。');
      } else setPending(null);
      setNotice(body.data.state === 'APPROVED' ? '批准决定已提交，原调用等待排队／执行。批准不代表执行成功。' : '拒绝决定已提交。');
      setBusy(false);
      await load();
      onDecided?.();
    } catch (failure) {
      if (!controller.signal.aborted && version === generation.current && credential === currentAiCredential())
        setError(`${toolFailure(failure)} 决定结果尚待确认，请用原命令继续确认，勿改为相反决定。`);
    } finally {
      if (!controller.signal.aborted && credential === currentAiCredential()) setBusy(false);
    }
  };
  const decide = async (decision: ApprovalDecision) => {
    if (!detail || pending || busy || storageBlocked || !operationKey) return;
    const digest = detail.subjectDigest, version = detail.version;
    const opened = generation.current, credential = currentAiCredential();
    const confirmed = await modal.confirm({
      title: decision === 'APPROVE' ? '批准这份固定操作？' : '拒绝这份固定操作？',
      content: '请核对下方实际参数、配置、版本及执行期限。决定只适用于本次固定内容。',
      okText: decision === 'APPROVE' ? '批准' : '拒绝',
      cancelText: '返回检查'
    });
    if (!confirmed || opened !== generation.current || credential !== currentAiCredential()) return;
    const operation: ApprovalOperation = {
      key: crypto.randomUUID(), body: {
        scope: context.scope, approvalId: detail.approvalId,
        expectedVersion: version, subjectDigest: digest, decision
      }
    };
    if (!writeValue(operationKey, operation)) {
      setError('无法保存决定恢复记录，尚未发送；请检查浏览器存储。');
      return;
    }
    setPending(operation);
    void send(operation);
  };
  return <Card size="small" title="人工确认／审批"
               extra={<Button disabled={busy} onClick={() => void load()}>重新读取审批</Button>}>
    {modalContext}
    <Flex vertical gap={12}>
      {error && <Alert type="warning" title={error}/>}
      {notice && <Alert type="success" title={notice}/>}
      {pending && <Alert type="warning" title="有一份已保存的决定待确认"
                         description="刷新页面或请求超时不会生成新决定。继续确认会沿用原版本、摘要、决定和幂等键。"
                         action={<Button loading={busy} onClick={() => void send(pending)}>继续确认原决定</Button>}/>}
      {pending && detail && detail.state !== 'PENDING' && <Button disabled={busy} onClick={() => {
        if (operationKey && removeValue(operationKey)) setPending(null);
      }}>保留当前终态并结束决定确认</Button>}
      {detail && <>
        <Descriptions column={1} size="small" items={[
          {key: 'state', label: '审批状态', children: states[detail.state]},
          {key: 'execution', label: '原调用状态', children: executionStates[detail.invocationState] ?? '状态待读取'},
          {
            key: 'approvalId',
            label: '审批编号',
            children: <Typography.Text copyable code>{detail.approvalId}</Typography.Text>
          },
          {
            key: 'invocationId',
            label: '原调用编号',
            children: <Typography.Text copyable code>{detail.invocationId}</Typography.Text>
          },
          {key: 'title', label: '工具', children: `${detail.title} / ${detail.toolVersion}`},
          {key: 'binding', label: '固定配置版本', children: detail.bindingVersion},
          {
            key: 'mode',
            label: '决定方式',
            children: detail.mode === 'REQUESTER_CONFIRMATION' ? '发起者本人确认' : '其他合格主体独立审批'
          },
          {key: 'requester', label: '原发起主体', children: detail.requesterSubjectId},
          {key: 'reviewers', label: '指定审批主体', children: detail.approverSubjectIds.join('、') || '原发起者本人'},
          {
            key: 'remaining',
            label: '剩余窗口',
            children: detail.state === 'PENDING' ? `${Math.ceil(remaining / 1000)} 秒（以服务端决定为准）` : '窗口已结束'
          },
          {key: 'expiry', label: '决定截止', children: detail.expiresAt},
          {key: 'deadline', label: '原执行截止', children: detail.executeNotAfter},
          {
            key: 'effect',
            label: '副作用',
            children: detail.sideEffect === 'READ_ONLY' ? '只读，无业务写入' : detail.sideEffect
          },
          {key: 'by', label: '决定者', children: detail.decidedBy || '尚无人工决定'},
        ]}/>
        <Typography.Text strong>实际参数</Typography.Text>
        <pre className="tool-json">{detail.argumentsJson}</pre>
        <Typography.Text strong>有效配置</Typography.Text>
        <pre className="tool-json">{detail.configurationJson}</pre>
        <Typography.Text
          type="secondary">批准不会改变原发起者、权限或期限。到期后无法延长本次调用；拒绝、到期与取消不会执行工具。</Typography.Text>
        {detail.state === 'PENDING' && remaining <= 0 &&
          <Alert type="warning" title="决定窗口已结束，请重新读取服务端状态。"/>}
        {detail.mayDecide && detail.state === 'PENDING' && remaining > 0 && !pending ? <Space wrap>
          <Button type="primary" loading={busy} disabled={storageBlocked}
                  onClick={() => void decide('APPROVE')}>核对并批准</Button>
          <Button danger disabled={busy || storageBlocked} onClick={() => void decide('REJECT')}>拒绝本次操作</Button>
        </Space> : detail.state === 'PENDING' && remaining > 0 && !pending && <Alert type="info"
                                                                                     title={detail.requester ? '等待指定审批主体处理；可在原调用中取消。' : '当前无决定资格，可重新读取最新状态。'}/>}
      </>}
    </Flex>
  </Card>;
}
