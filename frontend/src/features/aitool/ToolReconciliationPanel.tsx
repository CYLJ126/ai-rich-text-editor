import React, {useEffect, useRef, useState} from 'react';
import {Alert, Button, Card, Collapse, Descriptions, Flex, Input, Modal, Space, Typography,} from 'antd';
import {z} from 'zod';
import {type BrowserContext, readValue, removeValue, storageKey, writeValue,} from '@/features/ainew/storage';
import {currentAiCredential} from '@/services/arte-ai/request';
import {AiApiError} from '@/services/arte-ai';
import {
  getToolOutcomeEvidence,
  getToolReconciliation,
  sendToolReview,
  type ToolReviewDetail,
  type ToolReviewEvidence,
  type ToolReviewOperation,
  type ToolReviewPath,
} from '@/services/arte-ai/toolReconciliation';

const states: Record<string, string> = {
  CHECKING: '自动检查中',
  NEEDS_REVIEW: '等待人工核对',
  UNRESOLVABLE: '现有证据无法判定',
  RESOLVED: '核对已完成',
};
const pendingSchema = z.object({
  key: z.string().min(1).max(256),
  path: z.enum([
    'claimReconciliation',
    'requestOutcomeCheck',
    'confirmOutcome',
    'markOutcomeUnresolvable',
  ]),
  requestJson: z.string().min(2).max(65536),
});
const failureText = (e: unknown) =>
  e instanceof AiApiError
    ? e.httpStatus === 403
      ? '当前没有核对或空间治理资格，请联系空间负责人。'
      : e.httpStatus === 404
        ? '核对记录不可见，或此服务尚未启用。'
        : e.httpStatus === 409
          ? '核对记录、租约或版本已变化，请刷新核对；原命令不能换键重发。'
          : '核对请求未能确认，请检查原操作与服务状态。'
    : '网络中断，核对命令结果尚待确认。';

/** 每个账号／空间／原调用独立恢复，传输失败只允许同一原命令重放。 */
export default function ToolReconciliationPanel(props: {
  context: BrowserContext;
  invocationId: string;
  managed?: boolean;
}) {
  const {context, invocationId, managed = false} = props;
  return (
    <ReviewContent
      key={`${context.userId}:${context.scope.tenantId}:${context.scope.workspaceId}:${invocationId}:${managed}`}
      {...props}
    />
  );
}

