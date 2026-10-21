import {useEffect, useRef, useState} from 'react';
import {beginChatLatency, markChatLatency} from '@/services/arte-ai/latency';
import {
  AiApiError,
  type BudgetAccountResponse,
  cancelInvocation,
  type ConversationResponse,
  type ConversationTurnResponse,
  getBudget,
  getConversation,
  getInvocationSnapshot,
  type InvocationResultResponse,
  type InvocationStatusResponse,
  queryTurnsOfConversation,
  regenerateChat,
  type RegenerateChatRequest,
  type SubmitChatRequest,
  turnsForChat,
  watchInvocation,
} from '@/services/arte-ai';
import type {ChatTestConfig} from './config';
import {
  type BrowserContext,
  type Operation,
  readOperation,
  readValue,
  saveOperation,
  STORAGE_WARNING,
  storageKey,
  writeValue
} from './storage';
import {type ConfigurationRejected, invalidatesChatConfiguration} from './configurationInvalidation';

export const HISTORY_PAGE_SIZE = 10;
/** SSE 不可用时的恢复查询间隔；正常连接不循环查询状态。 */
export const POLL_INTERVAL_MS = 10_000;

export function isActive(status: InvocationStatusResponse) {
  return ['ACCEPTED', 'WAITING_APPROVAL', 'QUEUED', 'RUNNING'].includes(status.state);
}

function needsObservation(status: InvocationStatusResponse) {
  return isActive(status) || (status.budgetState === 'RESERVED' && !isUserStoppedGeneration(status));
}

export function isUserStoppedGeneration(status?: InvocationStatusResponse) {
  return status?.kind === 'GENERATION' && status.state === 'UNKNOWN' &&
    status.error?.code === 'INVOCATION_CANCELLED';
}

function blocksConversation(status?: InvocationStatusResponse) {
  return status?.state === 'UNKNOWN' && !isUserStoppedGeneration(status);
}

function canReuseInvocation(view?: InvocationView) {
  const status = view?.status;
  return !!status && !view?.error && !isActive(status) &&
    status.state !== 'UNKNOWN' &&
    ['NOT_RESERVED', 'SETTLED', 'RELEASED'].includes(status.budgetState ?? '') &&
    (!status.resultAvailable || !!view?.result);
}

export function hasAvailableBudget(amount: string) {
  return /^\+?\d+(\.\d+)?$/.test(amount) && /[1-9]/.test(amount);
}

export interface InvocationView {
  status?: InvocationStatusResponse;
  result?: InvocationResultResponse;
  error?: unknown;
  /** 实时文字预览及已提交增量，完整结果优先展示；预览不证明执行成功。 */
  streamText?: string;
  streamAttemptId?: string;
}

interface PendingMessage {
  key: string;
  request: SubmitChatRequest;
}

interface InvocationRead {
  controller: AbortController;
  consumers: Set<symbol>;
  promise: Promise<InvocationView>;
}

interface HistoryRead {
  page?: number;
  controller: AbortController;
  promise: Promise<void>;
}

