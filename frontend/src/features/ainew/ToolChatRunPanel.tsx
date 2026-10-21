import React, {useEffect, useRef, useState} from 'react';
import {Alert, Button, Collapse, Descriptions, Flex, Modal, Tag, Typography,} from 'antd';
import {getRunActions, type RunActions, runTerminal, type ToolChatStatus,} from '@/services/arte-ai/toolChat';
import {currentAiCredential} from '@/services/arte-ai/request';
import ToolApprovalPanel from '@/features/aitool/ToolApprovalPanel';
import ToolReconciliationPanel from '@/features/aitool/ToolReconciliationPanel';
import {type BrowserContext} from './storage';
import {useToolChatRun} from './useToolChatRun';
import MarkdownText, {copyText} from './MarkdownText';

export const runStateNames: Record<string, string> = {
  ACCEPTED: '已受理',
  RUNNING: '模型生成中',
  WAITING_TOOL: '正在使用工具',
  WAITING_APPROVAL: '等待人工确认／审批',
  PENDING_RECONCILIATION: '操作结果待核对',
  SUCCEEDED: '回答完成',
  FAILED: '执行失败',
  CANCELLED: '已停止',
  TIMED_OUT: '已超时',
};
export type RunPreparation = {
  kind: 'rewrite' | 'refresh';
  actions: RunActions;
  answer: string;
};
/** Run 的过程、预算及人工控制独立于回答正文；任何恢复都不重新执行工具。 */
export default function ToolChatRunPanel({
                                           context,
                                           runId,
                                           onStatus,
                                           onSettled,
                                           onStop,
                                           stopPending,
                                           disabled,
                                           onPrepare,
                                           onContent,
                                           allowActions = true,
                                         }: {
  context: BrowserContext;
  runId: string;
  onStatus: (status: ToolChatStatus | null, error?: unknown) => void;
  onSettled: () => void;
  onStop: () => void;
  stopPending: boolean;
  disabled: boolean;
  onPrepare: (value: RunPreparation) => void;
  onContent?: () => void;
  allowActions?: boolean;
}) {
  const run = useToolChatRun(context, runId, onStatus);
  const status = run.status;
  const terminalVersion = useRef<number | null>(null);
  const settled = useRef(onSettled);
  settled.current = onSettled;
  const [notice, setNotice] = useState(''),
    [actionError, setActionError] = useState(''),
    [preparing, setPreparing] = useState(false);
  const [control, setControl] = useState<{
    id: string;
    kind: 'approval' | 'review';
  } | null>(null);
  const action = useRef<AbortController | null>(null);
  const [modal, modalContext] = Modal.useModal();
  useEffect(() => () => action.current?.abort(), []);
  useEffect(() => {
    if (
      status &&
      runTerminal(status.state) &&
      terminalVersion.current !== status.version &&
      (!status.resultAvailable || run.answer)
    ) {
      terminalVersion.current = status.version;
      settled.current();
    }
  }, [status?.state, status?.version, run.answer]);
  const content = useRef(onContent);
  content.current = onContent;
  useEffect(() => {
    content.current?.();
  }, [run.text, run.answer, run.status?.version, run.error]);
  const answer =
    run.answer?.result.value.outputs
      .flatMap((m) => m.content.flatMap((p) => ('text' in p ? [p.text] : [])))
      .join('\n\n') ?? '';

  async function prepare(kind: 'rewrite' | 'refresh' | 'write') {
    if (
      !status ||
      status.state !== 'SUCCEEDED' ||
      !run.answer ||
      action.current ||
      disabled
    )
      return;
    const controller = new AbortController(),
      credential = currentAiCredential();
    action.current = controller;
    setPreparing(true);
    setActionError('');
    try {
      const actions = (
        await getRunActions(context.scope, runId, {signal: controller.signal})
      ).body.data;
      if (controller.signal.aborted || credential !== currentAiCredential())
        return;
      if (actions.runId !== runId) throw new Error('Run actions mismatch');
      if (kind === 'write') {
        modal.info({
          title: '重新执行操作需要重新准备',
          content: (
            <>
              <Typography.Paragraph>
                请在工具页明确选择写操作、核对当前目标和参数、重新预览并提交审批。原
                Run 不会被重新发送。
              </Typography.Paragraph>
              <Typography.Paragraph>
                原允许集合中的写工具配置：
                {actions.writeBindingIds.join('、') || '无'}
                。允许集合不代表这些操作实际已执行。
              </Typography.Paragraph>
              <Button href="/AI/Tools">前往工具页准备新操作</Button>
            </>
          ),
        });
        return;
      }
      if (kind === 'refresh' && !actions.readOnlyBindingIds.length) {
        setActionError(
          '原允许集合没有只读工具。请在工具设置中明确选择可用的只读工具后提问。',
        );
        return;
      }
      const confirmed = await modal.confirm({
        title:
          kind === 'rewrite'
            ? '使用旧回答快照准备改写？'
            : '准备重新读取最新资料？',
        content:
          kind === 'rewrite'
            ? `仅使用原完整回答与原问题，不重放工具原始输出，不调用工具。旧回答生成于 ${actions.answeredAt}；未声明资料观测时间，可能过期，未重新核实来源和时效。将替换输入草稿；点击发送后产生模型费用。`
            : '将替换输入草稿，仅选择原集合中的只读工具。下一次发送创建新的工具聊天与费用，服务端重新校验当前配置和权限；写操作不会随资料刷新重做。',
        okText: '准备草稿',
        cancelText: '返回',
      });
      if (
        confirmed &&
        !controller.signal.aborted &&
        credential === currentAiCredential()
      )
        onPrepare({kind, actions, answer});
    } catch {
      if (!controller.signal.aborted && credential === currentAiCredential())
        setActionError('无法准备原回答，请重新读取原 Run 并检查当前权限。');
    } finally {
      if (action.current === controller) action.current = null;
      if (!controller.signal.aborted && credential === currentAiCredential())
        setPreparing(false);
    }
  }

  return (
    <Flex
      vertical
      gap={8}
      className="ainew-tool-chat"
      aria-label="工具聊天过程"
    >
      {modalContext}
      <Flex gap={8} wrap align="center">
        <Tag
          color={
            status?.state === 'SUCCEEDED'
              ? 'green'
              : status?.state === 'PENDING_RECONCILIATION'
                ? 'orange'
                : 'blue'
          }
        >
          {status ? runStateNames[status.state] : '正在读取工具聊天'}
        </Tag>
        {status && (
          <Typography.Text type="secondary">
            模型 {status.modelSteps} 步 · 工具 {status.toolSteps} 步
          </Typography.Text>
        )}
        <Button size="small" onClick={run.refresh}>
          {run.paused ? '恢复原 Run 观察' : '重新读取原 Run'}
        </Button>
        {status && !runTerminal(status.state) && (
          <Button
            size="small"
            disabled={stopPending || status.cancellationRequested}
            onClick={onStop}
          >
            {stopPending || status.cancellationRequested
              ? '停止请求已保存／待确认'
              : '停止本次工具聊天'}
          </Button>
        )}
      </Flex>
      {run.error != null && (
        <Alert
          type="warning"
          title="原 Run 状态或过程无法完整读取"
          description="原调用可能仍在运行，请恢复读取或保留编号后稍后查询。页面刷新和结束查看不会取消或重发操作。"
        />
      )}
      {run.paused && (
        <Alert type="info" title="本次自动观察已暂停，可继续读取原 Run"/>
      )}
      {status?.cancellationRequested && !runTerminal(status.state) && (
        <Alert
          type="info"
          title="停止请求已保存，正在等待执行器处理"
          description="这不证明业务操作已停止或回滚；已发生费用和结果待核对事项仍保留。"
        />
      )}
      {status?.state === 'PENDING_RECONCILIATION' && (
        <Alert
          type="warning"
          title="有操作结果尚未确定，不可重试写操作"
          description="请查看原子调用核对进展或联系空间负责人。核对完成后，服务端在原期限内继续原流程。"
        />
      )}
      {status?.error && (
        <Alert
          type="warning"
          title={status.error.code}
          description={`副作用：${status.error.sideEffect}；确定性：${status.error.certainty}；追踪编号：${status.error.correlationId}`}
        />
      )}
      {run.answer ? (
        run.answer.result.value.outputs.map((message) => (
          <div key={message.messageId} data-message-id={message.messageId}>
            <MarkdownText
              invocationId={run.answer?.finalInvocationId}
              text={message.content
                .flatMap((p) => ('text' in p ? [p.text] : []))
                .join('\n')}
            />
          </div>
        ))
      ) : run.text ? (
        <>
          <MarkdownText text={run.text} streaming/>
          <Typography.Text type="secondary">
            本轮模型文字预览，最终回答以 Run 完整结果为准。
          </Typography.Text>
        </>
      ) : null}
      {answer && (
        <Flex wrap gap={8}>
          <Button size="small" onClick={() => void copyText(answer, setNotice)}>
            复制回答
          </Button>
          {allowActions && (
            <>
              <Button
                size="small"
                disabled={disabled || preparing}
                onClick={() => void prepare('rewrite')}
              >
                只重写回答
              </Button>
              <Button
                size="small"
                disabled={disabled || preparing}
                onClick={() => void prepare('refresh')}
              >
                重新读取最新数据
              </Button>
              <Button
                size="small"
                disabled={disabled || preparing}
                onClick={() => void prepare('write')}
              >
                重新执行操作…
              </Button>
            </>
          )}
        </Flex>
      )}
      {notice && <Typography.Text role="status">{notice}</Typography.Text>}
      {actionError && <Alert type="warning" title={actionError}/>}
      <Collapse
        ghost
        size="small"
        items={[
          {
            key: 'process',
            label: '过程与本次预算',
            children: (
              <Flex vertical gap={8}>
                <Typography.Text copyable code>
                  {runId}
                </Typography.Text>
                {run.budgets.map((b) => (
                  <Descriptions
                    key={b.currency}
                    column={1}
                    size="small"
                    items={[
                      {
                        key: 'limit',
                        label: `${b.currency} 总上限`,
                        children: b.limit,
                      },
                      {key: 'charged', label: '已扣', children: b.charged},
                      {
                        key: 'held',
                        label: '预留／待核对占用',
                        children: b.held,
                      },
                      {
                        key: 'available',
                        label: '本次剩余',
                        children: b.available,
                      },
                      {
                        key: 'blocked',
                        label: '新增派发',
                        children: b.blocked
                          ? '预算异常，已阻断'
                          : '仍受账户及本次上限约束',
                      },
                    ]}
                  />
                ))}
                <Typography.Text type="secondary">
                  Run
                  已结束也可能保留未知费用。金额按币种独立计算，不用账户余额差推算。
                </Typography.Text>
                {run.events.map((event) => (
                  <Typography.Paragraph key={event.sequence}>
                    #{event.sequence} · {runStateNames[event.state]} · 第{' '}
                    {event.checkpoint.roundIndex + 1} 轮 · {event.occurredAt}
                  </Typography.Paragraph>
                ))}
              </Flex>
            ),
          },
        ]}
      />
      {status?.checkpoint.children.map((id) => (
        <Flex key={id} gap={8} wrap>
          <Typography.Text type="secondary" copyable>
            {id}
          </Typography.Text>
          {status.state === 'WAITING_APPROVAL' && (
            <Button
              size="small"
              onClick={() => setControl({id, kind: 'approval'})}
            >
              查看此子调用确认／审批
            </Button>
          )}
          {status.state === 'PENDING_RECONCILIATION' && (
            <Button
              size="small"
              onClick={() => setControl({id, kind: 'review'})}
            >
              查看原子调用核对
            </Button>
          )}
        </Flex>
      ))}
      {control && (
        <>
          <Button onClick={() => setControl(null)}>收起人工控制详情</Button>
          {control.kind === 'approval' ? (
            <ToolApprovalPanel
              key={control.id}
              context={context}
              invocationId={control.id}
              onDecided={run.refresh}
            />
          ) : (
            <ToolReconciliationPanel
              context={context}
              invocationId={control.id}
            />
          )}
        </>
      )}
    </Flex>
  );
}
