import {useEffect, useRef, useState} from 'react';
import {
  AiApiError,
  type BudgetAccountResponse,
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
} from '@/services/arte-ai';
import type {ChatTestConfig} from './config';

export const HISTORY_PAGE_SIZE = 10;
export const POLL_INTERVAL_MS = 1000;

export function isActive(status: InvocationStatusResponse) {
  return ['ACCEPTED', 'QUEUED', 'RUNNING'].includes(status.state);
}

export function hasAvailableBudget(amount: string) {
  return /^\+?\d+(\.\d+)?$/.test(amount) && /[1-9]/.test(amount);
}

export interface InvocationView {
  status?: InvocationStatusResponse;
  result?: InvocationResultResponse;
  error?: unknown;
}

interface PendingMessage {
  key: string;
  request: SubmitChatRequest;
}

/** ChatPanel is keyed by conversation ID; the containing workspace is keyed by scope. */
export function useChatSession(
  config: ChatTestConfig,
  initial: ConversationResponse,
  onUpdated: (conversation: ConversationResponse) => void,
) {
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
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<unknown>(null);
  const [pending, setPending] = useState<PendingMessage | null>(null);
  const pendingRef = useRef<PendingMessage | null>(null);
  const [invocationId, setInvocationId] = useState<string | null>(null);
  const [watching, setWatching] = useState(false);
  const [pollError, setPollError] = useState<unknown>(null);
  const [pollPaused, setPollPaused] = useState(false);
  const [activeIds, setActiveIds] = useState<string[]>([]);
  const watched = useRef<string[]>([]);
  const created = useRef<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const expiry = useRef<ReturnType<typeof setTimeout> | null>(null);
  const requests = useRef({
    history: null as AbortController | null,
    budget: null as AbortController | null,
    submit: null as AbortController | null,
    poll: null as AbortController | null,
  });
  const alive = useRef(true);

  function publish(entries: Record<string, InvocationView>) {
    for (const [id, view] of Object.entries(entries)) {
      const previous = views.current[id];
      if (
        view.status &&
        previous?.status &&
        view.status.version < previous.status.version
      )
        continue;
      views.current[id] = view;
    }
    views.current = {...views.current};
    setInvocations(views.current);
  }

  async function readInvocation(
    id: string,
    signal: AbortSignal,
  ): Promise<InvocationView> {
    const {body} = await getInvocationStatus(
      {scope, invocationId: id},
      {signal},
    );
    if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
    let result = views.current[id]?.result;
    if (
      body.data.resultAvailable &&
      !isActive(body.data) &&
      (!result || views.current[id]?.status?.version !== body.data.version)
    ) {
      result = (
        await getInvocationResult({scope, invocationId: id}, {signal})
      ).body.data;
    }
    return {status: body.data, result};
  }

  function stopWatching() {
    if (timer.current) clearTimeout(timer.current);
    if (expiry.current) clearTimeout(expiry.current);
    timer.current = null;
    expiry.current = null;
    requests.current.poll?.abort();
  }

  function watch(ids: string[]) {
    stopWatching();
    watched.current = [...new Set(ids)];
    setActiveIds(watched.current);
    setWatching(ids.length > 0);
    setPollPaused(false);
    setPollError(null);
    if (!ids.length) return;
    const controller = new AbortController();
    requests.current.poll = controller;
    const deadline = Date.now() + (settings.current.timeoutSeconds + 30) * 1000;
    expiry.current = setTimeout(
      () => {
        controller.abort();
        if (timer.current) clearTimeout(timer.current);
        if (alive.current) {
          setWatching(false);
          setPollPaused(true);
        }
      },
      Math.max(0, deadline - Date.now()),
    );
    const tick = async () => {
      try {
        const entries = await Promise.all(
          watched.current.map(
            async (id) =>
              [id, await readInvocation(id, controller.signal)] as const,
          ),
        );
        if (controller.signal.aborted || !alive.current) return;
        publish(Object.fromEntries(entries));
        const active = entries
          .filter(([, view]) => view.status && isActive(view.status))
          .map(([id]) => id);
        watched.current = active;
        setActiveIds(active);
        if (!active.length) {
          if (expiry.current) clearTimeout(expiry.current);
          setWatching(false);
          created.current = null;
          // Read the authoritative conversation version after completion, including failed calls.
          await Promise.allSettled([loadHistory(), loadBudget()]);
        } else if (Date.now() >= deadline) {
          setWatching(false);
          setPollPaused(true);
        } else {
          timer.current = setTimeout(() => void tick(), POLL_INTERVAL_MS);
        }
      } catch (error) {
        if (!controller.signal.aborted && alive.current) {
          if (expiry.current) clearTimeout(expiry.current);
          setWatching(false);
          setPollError(error);
        }
      }
    };
    void tick();
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

  async function loadHistory(requestedPage?: number) {
    requests.current.history?.abort();
    const controller = new AbortController();
    requests.current.history = controller;
    setHistoryLoading(true);
    setHistoryError(null);
    try {
      const conversation = (
        await getConversation(
          {scope, conversationId: initial.conversationId},
          {signal: controller.signal},
        )
      ).body.data;
      if (controller.signal.aborted || !alive.current) return;
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
      metadata.current = conversation;
      updated.current(conversation);
      setTurns(history.records);
      setPage(history.current);
      setTotal(history.total);
      const ids = history.records.flatMap((turn) => {
        const id = turn.selectedInvocationId ?? turn.invocationIds.at(-1);
        return id ? [id] : [];
      });
      if (requestedPage === undefined && ids.length)
        setInvocationId(created.current ?? ids.at(-1) ?? null);
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
      publish(Object.fromEntries(entries));
      const active = entries
        .filter(
          ([id]) =>
            views.current[id]?.status && isActive(views.current[id].status),
        )
        .map(([id]) => id);
      // Browsing older pages must keep an invocation from the latest page under observation.
      for (const id of watched.current) {
        const status = views.current[id]?.status;
        if ((!status || isActive(status)) && !active.includes(id))
          active.push(id);
      }
      const createdStatus = created.current
        ? views.current[created.current]?.status
        : undefined;
      if (
        created.current &&
        !active.includes(created.current) &&
        (!createdStatus || isActive(createdStatus))
      ) {
        active.push(created.current);
      }
      // A failed history status lookup must be retried before submitting another turn.
      if (entries.some(([, view]) => 'error' in view))
        setHistoryError(new Error('History invocation lookup failed'));
      watch(active);
    } catch (error) {
      if (!controller.signal.aborted && alive.current) setHistoryError(error);
    } finally {
      if (!controller.signal.aborted && alive.current) setHistoryLoading(false);
    }
  }

  async function send() {
    if (requests.current.submit || !alive.current) return;
    if (
      !pendingRef.current &&
      (historyLoading ||
        historyError ||
        watched.current.length > 0 ||
        Object.values(views.current).some(
          (view) => view.status?.state === 'UNKNOWN',
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
        if (error.httpStatus === 409) void loadHistory();
        void loadBudget();
      }
    } finally {
      if (!controller.signal.aborted && alive.current) {
        requests.current.submit = null;
        setSubmitting(false);
      }
    }
  }

  useEffect(() => {
    alive.current = true;
    void loadHistory();
    return () => {
      alive.current = false;
      stopWatching();
      for (const request of Object.values(requests.current)) request?.abort();
    };
  }, []);
  useEffect(() => {
    void loadBudget();
  }, [config.budgetRef]);

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
      (view) => view.status?.state === 'UNKNOWN',
    ),
    resume: () => watch(watched.current),
    send,
  };
}