/** ChatPanel is keyed by conversation ID; the containing workspace is keyed by scope. */
export function useChatSession(
  config: ChatTestConfig,
  initial: ConversationResponse,
  onUpdated: (conversation: ConversationResponse) => void,
  maxInputBytes?: number,
  onConfigurationRejected?: ConfigurationRejected,
  context?: BrowserContext,
  sendingEnabled = true,
  writesAllowed = true,
  onTitleUpdated?: (conversationId: string, value: import('@/services/arte-ai').ConversationTitleMetadata) => void,
) {
  const rejected = useRef(onConfigurationRejected);
  rejected.current = onConfigurationRejected;
  const settings = useRef(config);
  settings.current = config;
  const titleUpdated = useRef(onTitleUpdated);
  titleUpdated.current = onTitleUpdated;
  const updated = useRef(onUpdated);
  updated.current = onUpdated;
  const metadata = useRef(initial);
  const scope = {tenantId: config.tenantId, workspaceId: config.workspaceId};
  const target = initial.conversationId;
  const [restored] = useState(() => ({
    send: readOperation<SubmitChatRequest>(context, 'send', target),
    regenerate: readOperation<RegenerateChatRequest>(context, 'regenerate', target),
    stop: readOperation<{ scope: typeof scope; invocationId: string }>(context, 'stop', target),
    draft: context ? readValue<string>(storageKey(context, 'draft', target)) : {value: null, warning: null},
  }));
  const recoveryBlocked = !!(restored.send.warning || restored.regenerate.warning || restored.stop.warning);
  const [storageWarning, setStorageWarning] = useState<string | null>(restored.send.warning || restored.regenerate.warning || restored.stop.warning || restored.draft.warning);
  const storageWarningRef = useRef(storageWarning);
  storageWarningRef.current = storageWarning;
  const acceptedOperations = useRef({
    send: restored.send.value?.acceptedId,
    regenerate: restored.regenerate.value?.acceptedId,
    stop: restored.stop.value?.acceptedId
  });
  const persist = <T, >(kind: 'send' | 'regenerate' | 'stop', value: Operation<T> | null) => {
    acceptedOperations.current[kind] = value?.acceptedId;
    if (!saveOperation(context, kind, target, value)) setStorageWarning(STORAGE_WARNING);
  };
  const [draft, setDraft] = useState(typeof restored.draft.value === 'string' ? restored.draft.value : '');
  const [turns, setTurns] = useState<ConversationTurnResponse[]>([]);
  const visibleInvocationIds = useRef(new Set<string>());
  visibleInvocationIds.current = new Set(turns.flatMap(turn => turn.invocationIds));
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [historyError, setHistoryError] = useState<unknown>(null);
  const [budget, setBudget] = useState<BudgetAccountResponse | null>(null);
  const [budgetLoading, setBudgetLoading] = useState(false);
  const [budgetError, setBudgetError] = useState<unknown>(null);
  const [invocations, setInvocations] = useState<
    Record<string, InvocationView>
  >({});
  const views = useRef<Record<string, InvocationView>>({});
  const invocationRevisions = useRef(new Map<string, number>());
  const invocationReads = useRef(new Map<string, InvocationRead>());
  const historyRevision = useRef(0);
  const historyRead = useRef<HistoryRead | null>(null);
  const firstHistory = useRef(!!context);
  const budgetRead = useRef<{ key: string; promise: Promise<void> } | null>(null);
  const budgetDirty = useRef(false);
  const budgetKey = useRef<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<unknown>(null);
  const [stopping, setStopping] = useState(false);
  const [stopRequested, setStopRequested] = useState<string | null>(restored.stop.value?.acceptedId ?? null);
  const [pendingStop, setPendingStop] = useState(restored.stop.value && !restored.stop.value.acceptedId ? restored.stop.value.request.invocationId : null);
  const [stopError, setStopError] = useState<unknown>(null);
  const stopKeys = useRef(new Map<string, string>(restored.stop.value ? [[restored.stop.value.request.invocationId, restored.stop.value.key]] : []));
  const stopTarget = useRef<string | null>(restored.stop.value?.request.invocationId ?? null);
  const restoredSend = restored.send.value?.acceptedId ? null : restored.send.value;
  const restoredRegeneration = restored.regenerate.value?.acceptedId ? null : restored.regenerate.value;
  const [pending, setPending] = useState<PendingMessage | null>(restoredSend);
  const [pendingRegeneration, setPendingRegeneration] = useState<{
    key: string;
    request: RegenerateChatRequest
  } | null>(restoredRegeneration);
  const regenerationRef = useRef<{ key: string; request: RegenerateChatRequest } | null>(restoredRegeneration);
  const pendingRef = useRef<PendingMessage | null>(restoredSend);
  const [invocationId, setInvocationId] = useState<string | null>(null);
  const [watching, setWatching] = useState(false);
  const [pollError, setPollError] = useState<unknown>(null);
  const [pollPaused, setPollPaused] = useState(false);
  const [activeIds, setActiveIds] = useState<string[]>([]);
  const watched = useRef<string[]>([]);
  const created = useRef<string | null>(restored.send.value?.acceptedId ?? restored.regenerate.value?.acceptedId ?? null);
  const timers = useRef(new Set<ReturnType<typeof setTimeout>>());
  const cursors = useRef(new Map<string, number>());
  const committedTextOffsets = useRef(new Map<string, number>());
  const pendingText = useRef(
    new Map<
      string,
      { attemptId?: string; chunks: Map<number, string>; chars: number }
    >(),
  );
  const refreshedTerminalVersions = useRef(new Map<string, number>());
  const expiredCursors = useRef(new Set<string>());
  const expiry = useRef<ReturnType<typeof setTimeout> | null>(null);
  const requests = useRef({
    history: null as AbortController | null,
    budget: null as AbortController | null,
    submit: null as AbortController | null,
    poll: null as AbortController | null,
    stop: null as AbortController | null,
    candidate: null as AbortController | null,
  });
  const alive = useRef(true);
  const textFrame = useRef<number | null>(null);

  function appendText(
    id: string,
    offset: number,
    text: string,
    attemptId?: string,
  ) {
    const previous = views.current[id];
    const pending = pendingText.current.get(id) ?? {
      attemptId,
      chunks: new Map<number, string>(),
      chars: 0,
    };
    if (
      previous?.result ||
      (attemptId &&
        ((pending.attemptId && pending.attemptId !== attemptId) ||
          (previous?.streamAttemptId &&
            previous.streamAttemptId !== attemptId) ||
          (previous?.status?.activeAttemptId &&
            previous.status.activeAttemptId !== attemptId)))
    )
      return;
    pending.attemptId ??= attemptId;
    let appended = previous?.streamText ?? '';
    // 短暂乱序先有界保存，前面的字到达后立即拼接；真的丢包仍由耐久 OUTPUT 补齐。
    if (offset > appended.length) {
      const existing = pending.chunks.get(offset);
      if (existing !== undefined && existing !== text)
        throw new Error('AI streamed text is inconsistent');
      if (
        existing === undefined &&
        pending.chunks.size < 512 &&
        pending.chars + text.length <= 131_072
      ) {
        pending.chunks.set(offset, text);
        pending.chars += text.length;
        pendingText.current.set(id, pending);
      }
      return;
    }
    const merge = (start: number, chunk: string) => {
      const overlap = Math.min(chunk.length, appended.length - start);
      if (appended.slice(start, start + overlap) !== chunk.slice(0, overlap))
        throw new Error('AI streamed text is inconsistent');
      appended += chunk.slice(overlap);
    };
    merge(offset, text);
    let progressed = true;
    while (progressed) {
      progressed = false;
      for (const [start, chunk] of pending.chunks) {
        if (start > appended.length) continue;
        merge(start, chunk);
        pending.chunks.delete(start);
        pending.chars -= chunk.length;
        progressed = true;
      }
    }
    if (!pending.chunks.size) pendingText.current.delete(id);
    if (appended.length > 1_000_000)
      throw new Error('AI streamed text is too large');
    if (appended === previous?.streamText) return;
    if (!previous?.streamText && appended.length) markChatLatency(id, 'firstUsableText');
    publish({
      [id]: {
        streamText: appended,
        ...(pending.attemptId ? {streamAttemptId: pending.attemptId} : {}),
      },
    }, !!previous?.streamText);
  }

  function publish(entries: Record<string, InvocationView>, byFrame = false) {
    for (const [id, entry] of Object.entries(entries)) {
      let view = entry;
      const previous = views.current[id];
      if (
        view.status &&
        previous?.status &&
        view.status.version < previous.status.version
      )
        continue;
      if (
        view.status &&
        previous?.status?.budgetState &&
        view.status.version === previous.status.version
      ) {
        const rank = {
          NOT_RESERVED: 0,
          RESERVED: 1,
          PENDING_RECONCILIATION: 2,
          SETTLED: 3,
          RELEASED: 3,
        };
        if (
          rank[view.status.budgetState ?? 'NOT_RESERVED'] <
          rank[previous.status.budgetState]
        )
          view = {
            ...view,
            status: {
              ...view.status,
              budgetState: previous.status.budgetState,
            },
          };
      }
      views.current[id] = {
        ...previous,
        ...(view.status ? {error: undefined} : {}),
        ...view,
      };
      if (view.result) pendingText.current.delete(id);
      if (view.status && !isActive(view.status)) {
        if (stopTarget.current === id) {
          setPendingStop(null);
          persist('stop', null);
        }
        if (acceptedOperations.current.stop === id) persist('stop', null);
        if (canReuseInvocation(views.current[id]) || isUserStoppedGeneration(view.status)) {
          if (acceptedOperations.current.send === id) persist('send', null);
          if (acceptedOperations.current.regenerate === id) persist('regenerate', null);
        }
      }
    }
    // Keep unresolved executions for the send gate, but bound settled history/result caches.
    const ids = Object.keys(views.current);
    let excess = ids.length - 256;
    for (const id of ids) {
      if (excess <= 0) break;
      if (visibleInvocationIds.current.has(id) || watched.current.includes(id) || invocationReads.current.has(id) || id === created.current || !canReuseInvocation(views.current[id])) continue;
      delete views.current[id];
      invocationRevisions.current.delete(id);
      cursors.current.delete(id);
      expiredCursors.current.delete(id);
      committedTextOffsets.current.delete(id);
      pendingText.current.delete(id);
      excess--;
    }
    const commit = () => {
      textFrame.current = null;
      if (!alive.current) return;
      views.current = {...views.current};
      setInvocations(views.current);
    };
    if (byFrame) {
      // 首段立即发布；后续片段共享同一个待执行帧，既合并高频更新，也不反复取消推迟绘制。
      if (textFrame.current === null) textFrame.current = requestAnimationFrame(commit);
    } else {
      if (textFrame.current !== null) cancelAnimationFrame(textFrame.current);
      commit();
    }
  }

  function invalidateInvocation(id: string) {
    invocationRevisions.current.set(
      id,
      (invocationRevisions.current.get(id) ?? 0) + 1,
    );
  }

  function readInvocation(
    id: string,
    signal: AbortSignal,
  ): Promise<InvocationView> {
    if (signal.aborted)
      return Promise.reject(new DOMException('Aborted', 'AbortError'));
    const cached = views.current[id];
    if (canReuseInvocation(cached)) return Promise.resolve(cached);
    let read = invocationReads.current.get(id);
    if (!read || read.controller.signal.aborted) {
      const controller = new AbortController();
      const pending: InvocationRead = {
        controller,
        consumers: new Set<symbol>(),
        promise: Promise.resolve().then(async () => {
          let revision: number;
          do {
            revision = invocationRevisions.current.get(id) ?? 0;
            const snapshot = await getInvocationSnapshot({
              scope, invocationId: id,
              knownResultVersion: views.current[id]?.result ? views.current[id]?.status?.version : undefined
            }, {signal: controller.signal});
            if (snapshot.missing || !snapshot.status || snapshot.status.invocationId !== id)
              throw new Error('Invocation unavailable');
            const body = {data: snapshot.status};
            const result = snapshot.result ?? (views.current[id]?.status?.version === body.data.version ? views.current[id]?.result : undefined);
            if (body.data.resultAvailable && !isActive(body.data) && !result)
              throw new Error('Committed invocation result unavailable');
            if (controller.signal.aborted || !alive.current)
              throw new DOMException('Aborted', 'AbortError');
            const previous = views.current[id]?.status;
            if (previous && !isActive(body.data) && body.data.version >= previous.version &&
              (isActive(previous) || body.data.version > previous.version)) {
              // 历史查询也可能先于 SSE 读到终态，不能把先前取得的会话版本当作最新版本。
              historyRevision.current++;
            }
            publish({[id]: {status: body.data, result}});
            // 查询开始后收到通知，补查一次；确定且已结算的终态不会再变化。
          } while (!canReuseInvocation(views.current[id]) &&
          revision !== (invocationRevisions.current.get(id) ?? 0));
          return views.current[id];
        }).finally(() => {
          if (invocationReads.current.get(id) === pending)
            invocationReads.current.delete(id);
        }),
      };
      invocationReads.current.set(id, pending);
      read = pending;
    }
    // 每个调用方单独取消；仅在没有使用者时才取消共享 HTTP 请求。
    const pending = read;
    return new Promise((resolve, reject) => {
      const consumer = Symbol();
      pending.consumers.add(consumer);
      const detach = () => {
        signal.removeEventListener('abort', abort);
        pending.consumers.delete(consumer);
        if (!pending.consumers.size && invocationReads.current.get(id) === pending) {
          pending.controller.abort();
          invocationReads.current.delete(id);
        }
      };
      const abort = () => {
        detach();
        reject(new DOMException('Aborted', 'AbortError'));
      };
      signal.addEventListener('abort', abort, {once: true});
      pending.promise.then((view) => {
        detach();
        if (!signal.aborted) resolve(view);
      }, (error) => {
        detach();
        reject(error);
      });
    });
  }

  async function refreshTerminal(id: string, status: InvocationStatusResponse) {
    const history = refreshedTerminalVersions.current.get(id) !== status.version;
    refreshedTerminalVersions.current.set(id, status.version);
    if (history) historyRevision.current++;
    await Promise.allSettled([
      ...(history ? [loadHistory()] : []),
      loadBudget(true),
    ]);
  }

  function stopWatching() {
    for (const timer of timers.current) clearTimeout(timer);
    timers.current.clear();
    if (expiry.current) clearTimeout(expiry.current);
    expiry.current = null;
    requests.current.poll?.abort();
  }

  function watch(ids: string[], force = false) {
    const unique = [...new Set(ids)];
    // 历史刷新不得打断正在等待预算结算的 SSE；只有观察集合变化时重新建连。
    if (
      !force &&
      unique.length > 0 &&
      !requests.current.poll?.signal.aborted &&
      requests.current.poll &&
      unique.length === watched.current.length &&
      unique.every((id) => watched.current.includes(id))
    )
      return;
    stopWatching();
    watched.current = [...new Set(ids)];
    setActiveIds(watched.current);
    setWatching(ids.length > 0);
    setPollPaused(false);
    setPollError(null);
    if (!ids.length) return;
    const controller = new AbortController();
    requests.current.poll = controller;
    const valid = () => !controller.signal.aborted && alive.current;
    expiry.current = setTimeout(
      () => {
        controller.abort();
        if (alive.current) {
          setWatching(false);
          setPollPaused(true);
        }
      },
      (settings.current.timeoutSeconds + 30) * 1000,
    );

    const delay = () =>
      new Promise<void>((resolve) => {
        const finish = () => {
          clearTimeout(timer);
          timers.current.delete(timer);
          controller.signal.removeEventListener('abort', finish);
          resolve();
        };
        const timer = setTimeout(finish, POLL_INTERVAL_MS);
        timers.current.add(timer);
        controller.signal.addEventListener('abort', finish, {once: true});
        if (controller.signal.aborted) finish();
      });
    const refreshOnce = async (id: string) => {
      const view = await readInvocation(id, controller.signal);
      if (!valid()) return;
      publish({[id]: view});
      if (view.status && !isActive(view.status)) {
        if (!needsObservation(view.status))
          watched.current = watched.current.filter((item) => item !== id);
        setActiveIds(watched.current);
        if (!watched.current.length) {
          if (expiry.current) clearTimeout(expiry.current);
          setWatching(false);
          setPollError(null);
          created.current = null;
        }
        // 回答完成先展示历史；账本仍 RESERVED 时继续等待结算事件。相同执行版本只刷新一次历史。
        await refreshTerminal(id, view.status);
      }
    };
    // 历史、停止和 SSE 共用同一个状态读取；事件期间的变化由读取循环补查。
    const refresh = refreshOnce;
    // 通知回调只登记失效事实；HTTP 刷新与 SSE 解码独立，短时间连续通知合并一次。
    const queued = new Map<string, boolean>();
    let flushing = false;
    let scheduled: ReturnType<typeof setTimeout> | null = null;
    const schedule = (wait = 50) => {
      if (!valid() || flushing || scheduled || !queued.size) return;
      scheduled = setTimeout(() => {
        timers.current.delete(scheduled!);
        scheduled = null;
        void flush();
      }, wait);
      timers.current.add(scheduled);
    };
    const flush = async () => {
      if (!valid()) return;
      flushing = true;
      const work = [...queued];
      queued.clear();
      let failed = false;
      const outcomes = await Promise.allSettled(work.map(([id]) => refresh(id)));
      if (valid()) {
        outcomes.forEach((outcome, index) => {
          if (outcome.status !== 'rejected') return;
          const [id, budgetChanged] = work[index];
          queued.set(id, (queued.get(id) ?? false) || budgetChanged);
          failed = true;
          setPollError(outcome.reason);
          if (outcome.reason instanceof AiApiError && [401, 403].includes(outcome.reason.httpStatus)) {
            controller.abort();
            if (expiry.current) clearTimeout(expiry.current);
            setWatching(false);
          }
        });
        if (!failed && work.some(([id, changed]) => changed && views.current[id]?.status && isActive(views.current[id].status)))
          await loadBudget(true);
      }
      flushing = false;
      // 失败事实留在队列，低频补查；不能因为已处理通知游标而丢掉终态刷新。
      schedule(failed ? POLL_INTERVAL_MS : 50);
    };
    const claimed = new Set<string>();
    const enqueue = (id: string, budgetChanged: boolean) => {
      invalidateInvocation(id);
      // 已有读取立即登记 SSE 使用者，翻页取消历史时不能取消其余使用者仍需要的请求。
      if (invocationReads.current.has(id) && !claimed.has(id)) {
        claimed.add(id);
        void readInvocation(id, controller.signal).catch(() => {
        }).finally(() => claimed.delete(id));
      }
      queued.set(id, (queued.get(id) ?? false) || budgetChanged);
      schedule();
    };
    const observe = async (id: string) => {
      while (valid() && watched.current.includes(id)) {
        try {
          // 状态查询与建连并行。避免慢 HTTP 查询挡住模型首段；SSE 重放仍覆盖 202 到建连间的完成事件。
          if (expiredCursors.current.has(id)) {
            await refresh(id);
          } else {
            void refresh(id).catch((error) => {
              if (!valid()) return;
              setPollError(error);
              if (
                error instanceof AiApiError &&
                [401, 403].includes(error.httpStatus)
              ) {
                controller.abort();
                if (expiry.current) clearTimeout(expiry.current);
                setWatching(false);
              }
            });
          }
          if (!valid() || !watched.current.includes(id)) return;
          if (!expiredCursors.current.has(id)) {
            await watchInvocation(
              {
                scope,
                invocationId: id,
                afterSequence: cursors.current.get(id) ?? 0,
              },
              async (event) => {
                if (!valid()) return;
                if (event.sequence <= (cursors.current.get(id) ?? 0)) return;
                if (event.kind === 'OUTPUT' && event.text !== undefined) {
                  const offset = committedTextOffsets.current.get(id) ?? 0;
                  appendText(id, offset, event.text);
                  committedTextOffsets.current.set(
                    id,
                    offset + event.text.length,
                  );
                } else if (['TERMINAL', 'CONTROL', 'STARTED', 'BUDGET_CHANGED'].includes(event.kind)) {
                  enqueue(id, event.kind === 'BUDGET_CHANGED');
                }
                // 登记刷新事实后推进通知游标；失败由队列重试，重连始终先恢复权威状态。
                if (valid()) cursors.current.set(id, event.sequence);
              },
              {
                signal: controller.signal,
                onConnected: () => {
                  if (valid()) setPollError(null);
                },
                onText: (delta) => {
                  if (valid() && watched.current.includes(id))
                    appendText(id, delta.offset, delta.text, delta.attemptId);
                },
              },
            );
          }
        } catch (error) {
          if (!valid()) return;
          setPollError(error);
          if (
            error instanceof AiApiError &&
            [401, 403].includes(error.httpStatus)
          ) {
            controller.abort();
            if (expiry.current) clearTimeout(expiry.current);
            setWatching(false);
            return;
          }
          if (error instanceof AiApiError && error.httpStatus === 410) {
            // 已裁剪游标不能静默跳过。恢复权威快照后采用低频 HTTP，避免不断重连同一过期游标。
            expiredCursors.current.add(id);
            await loadHistory();
          }
        }
        if (valid() && watched.current.includes(id)) await delay();
      }
    };
    for (const id of watched.current) void observe(id);
  }

  function loadBudget(force = false): Promise<void> {
    const budgetRef = settings.current.budgetRef;
    const key = JSON.stringify([scope, budgetRef, sendingEnabled]);
    if (budgetKey.current !== key) {
      budgetKey.current = key;
      setBudget(null);
    }
    if (budgetRead.current?.key === key && !requests.current.budget?.signal.aborted) {
      if (force) budgetDirty.current = true;
      return budgetRead.current.promise;
    }
    requests.current.budget?.abort();
    if (!sendingEnabled || !budgetRef) {
      setBudget(null);
      return Promise.resolve();
    }
    const controller = new AbortController();
    requests.current.budget = controller;
    setBudgetLoading(true);
    setBudgetError(null);
    const pending = {
      key, promise: Promise.resolve().then(async () => {
        do {
          budgetDirty.current = false;
          try {
            const {body} = await getBudget({scope, budgetRef}, {signal: controller.signal});
            if (!controller.signal.aborted && alive.current) setBudget(previous =>
              previous?.budgetRef === body.data.budgetRef && previous.version > body.data.version ? previous : body.data);
          } catch (error) {
            if (!controller.signal.aborted && alive.current) setBudgetError(error);
          }
        } while (budgetDirty.current && !controller.signal.aborted && alive.current);
      }).finally(() => {
        if (budgetRead.current === pending) budgetRead.current = null;
      if (!controller.signal.aborted && alive.current) setBudgetLoading(false);
      })
    };
    budgetRead.current = pending;
    return pending.promise;
  }

  function loadHistory(requestedPage?: number): Promise<void> {
    const existing = historyRead.current;
    if (existing && !existing.controller.signal.aborted && existing.page === requestedPage)
      return existing.promise;
    requests.current.history?.abort();
    const controller = new AbortController();
    requests.current.history = controller;
    const pending: HistoryRead = {
      page: requestedPage,
      controller,
      promise: readHistory(requestedPage, controller).finally(() => {
        if (historyRead.current === pending) historyRead.current = null;
      }),
    };
    historyRead.current = pending;
    setHistoryLoading(true);
    setHistoryError(null);
    return pending.promise;
  }

  async function readHistory(requestedPage: number | undefined, controller: AbortController) {
    let retriedConflict = false;
    try {
      while (!controller.signal.aborted && alive.current) {
        const revision = historyRevision.current;
        try {
          const reuseInitial = firstHistory.current;
          firstHistory.current = false;
          const conversation = reuseInitial ? initial : (
            await getConversation(
              {scope, conversationId: initial.conversationId},
              {signal: controller.signal},
            )
          ).body.data;
          if (controller.signal.aborted || !alive.current) return;
          if (revision !== historyRevision.current) continue;
          const query = async (current: number) =>
            (
              await queryTurnsOfConversation(
                {
                  scope,
                  conversationId: conversation.conversationId,
                  expectedVersion: conversation.version,
                  page: {current, size: HISTORY_PAGE_SIZE},
                },
                {signal: controller.signal},
              )
            ).body;
          let history = await query(requestedPage ?? 1);
          const lastPage = Math.max(
            1,
            Math.ceil(history.total / HISTORY_PAGE_SIZE),
          );
          if (requestedPage === undefined && lastPage > 1)
            history = await query(lastPage);
          if (controller.signal.aborted || !alive.current) return;
          if (revision !== historyRevision.current) continue;
          const ids = [...new Set([...history.records.flatMap((turn) => {
            const selected = turn.selectedCandidate?.kind === 'TOOL_CHAT_RUN' ? undefined : turn.selectedInvocationId ?? turn.invocationIds.at(-1);
            const latest = turn.invocationIds.at(-1);
            return [selected, latest].filter((id): id is string => !!id);
          }), ...Object.entries(views.current).filter(([, view]) => blocksConversation(view.status)).map(([id]) => id)])];
          // 先展示问题与已有回答；读取状态仍进行时禁止提交，避免使用尚未核对的会话版本。
          setTurns(history.records);
          setPage(history.current);
          setTotal(history.total);
          if (requestedPage === undefined && ids.length)
            setInvocationId(created.current ?? history.records.at(-1)?.invocationIds.at(-1) ?? null);
          const entries: [string, InvocationView][] = [];
          const unread = ids.filter(id => {
            if (!canReuseInvocation(views.current[id])) return true;
            entries.push([id, views.current[id]]);
            return false;
          });
          // 最近的回复先补全；每批不超过服务端上限，每批返回立即发布。
          unread.reverse();
          for (let offset = 0; offset < unread.length; offset += 20) {
            await Promise.all(unread.slice(offset, offset + 20).map(async id => {
              try {
                entries.push([id, await readInvocation(id, controller.signal)]);
              } catch (error) {
                const view = {error};
                entries.push([id, view]);
                if (!controller.signal.aborted && alive.current) publish({[id]: view});
              }
            }));
            if (controller.signal.aborted || !alive.current) return;
            const denied = entries.find(([, view]) => view.error instanceof AiApiError && [401, 403].includes(view.error.httpStatus));
            if (denied) throw denied[1].error;
          }
          if (controller.signal.aborted || !alive.current) return;
          // 提交或终态发生在查询期间，旧会话版本不能用于下一轮；合并为一次后续刷新。
          if (revision !== historyRevision.current) continue;
          metadata.current = conversation;
          updated.current(conversation);
          for (const [id, view] of entries) {
            if ('status' in view && view.status && !isActive(view.status))
              refreshedTerminalVersions.current.set(id, view.status.version);
          }
          const active = entries
            .filter(
              ([id]) =>
                views.current[id]?.status &&
                needsObservation(views.current[id].status),
            )
            .map(([id]) => id);
          // Browsing older pages must keep an invocation from the latest page under observation.
          for (const id of watched.current) {
            const status = views.current[id]?.status;
            if ((!status || needsObservation(status)) && !active.includes(id))
              active.push(id);
          }
          const createdStatus = created.current
            ? views.current[created.current]?.status
            : undefined;
          if (
            created.current &&
            !active.includes(created.current) &&
            (!createdStatus || needsObservation(createdStatus))
          ) {
            active.push(created.current);
          }
          // A failed history status lookup must be retried before submitting another turn.
          if (entries.some(([, view]) => 'error' in view && view.error !== undefined))
            setHistoryError(new Error('History invocation lookup failed'));
          watch(active);
          return;
        } catch (error) {
          if (!controller.signal.aborted && alive.current && revision !== historyRevision.current &&
            !(error instanceof AiApiError && [401, 403].includes(error.httpStatus))) continue;
          if (error instanceof AiApiError && error.httpStatus === 409 && revision === historyRevision.current) {
            // 已有会话元数据可能在打开期间变化；只允许一次自动恢复，持续冲突交给显式刷新。
            if (!retriedConflict) {
              retriedConflict = true;
              continue;
            }
          }
          throw error;
        }
      }
    } catch (error) {
      if (!controller.signal.aborted && alive.current) setHistoryError(error);
    } finally {
      if (!controller.signal.aborted && alive.current) setHistoryLoading(false);
    }
  }

  async function send() {
    if (requests.current.submit || regenerationRef.current || !alive.current || recoveryBlocked || !writesAllowed || (!sendingEnabled && !pendingRef.current)) return;
    // 本轮 UTF-8 预检；服务端检查合并历史后的容量。原幂等重试不受新模型额度改写。
    if (!pendingRef.current && maxInputBytes !== undefined && new TextEncoder().encode(draft.trim()).length > maxInputBytes) return;
    if (
      !pendingRef.current &&
      (historyLoading ||
        historyError ||
        watched.current.length > 0 ||
        Object.values(views.current).some(
          (view) => blocksConversation(view.status),
        ) ||
        !budget ||
        !hasAvailableBudget(budget.available) ||
        metadata.current.state !== 'ACTIVE')
    )
      return;
    const snapshot =
      pendingRef.current ??
      (() => {
        const current = settings.current;
        return {
          key: crypto.randomUUID(),
          request: {
            scope,
            conversationId: initial.conversationId,
            expectedVersion: metadata.current.version,
            text: draft.trim(),
            capability: {
              type: 'capability' as const,
              id: current.capabilityId,
              version: current.capabilityVersion,
            },
            binding: {
              type: 'binding' as const,
              id: current.bindingId,
              version: current.bindingVersion,
            },
            budgetRef: current.budgetRef,
            maxInputTokens: current.maxInputTokens,
            generationOptions: {
              maxOutputTokens: current.maxOutputTokens,
              temperature: current.temperature,
              topP: current.topP,
              stopSequences: [],
            },
            timeoutSeconds: current.timeoutSeconds,
          },
        };
      })();
    if (!snapshot.request.text) return;
    const acceptedLatency = beginChatLatency();
    pendingRef.current = snapshot;
    persist('send', snapshot);
    setPending(snapshot);
    setSubmitting(true);
    setSubmitError(null);
    const controller = new AbortController();
    requests.current.submit = controller;
    try {
      const {body, httpStatus} = await turnsForChat(snapshot.request, snapshot.key, {
        signal: controller.signal,
      });
      if (controller.signal.aborted || !alive.current) return;
      if (httpStatus !== 202 || !body.data.invocationId || body.data.conversationId !== snapshot.request.conversationId) throw new Error('Invalid chat acceptance; confirm the original request.');
      acceptedLatency(body.data.invocationId);
      if (body.data.titleMetadata) titleUpdated.current?.(snapshot.request.conversationId, body.data.titleMetadata);
      persist('send', {...snapshot, acceptedId: body.data.invocationId});
      pendingRef.current = null;
      setPending(null);
      setDraft(previous => previous.trim() === snapshot.request.text ? '' : previous);
      setInvocationId(body.data.invocationId);
      created.current = body.data.invocationId;
      watch([body.data.invocationId]);
      void loadBudget(true);
      historyRevision.current++;
      void loadHistory();
    } catch (error) {
      if (controller.signal.aborted || !alive.current) return;
      setSubmitError(error);
      // Explicit rejection is safe to correct. Network/5xx uncertainty must reuse the original request and key.
      if (
        error instanceof AiApiError &&
        error.httpStatus >= 400 &&
        error.httpStatus < 500 &&
        error.httpStatus !== 408 && error.code !== '205027'
      ) {
        persist('send', null);
        pendingRef.current = null;
        setPending(null);
        if (invalidatesChatConfiguration(error)) {
          rejected.current?.(snapshot.request, error);
        } else {
          if (error.httpStatus === 409) {
            historyRevision.current++;
            void loadHistory();
          }
          void loadBudget(true);
        }
      }
    } finally {
      if (!controller.signal.aborted && alive.current) {
        requests.current.submit = null;
        setSubmitting(false);
      }
    }
  }

  async function regenerate(originalInvocationId?: string) {
    if (requests.current.submit || pendingRef.current || !alive.current || recoveryBlocked || !writesAllowed || (!sendingEnabled && !regenerationRef.current)) return;
    let snapshot = regenerationRef.current;
    if (!snapshot) {
      if (historyLoading || historyError || watched.current.length > 0 ||
        Object.values(views.current).some(view => blocksConversation(view.status)) || !originalInvocationId ||
        metadata.current.state !== 'ACTIVE') return;
      snapshot = {
        key: crypto.randomUUID(),
        request: {
          scope, originalInvocationId, expectedConversationVersion: metadata.current.version,
          timeoutSeconds: settings.current.timeoutSeconds
        },
      };
    }
    const acceptedLatency = beginChatLatency();
    regenerationRef.current = snapshot;
    persist('regenerate', snapshot);
    setPendingRegeneration(snapshot);
    setSubmitting(true);
    setSubmitError(null);
    const controller = new AbortController();
    requests.current.submit = controller;
    try {
      const {body, httpStatus} = await regenerateChat(snapshot.request, snapshot.key, {signal: controller.signal});
      if (controller.signal.aborted || !alive.current) return;
      if (httpStatus !== 202 || !body.data.executionId) throw new Error('Invalid regeneration acceptance; confirm the original request.');
      acceptedLatency(body.data.executionId);
      persist('regenerate', {...snapshot, acceptedId: body.data.executionId});
      regenerationRef.current = null;
      setPendingRegeneration(null);
      setInvocationId(body.data.executionId);
      created.current = body.data.executionId;
      watch([body.data.executionId]);
      historyRevision.current++;
      void loadBudget(true);
      void loadHistory();
    } catch (error) {
      if (controller.signal.aborted || !alive.current) return;
      setSubmitError(error);
      if (error instanceof AiApiError && error.httpStatus >= 400 && error.httpStatus < 500 && error.httpStatus !== 408 && error.code !== '205027') {
        persist('regenerate', null);
        regenerationRef.current = null;
        setPendingRegeneration(null);
        // 原参数固定；拒绝后重新读取会话和预算，不能自动改为新问题或新配置重发。
        historyRevision.current++;
        void loadHistory();
        void loadBudget(true);
      }
    } finally {
      if (!controller.signal.aborted && alive.current) {
        requests.current.submit = null;
        setSubmitting(false);
      }
    }
  }

  async function inspectCandidate(id: string) {
    const controller = new AbortController();
    requests.current.candidate?.abort();
    requests.current.candidate = controller;
    try {
      const view = await readInvocation(id, controller.signal);
      if (!controller.signal.aborted && alive.current) publish({[id]: view});
    } catch (error) {
      if (!controller.signal.aborted && alive.current) publish({[id]: {...views.current[id], error}});
    }
  }

  async function refreshAfterReconciliation(id: string) {
    invalidateInvocation(id);
    historyRevision.current++;
    await Promise.all([loadHistory(), loadBudget(), views.current[id] ? inspectCandidate(id) : Promise.resolve()]);
  }

  async function stop(id: string) {
    if (requests.current.stop || stopRequested === id || recoveryBlocked) return;
    const current = views.current[id]?.status;
    if (current && !isActive(current)) {
      persist('stop', null);
      setPendingStop(null);
      return;
    }
    const controller = new AbortController();
    requests.current.stop = controller;
    setStopping(true);
    setStopError(null);
    stopTarget.current = id;
    const key = stopKeys.current.get(id) ?? crypto.randomUUID();
    stopKeys.current.set(id, key);
    persist('stop', {key, request: {scope, invocationId: id}});
    setPendingStop(id);
    let received = false;
    try {
      const {body} = await cancelInvocation({scope, invocationId: id}, key, {signal: controller.signal});
      if (controller.signal.aborted || !alive.current) return;
      received = true;
      setPendingStop(null);
      if (body.data.outcome === 'ACCEPTED') {
        setStopRequested(id);
        persist('stop', {key, request: {scope, invocationId: id}, acceptedId: id});
      } else persist('stop', null);
      invalidateInvocation(id);
      // 保持现有 SSE；暂停观察时恢复查询，不通过断开订阅假装停止成功。
      if (!requests.current.poll || requests.current.poll.signal.aborted || !watched.current.includes(id)) {
        watch([...watched.current, id], true);
      }
      const view = await readInvocation(id, controller.signal);
      if (controller.signal.aborted || !alive.current) return;
      publish({[id]: view});
      if (view.status && !isActive(view.status)) {
        await refreshTerminal(id, view.status);
      }
    } catch (error) {
      if (controller.signal.aborted || !alive.current) return;
      if (received) setPollError(error);
      else setStopError(error);
    } finally {
      if (!controller.signal.aborted && alive.current) {
        requests.current.stop = null;
        setStopping(false);
      }
    }
  }

  useEffect(() => {
    alive.current = true;
    void loadHistory();
    const knownId = created.current ?? restored.stop.value?.request.invocationId;
    if (knownId) {
      setInvocationId(knownId);
      watch([knownId]);
    }
    return () => {
      alive.current = false;
      if (textFrame.current !== null) cancelAnimationFrame(textFrame.current);
      textFrame.current = null;
      stopWatching();
      pendingText.current.clear();
      for (const request of Object.values(requests.current)) request?.abort();
      for (const read of invocationReads.current.values()) read.controller.abort();
      invocationReads.current.clear();
    };
  }, []);
  useEffect(() => {
    void loadBudget();
  }, [config, sendingEnabled]);

  useEffect(() => {
    const updatedBudget = (event: Event) => {
      if (!alive.current || !sendingEnabled) return;
      const {scope: owner, account} = (event as CustomEvent<{
        scope: typeof scope;
        account: BudgetAccountResponse
      }>).detail;
      if (owner.tenantId !== scope.tenantId || owner.workspaceId !== scope.workspaceId || account.budgetRef !== settings.current.budgetRef) return;
      setBudget(previous => previous?.budgetRef === account.budgetRef && previous.version > account.version ? previous : account);
      setBudgetError(null);
    };
    window.addEventListener('arte-ai-budget-updated', updatedBudget);
    return () => window.removeEventListener('arte-ai-budget-updated', updatedBudget);
  }, [config.budgetRef, sendingEnabled]);

  useEffect(() => {
    if (context && alive.current && !writeValue(storageKey(context, 'draft', target), draft)) setStorageWarning(STORAGE_WARNING);
  }, [draft]);
  useEffect(() => {
    const logout = (event: Event) => {
      if (!context || (event as CustomEvent).detail !== context.userId) return;
      alive.current = false;
      if (textFrame.current !== null) cancelAnimationFrame(textFrame.current);
      textFrame.current = null;
      stopWatching();
      for (const request of Object.values(requests.current)) request?.abort();
      for (const read of invocationReads.current.values()) read.controller.abort();
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => window.removeEventListener('arte-account-session-cleared', logout);
  }, []);
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (storageWarningRef.current && (pendingRef.current || regenerationRef.current || requests.current.stop)) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, []);

  return {
    storageWarning,
    recoveryBlocked,
    draft,
    setDraft,
    turns,
    page,
    total,
    historyLoading,
    historyError,
    loadHistory,
    /** Run 受理／完成可能与历史读取并发，先作废旧版本再复用现有刷新队列。 */
    refreshAfterExternalExecution: () => {
      historyRevision.current++;
      void loadHistory();
      void loadBudget(true);
    },
    budget,
    budgetLoading,
    budgetError,
    loadBudget,
    invocations,
    submitting,
    submitError,
    pending,
    pendingRegeneration,
    regenerate,
    inspectCandidate,
    refreshAfterReconciliation,
    invocationId,
    watching,
    pollError,
    pollPaused,
    activeIds,
    unresolved: Object.values(invocations).some(
      (view) => blocksConversation(view.status),
    ),
    resume: () => watch(watched.current, true),
    send,
    stop,
    stopping,
    stopRequested,
    stopError,
    pendingStop,
    retryStop: () => {
      if (stopTarget.current) void stop(stopTarget.current);
    },
  };
}
