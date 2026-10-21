import {useEffect, useRef, useState} from 'react';
import {
  AiApiError,
  type AiScope,
  type ConversationResponse,
  getInvocationSnapshot,
  queryTurnsAround
} from '@/services/arte-ai';
import type {ConversationTurnsAroundPage} from '@/services/arte-ai/conversation-search-contract';
import type {InvocationView} from './useChatSession';
import {historyViews, type LocatedHistory, locateSearchMessage, validateSnapshot} from './searchNavigation';
import {SearchTargetUnavailable} from './useConversationSearch';

interface State {
  id: string;
  pages: ConversationTurnsAroundPage[];
  views: Record<string, InvocationView>;
  viewed: Record<string, string>;
  revision: number;
}

const seedState = (seed: LocatedHistory): State => ({
  id: seed.id, pages: [seed.page], views: seed.views,
  viewed: seed.invocationId ? {[seed.turnId]: seed.invocationId} : {}, revision: 0
});

/** 定位窗口独立于普通历史和 SSE 缓存；版本改变时围绕可见锚点原子重建。 */
export function useLocatedHistory(seed: LocatedHistory | null | undefined, conversation: ConversationResponse, scope: AiScope,
                                  onInvalidated: (error: unknown, seed: LocatedHistory) => void) {
  const [state, setState] = useState<State | null>(seed ? seedState(seed) : null);
  const [dismissed, setDismissed] = useState<string | null>(null);
  const [loading, setLoading] = useState(false), [error, setError] = useState<unknown>(null);
  const request = useRef<AbortController | null>(null), epoch = useRef(0), anchor = useRef<string | null>(null);
  const failedVersion = useRef<number | null>(null);
  const active = !!seed && dismissed !== seed.id;
  const value = active ? state?.id === seed.id ? state : seedState(seed) : null;
  const current = useRef(value);
  current.current = value;
  const invalidated = useRef(onInvalidated);
  invalidated.current = onInvalidated;
  useEffect(() => {
    epoch.current++;
    request.current?.abort();
    anchor.current = seed?.turnId ?? null;
    failedVersion.current = null;
    setState(seed ? seedState(seed) : null);
    setLoading(false);
    setError(null);
    return () => {
      epoch.current++;
      request.current?.abort();
    };
  }, [seed?.id]);
  const turns = [...new Map(value?.pages.flatMap(page => page.records).map(turn => [turn.turnId, turn]) ?? [])].map(([, turn]) => turn).sort((a, b) => a.sequence - b.sequence);

  function failure(e: unknown) {
    if (!seed) return;
    if (e instanceof SearchTargetUnavailable || e instanceof AiApiError && [401, 403, 404].includes(e.httpStatus)) {
      setState(null);
      setDismissed(seed.id);
      invalidated.current(e, seed);
    } else setError(e);
  }

  async function rebuild() {
    if (!seed || !active || loading) return;
    const controller = new AbortController();
    request.current?.abort();
    request.current = controller;
    const batch = ++epoch.current;
    setLoading(true);
    setError(null);
    try {
      const target = turns.find(turn => turn.turnId === anchor.current) ?? turns.find(turn => turn.turnId === seed.turnId) ?? turns[0];
      const retainTarget = target?.turnId === seed.turnId;
      const next = await locateSearchMessage(scope, {
        entryKey: seed.entryKey, conversationId: seed.conversationId, title: '', matchKind: 'MESSAGE',
        snippet: {text: '', highlights: []}, canLocateMessage: true, turnId: target?.turnId ?? seed.turnId,
        messageId: retainTarget ? seed.messageId : target?.userMessage.messageId ?? seed.messageId,
        invocationId: retainTarget ? seed.invocationId : null,
        candidate: retainTarget ? seed.candidate : null,
        candidateOrdinal: null,
        partial: false
      }, controller.signal);
      if (batch !== epoch.current || controller.signal.aborted) return;
      setState({...seedState(next), id: seed.id, revision: (current.current?.revision ?? 0) + 1});
      failedVersion.current = conversation.version;
    } catch (e) {
      if (batch === epoch.current && !controller.signal.aborted) {
        failedVersion.current = conversation.version;
        failure(e);
      }
    } finally {
      if (batch === epoch.current) setLoading(false);
    }
  }

  useEffect(() => {
    if (active && value && value.pages[0].conversationVersion !== conversation.version && failedVersion.current !== conversation.version && !loading) void rebuild();
  }, [active, conversation.version, value?.pages[0].conversationVersion, loading]);

  async function load(direction: 'before' | 'after') {
    const s = current.current;
    if (!s || loading || !seed) return;
    const cursor = direction === 'before' ? s.pages[0].earlierCursor : s.pages.at(-1)!.laterCursor;
    if (!cursor) return;
    const controller = new AbortController();
    request.current?.abort();
    request.current = controller;
    const batch = ++epoch.current;
    setLoading(true);
    setError(null);
    try {
      const {body} = await queryTurnsAround({
        scope,
        conversationId: seed.conversationId,
        historyCursor: cursor,
        size: 10
      }, {signal: controller.signal});
      if (body.data.conversationVersion !== s.pages[0].conversationVersion || body.data.records.some(turn => turn.conversationId !== seed.conversationId)) throw new Error('History version mismatch');
      const views = await historyViews(scope, body.data, controller.signal);
      if (batch !== epoch.current || controller.signal.aborted) return;
      const pages = direction === 'before' ? [body.data, ...s.pages].slice(0, 10) : [...s.pages, body.data].slice(-10);
      const ids = new Set(pages.flatMap(page => page.records.flatMap(turn => turn.invocationIds)));
      const turnIds = new Set(pages.flatMap(page => page.records.map(turn => turn.turnId)));
      setState({
        ...s, pages, views: Object.fromEntries(Object.entries({...s.views, ...views}).filter(([id]) => ids.has(id))),
        viewed: Object.fromEntries(Object.entries(s.viewed).filter(([id]) => turnIds.has(id))), revision: s.revision + 1
      });
    } catch (e) {
      if (batch !== epoch.current || controller.signal.aborted) return;
      if (e instanceof AiApiError && e.httpStatus === 409 && e.code === '205030') {
        setLoading(false);
        void rebuild();
      } else failure(e);
    } finally {
      if (batch === epoch.current) setLoading(false);
    }
  }

  async function choose(turnId: string, id: string) {
    const s = current.current, turn = turns.find(value => value.turnId === turnId);
    if (!s || !turn || loading || !turn.invocationIds.includes(id)) return;
    const controller = new AbortController();
    request.current?.abort();
    request.current = controller;
    const batch = ++epoch.current;
    setLoading(true);
    setError(null);
    try {
      const view = validateSnapshot(await getInvocationSnapshot({
        scope,
        invocationId: id
      }, {signal: controller.signal}), turn, id);
      if (!controller.signal.aborted && batch === epoch.current) setState({
        ...s,
        views: {...s.views, [id]: view},
        viewed: {...s.viewed, [turnId]: id}
      });
    } catch (e) {
      if (batch === epoch.current && !controller.signal.aborted) failure(e);
    } finally {
      if (batch === epoch.current) setLoading(false);
    }
  }

  function dismiss() {
    epoch.current++;
    request.current?.abort();
    setState(null);
    setLoading(false);
    setError(null);
    setDismissed(seed?.id ?? null);
  }

  return {
    active,
    turns,
    views: value?.views ?? {},
    viewed: value?.viewed ?? {},
    loading,
    error,
    revision: value?.revision ?? 0,
    before: value?.pages[0].earlierCursor,
    after: value?.pages.at(-1)?.laterCursor,
    choose,
    load,
    rebuild,
    dismiss,
    anchor: (turnId: string) => {
      anchor.current = turnId;
    }
  };
}
