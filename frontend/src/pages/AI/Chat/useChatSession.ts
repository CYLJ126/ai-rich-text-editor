import {useEffect, useRef, useState} from 'react';
import {
  AiApiError,
  type BudgetAccountResponse,
  cancelInvocation,
  type ConversationResponse,
  type ConversationTurnResponse,
  getBudget,
  getConversation,
  getInvocationResult,
  getInvocationStatus,
  type InvocationResultResponse,
  type InvocationStatusResponse,
  queryTurnsOfConversation,
  type SubmitChatRequest,
  turnsForChat,
  watchInvocation,
} from '@/services/arte-ai';
import type {ChatTestConfig} from './config';
import {type ConfigurationRejected, invalidatesChatConfiguration} from './configurationInvalidation';

export const HISTORY_PAGE_SIZE = 10;
/** SSE 不可用时的恢复查询间隔；正常连接不循环查询状态。 */
export const POLL_INTERVAL_MS = 10_000;

export function isActive(status: InvocationStatusResponse) {
  return ['ACCEPTED', 'QUEUED', 'RUNNING'].includes(status.state);
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
) {
  const rejected = useRef(onConfigurationRejected);
  rejected.current = onConfigurationRejected;
  const settings = useRef(config);
  settings.current = config;
  const updated = useRef(onUpdated);
  updated.current = onUpdated;
  const metadata = useRef(initial);
  const scope = {tenantId: config.tenantId, workspaceId: config.workspaceId};
  const [draft, setDraft] = useState('');
  const [turns, setTurns] = useState<ConversationTurnResponse[]>([]);
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
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<unknown>(null);
  const [stopping, setStopping] = useState(false);
  const [stopRequested, setStopRequested] = useState<string | null>(null);
  const [stopError, setStopError] = useState<unknown>(null);
  const stopKeys = useRef(new Map<string, string>());
  const stopTarget = useRef<string | null>(null);
  const [pending, setPending] = useState<PendingMessage | null>(null);
  const pendingRef = useRef<PendingMessage | null>(null);
  const [invocationId, setInvocationId] = useState<string | null>(null);
  const [watching, setWatching] = useState(false);
  const [pollError, setPollError] = useState<unknown>(null);
  const [pollPaused, setPollPaused] = useState(false);
  const [activeIds, setActiveIds] = useState<string[]>([]);
  const watched = useRef<string[]>([]);
  const created = useRef<string | null>(null);
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
  });
  const alive = useRef(true);

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
    publish({
      [id]: {
        streamText: appended,
        ...(pending.attemptId ? {streamAttemptId: pending.attemptId} : {}),
      },
    });
  }

  function publish(entries: Record<string, InvocationView>) {
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
    }
    views.current = {...views.current};
    setInvocations(views.current);
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
            const {body} = await getInvocationStatus(
              {scope, invocationId: id},
              {signal: controller.signal},
            );
            if (controller.signal.aborted)
              throw new DOMException('Aborted', 'AbortError');
            let result = views.current[id]?.result;
            if (body.data.resultAvailable && !isActive(body.data) &&
              (!result || views.current[id]?.status?.version !== body.data.version)) {
              result = (await getInvocationResult(
                {scope, invocationId: id},
                {signal: controller.signal},
              )).body.data;
            }
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
      loadBudget(),
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
                } else if (event.kind === 'TERMINAL') {
                  invalidateInvocation(id);
                  await refresh(id);
                } else if (
                  event.kind === 'CONTROL' ||
                  event.kind === 'STARTED' ||
                  event.kind === 'BUDGET_CHANGED'
                ) {
                  invalidateInvocation(id);
                  void refresh(id)
                    .then(async () => {
                      if (
                        event.kind === 'BUDGET_CHANGED' &&
                        valid() &&
                        views.current[id]?.status &&
                        isActive(views.current[id].status)
                      )
                        await loadBudget();
                    })
                    .catch((error) => {
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
                // 回调成功后才推进游标，结果读取失败可以重放终态通知。
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

  async function loadBudget() {
    requests.current.budget?.abort();
    const controller = new AbortController();
    requests.current.budget = controller;
    setBudgetLoading(true);
    setBudgetError(null);
    setBudget(null);
    try {
      const {body} = await getBudget(
        {scope, budgetRef: settings.current.budgetRef},
        {signal: controller.signal},
      );
      if (!controller.signal.aborted && alive.current) setBudget(body.data);
    } catch (error) {
      if (!controller.signal.aborted && alive.current) setBudgetError(error);
    } finally {
      if (!controller.signal.aborted && alive.current) setBudgetLoading(false);
    }
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
    try {
      while (!controller.signal.aborted && alive.current) {
        const revision = historyRevision.current;
        try {
          const conversation = (
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
          const ids = history.records.flatMap((turn) => {
            const id = turn.selectedInvocationId ?? turn.invocationIds.at(-1);
            return id ? [id] : [];
          });
          const entries = await Promise.all(
            ids.map(async (id) => {
              try {
                return [id, await readInvocation(id, controller.signal)] as const;
              } catch (error) {
                return [id, {error}] as const;
              }
            }),
          );
          if (controller.signal.aborted || !alive.current) return;
          // 提交或终态发生在查询期间，旧会话版本不能用于下一轮；合并为一次后续刷新。
          if (revision !== historyRevision.current) continue;
          metadata.current = conversation;
          updated.current(conversation);
          setTurns(history.records);
          setPage(history.current);
          setTotal(history.total);
          if (requestedPage === undefined && ids.length)
            setInvocationId(created.current ?? ids.at(-1) ?? null);
          publish(Object.fromEntries(entries));
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
    if (requests.current.submit || !alive.current) return;
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
    pendingRef.current = snapshot;
    setPending(snapshot);
    setSubmitting(true);
    setSubmitError(null);
    const controller = new AbortController();
    requests.current.submit = controller;
    try {
      const {body} = await turnsForChat(snapshot.request, snapshot.key, {
        signal: controller.signal,
      });
      if (controller.signal.aborted || !alive.current) return;
      pendingRef.current = null;
      setPending(null);
      setDraft('');
      setInvocationId(body.data.invocationId);
      created.current = body.data.invocationId;
      watch([body.data.invocationId]);
      void loadBudget();
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
        error.httpStatus !== 408
      ) {
        pendingRef.current = null;
        setPending(null);
        if (invalidatesChatConfiguration(error)) {
          rejected.current?.(snapshot.request, error);
        } else {
          if (error.httpStatus === 409) {
            historyRevision.current++;
            void loadHistory();
          }
          void loadBudget();
        }
      }
    } finally {
      if (!controller.signal.aborted && alive.current) {
        requests.current.submit = null;
        setSubmitting(false);
      }
    }
  }

  async function stop(id: string) {
    if (requests.current.stop || stopRequested === id) return;
    const current = views.current[id]?.status;
    if (current && !isActive(current)) return;
    const controller = new AbortController();
    requests.current.stop = controller;
    setStopping(true);
    setStopError(null);
    stopTarget.current = id;
    const key = stopKeys.current.get(id) ?? crypto.randomUUID();
    stopKeys.current.set(id, key);
    let received = false;
    try {
      const {body} = await cancelInvocation({scope, invocationId: id}, key, {signal: controller.signal});
      if (controller.signal.aborted || !alive.current) return;
      received = true;
      if (body.data.outcome === 'ACCEPTED') setStopRequested(id);
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
    return () => {
      alive.current = false;
      stopWatching();
      pendingText.current.clear();
      for (const request of Object.values(requests.current)) request?.abort();
      for (const read of invocationReads.current.values()) read.controller.abort();
      invocationReads.current.clear();
    };
  }, []);
  useEffect(() => {
    void loadBudget();
  }, [config]);

  return {
    draft,
    setDraft,
    turns,
    page,
    total,
    historyLoading,
    historyError,
    loadHistory,
    budget,
    budgetLoading,
    budgetError,
    loadBudget,
    invocations,
    submitting,
    submitError,
    pending,
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
    retryStop: () => {
      if (stopTarget.current) void stop(stopTarget.current);
    },
  };
}