function ReviewContent({
                         context,
                         invocationId,
                         managed = false,
                       }: {
  context: BrowserContext;
  invocationId: string;
  managed?: boolean;
}) {
  const target = storageKey(context, 'tool-review-command', invocationId);
  const [initial] = useState(() => {
    const saved = readValue<unknown>(target);
    const parsed = pendingSchema.safeParse(saved.value);
    let valid = false;
    if (parsed.success) {
      try {
        const r = JSON.parse(parsed.data.requestJson);
        valid =
          r.invocationId === invocationId &&
          r.scope.tenantId === context.scope.tenantId &&
          r.scope.workspaceId === context.scope.workspaceId;
      } catch {
        /* fail closed */
      }
    }
    return {
      pending: valid && parsed.success ? parsed.data : null,
      blocked: !!saved.warning || (saved.value !== null && !valid),
    };
  });
  const [pending, setPending] = useState<ToolReviewOperation | null>(
    initial.pending,
  );
  const [blocked, setBlocked] = useState(initial.blocked);
  const [detail, setDetail] = useState<ToolReviewDetail | null>(null);
  const [evidence, setEvidence] = useState<ToolReviewEvidence | null>(null);
  const [reason, setReason] = useState('');
  const [nextStep, setNextStep] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(
    initial.blocked ? '恢复记录无法读取，暂不发送新命令。' : null,
  );
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const active = useRef<AbortController | null>(null),
    mounted = useRef(true),
    credential = useRef(currentAiCredential());
  const [modal, modalContext] = Modal.useModal();
  const sending = useRef(false),
    starting = useRef(false);
  const valid = () =>
    mounted.current && credential.current === currentAiCredential();
  const load = async () => {
    if (!valid() || busy) return;
    const controller = new AbortController();
    active.current?.abort();
    active.current = controller;
    setEvidence(null);
    try {
      const {body} = await getToolReconciliation(
        context.scope,
        invocationId,
        managed,
        {signal: controller.signal},
      );
      if (!valid() || controller.signal.aborted) return;
      setDetail(body.data);
      setError(initial.blocked ? '恢复记录无法读取，暂不发送新命令。' : null);
    } catch (e) {
      if (valid() && !controller.signal.aborted) {
        setDetail(null);
        setError(failureText(e));
      }
    }
  };
  useEffect(() => {
    mounted.current = true;
    void load();
    return () => {
      mounted.current = false;
      active.current?.abort();
    };
  }, []);
  const send = async (operation: ToolReviewOperation) => {
    if (!valid() || busy || blocked || sending.current) return;
    sending.current = true;
    const controller = new AbortController();
    active.current?.abort();
    active.current = controller;
    setBusy(true);
    setError(null);
    try {
      const {body} = await sendToolReview(operation, {
        signal: controller.signal,
      });
      if (!valid() || controller.signal.aborted) return;
      if (removeValue(target)) setPending(null);
      else {
        setBlocked(true);
        setError('命令已保存，但恢复记录清理失败；请保留原命令。');
      }
      setNotice(
        body.data.outcome === 'EVIDENCE_CONFLICT'
          ? '证据冲突已登记，原确认结果未被覆盖，请继续复核。'
          : `命令已保存；原调用状态：${body.data.invocationState}。`,
      );
      const result = await getToolReconciliation(
        context.scope,
        invocationId,
        managed,
        {signal: controller.signal},
      );
      if (valid() && !controller.signal.aborted) {
        setDetail(result.body.data);
        setEvidence(null);
      }
    } catch (e) {
      if (valid() && !controller.signal.aborted) {
        if (e instanceof AiApiError && [400, 409].includes(e.httpStatus)) {
          if (removeValue(target)) setPending(null);
          else setBlocked(true);
          setDetail(null);
          setEvidence(null);
          setError('服务端已拒绝本次命令；请刷新原记录，重新核验证据。');
        } else setError(`${failureText(e)} 请确认原命令，不创建相反决定。`);
        if (e instanceof AiApiError && [401, 403, 404].includes(e.httpStatus))
          setDetail(null);
      }
    } finally {
      sending.current = false;
      if (valid() && !controller.signal.aborted) setBusy(false);
    }
  };
  const start = async (path: ToolReviewPath) => {
    if (!detail || pending || blocked || busy || !valid() || starting.current)
      return;
    if (
      !reason.trim() ||
      !nextStep.trim() ||
      (path === 'confirmOutcome' && !note.trim())
    ) {
      setError('请填写核验原因、下一步，以及确认时的核验记录。');
      return;
    }
    starting.current = true;
    try {
      const confirmed = await modal.confirm({
        title:
          path === 'confirmOutcome'
            ? '确认这份原操作证据？'
            : path === 'markOutcomeUnresolvable'
              ? '记录现有证据无法判定？'
              : '保存本次核对操作？',
        content:
          path === 'markOutcomeUnresolvable'
            ? '原调用将继续保持 UNKNOWN，业务结果及费用不会被改成失败。'
            : '此操作只处理原调用，不重新执行工具。请核对原编号、参数、责任方和证据。',
        okText: '确认保存',
        cancelText: '返回检查',
      });
      if (!confirmed || !valid()) return;
      const body = {
        scope: context.scope,
        invocationId,
        attemptId: detail.attemptId,
        operationKey: detail.operationKey,
        expectedVersion: detail.version,
        expectedInvocationVersion: detail.invocationVersion,
        reason,
        nextStep,
        ...(path === 'confirmOutcome' && evidence
          ? {
            evidence: {
              ...evidence,
              source: 'DOMAIN_RECEIPT',
              verificationNote: note,
            },
          }
          : {}),
      };
      const operation: ToolReviewOperation = {
        key: crypto.randomUUID(),
        path,
        requestJson: JSON.stringify(body),
      };
      if (!writeValue(target, operation)) {
        setBlocked(true);
        setError('无法保存命令恢复记录，尚未发送。');
        return;
      }
      setPending(operation);
      await send(operation);
    } finally {
      starting.current = false;
    }
  };
  const verify = async () => {
    if (!valid() || busy || pending) return;
    const controller = new AbortController();
    active.current?.abort();
    active.current = controller;
    setBusy(true);
    try {
      const {body} = await getToolOutcomeEvidence(
        context.scope,
        invocationId,
        {signal: controller.signal},
      );
      if (valid() && !controller.signal.aborted) setEvidence(body.data);
    } catch (e) {
      if (valid() && !controller.signal.aborted) setError(failureText(e));
    } finally {
      if (valid() && !controller.signal.aborted) setBusy(false);
    }
  };
  return (
    <Card
      size="small"
      title="原调用结果核对"
      extra={
        <Button disabled={busy} onClick={() => void load()}>
          刷新核对进度
        </Button>
      }
    >
      {modalContext}
      <Flex vertical gap={12}>
        {error && <Alert type="warning" title={error}/>}
        {notice && <Alert type="info" title={notice}/>}
        {pending && (
          <Alert
            type="warning"
            title="原核对命令尚待确认"
            action={
              <Button
                disabled={busy || blocked || !detail}
                onClick={() => void send(pending)}
              >
                确认原核对命令
              </Button>
            }
          />
        )}
        {detail && (
          <>
            <Descriptions
              size="small"
              column={1}
              items={[
                {
                  key: 'state',
                  label: '核对进度',
                  children: states[detail.state],
                },
                {
                  key: 'invocationState',
                  label: '原调用状态',
                  children: ({
                    UNKNOWN: '结果仍未知',
                    SUCCEEDED: '已确认成功',
                    FAILED: '已确认失败',
                    CANCELLED: '已确认未执行并取消',
                  } as Record<string, string>)[detail.invocationState] ?? detail.invocationState,
                },
                {
                  key: 'id',
                  label: '原调用编号',
                  children: detail.invocationId,
                },
                {key: 'tool', label: '工具', children: detail.title},
                {
                  key: 'count',
                  label: '自动检查',
                  children: `${detail.checks} / ${detail.maxChecks} 次；窗口截止 ${detail.autoExpiresAt}`,
                },
                {
                  key: 'next',
                  label: '下一自动检查',
                  children:
                    detail.state === 'CHECKING'
                      ? detail.nextCheckAt
                      : '自动检查已停止',
                },
                {
                  key: 'owner',
                  label: '处理责任方',
                  children: detail.unassigned
                    ? '当前空间核对责任池（尚未认领，请联系空间负责人）'
                    : detail.responsibleSubjectId,
                },
                {key: 'due', label: '处理截止', children: detail.dueAt},
                {key: 'step', label: '下一步', children: detail.nextStep},
              ]}
            />
            {detail.overdue && detail.state !== 'RESOLVED' && (
              <Alert
                type="warning"
                title="此项核对已超期，请联系当前责任方处理"
              />
            )}
            {detail.state === 'UNRESOLVABLE' && (
              <Alert
                type="warning"
                title="现有证据无法判定；原调用仍为 UNKNOWN，不提供重试写入"
              />
            )}
            <Collapse
              items={[
                {
                  key: 'args',
                  label: '原操作参数（固定）',
                  children: <pre>{detail.argumentsJson}</pre>,
                },
              ]}
            />
            {managed && (
              <>
                <Input.TextArea
                  aria-label="核对原因"
                  placeholder="核对原因，不填写凭据"
                  maxLength={1000}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
                <Input.TextArea
                  aria-label="核对下一步"
                  placeholder="处理下一步及联系安排"
                  maxLength={1000}
                  value={nextStep}
                  onChange={(e) => setNextStep(e.target.value)}
                />
                <Space wrap>
                  {detail.mayClaim && (
                    <Button
                      disabled={busy || !!pending || blocked}
                      onClick={() => void start('claimReconciliation')}
                    >
                      认领给本人
                    </Button>
                  )}
                  {detail.state === 'CHECKING' && (
                    <Button
                      disabled={busy || !!pending || blocked}
                      onClick={() => void start('requestOutcomeCheck')}
                    >
                      请求检查原结果
                    </Button>
                  )}
                  {detail.mayConfirm && (
                    <>
                      <Button
                        disabled={
                          busy ||
                          !!pending ||
                          blocked ||
                          !detail.evidenceSupported
                        }
                        onClick={() => void verify()}
                      >
                        核验原领域回执
                      </Button>
                      <Button
                        disabled={busy || !!pending || blocked}
                        onClick={() => void start('markOutcomeUnresolvable')}
                      >
                        记录无法判定
                      </Button>
                    </>
                  )}
                </Space>
                {evidence && (
                  <Alert
                    type={
                      evidence.decision === 'STILL_UNKNOWN' ? 'warning' : 'info'
                    }
                    title={
                      evidence.decision === 'STILL_UNKNOWN'
                        ? '未找到可确认的原回执，不能据此声明未执行'
                        : '已读取原领域回执，请人工核验后确认'
                    }
                    description={
                      <Flex vertical>
                        <Typography.Text>{evidence.reference}</Typography.Text>
                        <Typography.Text>{evidence.digest}</Typography.Text>
                        <pre>{evidence.outputJson}</pre>
                      </Flex>
                    }
                  />
                )}
                {detail.mayConfirm &&
                  evidence &&
                  evidence.decision !== 'STILL_UNKNOWN' && (
                    <>
                      <Input.TextArea
                        aria-label="证据核验记录"
                        maxLength={1000}
                        value={note}
                        onChange={(e) => setNote(e.target.value)}
                        placeholder="说明核验的记录、范围和依据"
                      />
                      <Button
                        type="primary"
                        disabled={busy || !!pending || blocked}
                        onClick={() => void start('confirmOutcome')}
                      >
                        确认原操作证据
                      </Button>
                    </>
                  )}
                <Collapse
                  items={[
                    {
                      key: 'history',
                      label: '最近 50 条核对审计',
                      children: detail.history.map((h) => (
                        <div key={h.commandKey}>
                          <Typography.Text>
                            {h.action} · {h.outcome} · {h.actorId}
                          </Typography.Text>
                          <pre>{h.note}</pre>
                          <Typography.Paragraph>
                            {h.nextStep}
                          </Typography.Paragraph>
                        </div>
                      )),
                    },
                  ]}
                />
              </>
            )}
          </>
        )}
      </Flex>
    </Card>
  );
}
