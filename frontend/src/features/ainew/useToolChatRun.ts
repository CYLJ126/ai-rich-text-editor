import {useEffect, useRef, useState} from 'react';
import {
  AiApiError,
  getInvocationSnapshot,
  watchInvocation,
} from '@/services/arte-ai';
import {currentAiCredential} from '@/services/arte-ai/request';
import {
  getRunBudget,
  getRunResult,
  getRunStatus,
  replayRunEvents,
  runTerminal,
  type RunAnswer,
  type RunBudget,
  type RunEvent,
  type ToolChatStatus,
} from '@/services/arte-ai/toolChat';
import type {BrowserContext} from './storage';

export const RUN_OBSERVATION_MS = 5 * 60_000;

/** Run 使用独立游标和串行有界轮询；文字订阅只使用检查点中的真实模型子 ID。 */
export function useToolChatRun(
  context: BrowserContext,
  runId: string,
  onStatus: (status: ToolChatStatus | null, error?: unknown) => void,
) {
  const [status, setStatus] = useState<ToolChatStatus | null>(null);
  const [answer, setAnswer] = useState<RunAnswer | null>(null);
  const [budgets, setBudgets] = useState<RunBudget[]>([]);
  const [events, setEvents] = useState<RunEvent[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [paused, setPaused] = useState(false);
  const [text, setText] = useState('');
  const [revision, refresh] = useState(0);
  const notify = useRef(onStatus);
  notify.current = onStatus;
  const latest = useRef<ToolChatStatus | null>(null);
  const final = useRef<RunAnswer | null>(null);
  const cursor = useRef(0);
  const balances = useRef<RunBudget[]>([]);
  useEffect(() => {
    const controller = new AbortController(),
      credential = currentAiCredential();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let stream: AbortController | null = null,
      child: string | null = null;
    let preview = '',
      committed = '',
      attempt: string | undefined;
    let failures = 0;
    let lastBudgetRead = 0;
    const started = Date.now();
    const valid = () =>
      !controller.signal.aborted && credential === currentAiCredential();
    const stopStream = () => {
      stream?.abort();
      stream = null;
      child = null;
      setText('');
    };
    const previewText = (offset: number, value: string) => {
      if (
        !valid() ||
        offset > preview.length ||
        offset + value.length > 1_000_000
      )
        return;
      const overlap = Math.min(value.length, preview.length - offset);
      if (preview.slice(offset, offset + overlap) !== value.slice(0, overlap))
        return;
      preview += value.slice(overlap);
      setText(preview);
    };
    const tick = async () => {
      if (!valid()) return;
      try {
        const value = (
          await getRunStatus(context.scope, runId, {
            signal: controller.signal,
          })
        ).body.data;
        if (!valid()) return;
        if (value.runId !== runId) throw new Error('Run identity mismatch');
        const advanced = latest.current?.version !== value.version;
        if (!latest.current || value.version >= latest.current.version) {
          // cancellationRequested 独立于根版本；旧的同版本查询不能清除已保存的停止事实。
          latest.current = {
            ...value,
            cancellationRequested:
              value.cancellationRequested ||
              !!latest.current?.cancellationRequested,
          };
          setStatus(latest.current);
          notify.current(latest.current);
        }
        const current = latest.current;
        if (!current) return;
        if (current.checkpoint.phase === 'WAITING_MODEL') {
          const id = current.checkpoint.children[0];
          if (id && child !== id) {
            stopStream();
            child = id;
            stream = new AbortController();
            preview = '';
            committed = '';
            attempt = undefined;
            const source = stream;
            // 建连失败由权威查询继续恢复，不重新提交模型调用。
            void watchInvocation(
              {scope: context.scope, invocationId: id, afterSequence: 0},
              (event) => {
                if (!valid() || source.signal.aborted || child !== id) return;
                if (event.kind === 'OUTPUT' && event.text) {
                  previewText(committed.length, event.text);
                  committed += event.text;
                }
              },
              {
                signal: source.signal,
                onText: (delta) => {
                  if (!valid() || source.signal.aborted || child !== id) return;
                  if (attempt && attempt !== delta.attemptId) return;
                  attempt = delta.attemptId;
                  previewText(delta.offset, delta.text);
                },
              },
            ).catch(() => {
              /* 已落库结果仍从原子调用读取。 */
            });
          }
          const snapshot = await getInvocationSnapshot(
            {scope: context.scope, invocationId: id},
            {signal: controller.signal},
          );
          if (!valid()) return;
          if (
            !snapshot.missing &&
            snapshot.result?.kind === 'GENERATION' &&
            snapshot.result.result.value.finishReason !== 'TOOL_CALLS'
          ) {
            preview = snapshot.result.result.value.outputs
              .flatMap((m) =>
                m.content.flatMap((p) => ('text' in p ? [p.text] : [])),
              )
              .join('\n');
            setText(preview);
          }
        } else stopStream();
        if (current.resultAvailable && !final.current) {
          const result = (
            await getRunResult(context.scope, runId, {
              signal: controller.signal,
            })
          ).body.data;
          if (!valid()) return;
          if (result.runId !== runId || !result.result.value.complete)
            throw new Error('Invalid Run answer');
          final.current = result;
          setAnswer(result);
        }
        const readBudget = advanced || Date.now() - lastBudgetRead >= 10_000;
        const outcomes = await Promise.allSettled([
          readBudget
            ? getRunBudget(context.scope, runId, {signal: controller.signal})
            : Promise.resolve(null),
          cursor.current < current.version + 1
            ? replayRunEvents(context.scope, runId, cursor.current, {
              signal: controller.signal,
            })
            : Promise.resolve(null),
        ]);
        if (!valid()) return;
        if (outcomes[0].status === 'fulfilled' && outcomes[0].value) {
          const next = outcomes[0].value.body.data;
          if (next.some((b) => b.runId !== runId))
            throw new Error('Run budget identity mismatch');
          balances.current = next.map(
            (b) =>
              balances.current.find(
                (old) => old.currency === b.currency && old.version > b.version,
              ) ?? b,
          );
          setBudgets(balances.current);
          lastBudgetRead = Date.now();
        }
        if (outcomes[1].status === 'fulfilled' && outcomes[1].value) {
          const page = outcomes[1].value.body.data;
          let sequence = cursor.current;
          for (const event of page) {
            if (event.runId !== runId || event.sequence !== sequence + 1)
              throw new Error('Run event sequence gap');
            sequence = event.sequence;
          }
          cursor.current = sequence;
          if (page.length)
            setEvents((previous) => [...previous, ...page].slice(-100));
        }
        const rejected = outcomes.find((o) => o.status === 'rejected');
        if (rejected?.status === 'rejected') throw rejected.reason;
        failures = 0;
        setError(null);
        if (
          runTerminal(current.state) &&
          (!current.resultAvailable || final.current) &&
          !balances.current.some((b) => /[1-9]/.test(b.held)) &&
          cursor.current >= current.version + 1
        )
          return;
      } catch (failure) {
        if (!valid()) return;
        setError(failure);
        notify.current(latest.current, failure);
        failures++;
        if (
          failure instanceof AiApiError &&
          [401, 403, 404].includes(failure.httpStatus)
        ) {
          if ([401, 403].includes(failure.httpStatus)) {
            setAnswer(null);
            final.current = null;
            setBudgets([]);
            setEvents([]);
            setStatus(null);
          }
          stopStream();
          setPaused(true);
          return;
        }
      }
      if (!valid()) return;
      if (Date.now() - started >= RUN_OBSERVATION_MS || failures >= 5) {
        stopStream();
        setPaused(true);
        return;
      }
      const wait = failures
        ? Math.min(30_000, 1000 * 2 ** failures)
        : runTerminal(latest.current?.state ?? 'ACCEPTED') ||
        latest.current?.state === 'PENDING_RECONCILIATION'
          ? 10_000
          : 1500;
      timer = setTimeout(() => void tick(), wait);
    };
    setPaused(false);
    void tick();
    const logout = (event: Event) => {
      if ((event as CustomEvent).detail === context.userId) {
        controller.abort();
        stream?.abort();
        clearTimeout(timer);
        setAnswer(null);
        setText('');
        setBudgets([]);
        setEvents([]);
        setStatus(null);
      }
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      controller.abort();
      stream?.abort();
      clearTimeout(timer);
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [
    context.userId,
    context.scope.tenantId,
    context.scope.workspaceId,
    runId,
    revision,
  ]);
  return {
    status,
    answer,
    budgets,
    events,
    text,
    error,
    paused,
    refresh: () => refresh((value) => value + 1),
  };
}
