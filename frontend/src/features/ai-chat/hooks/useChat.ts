import {useInfiniteQuery, useMutation, useQueryClient,} from '@tanstack/react-query';
import {useEffect, useRef, useState} from 'react';
import {cancelChat, getChatHistory, submitChat} from '@/services/ai-new/chat';
import {AiNewApiError} from '@/services/ai-new/request';
import type {CancellationStatus, PendingChatCommand} from '@/types/ai-new/chat';
import type {Conversation, WorkspaceSelection,} from '@/types/ai-new/conversation';
import {pendingStorageKey, readPendingCommand, removePendingCommand, storePendingCommand,} from '../pendingCommands';
import {chatKeys} from '../queryKeys';
import {canRegenerateTurn, isTurnPending} from '../executionState';
import {isAccessError} from '../errors';

const observationWindow = 5 * 60 * 1000;

export function useChat(
  userId: string,
  scope: WorkspaceSelection,
  conversation: Conversation,
) {
  const client = useQueryClient();
  const id = conversation.conversationId;
  const storageKey = pendingStorageKey(userId, scope, id);
  const [pending, setPending] = useState(() => readPendingCommand(storageKey));
  const [commandError, setCommandError] = useState<unknown>(null);
  const [storageFailed, setStorageFailed] = useState(false);
  const [cancellation, setCancellation] = useState<{ turnId: string; status: CancellationStatus } | null>(null);
  const [cancelError, setCancelError] = useState<unknown>(null);
  const active = useRef(true);
  const submitting = useRef(false);
  const observedFrom = useRef(Date.now());
  const cancelling = useRef(false);
  const [working, setWorking] = useState(false);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  const history = useInfiniteQuery({
    queryKey: chatKeys.history(userId, scope, id),
    initialPageParam: undefined as number | undefined,
    queryFn: ({pageParam, signal}) =>
      getChatHistory(scope, id, pageParam, signal),
    enabled: (query) => !isAccessError(commandError) && !isAccessError(cancelError) && !isAccessError(query.state.error),
    getNextPageParam: (page) =>
      page.length === 20 ? page.at(-1)?.turn.sequence : undefined,
    retry: false,
    refetchIntervalInBackground: false,
    refetchInterval: (query) =>
      !isAccessError(commandError) && !isAccessError(cancelError) &&
      !query.state.error &&
      Date.now() - observedFrom.current < observationWindow &&
      query.state.data?.pages[0]?.some(isTurnPending)
        ? 2000
        : false,
  });
  const turns = [
    ...new Map(
      (history.data?.pages.flat() ?? []).map((item) => [
        item.turn.turnId,
        item,
      ]),
    ).values(),
  ].sort((left, right) => left.turn.sequence - right.turn.sequence);
  const unfinished = turns.some(isTurnPending);
  const refreshMetadata = () =>
    Promise.all([
      client.invalidateQueries({
        queryKey: chatKeys.detail(userId, scope, id),
        exact: true,
      }),
      client.invalidateQueries({queryKey: chatKeys.lists(userId, scope)}),
    ]);
  const clearPending = () => {
    removePendingCommand(storageKey);
    setPending(null);
  };
  // A query can reconcile an ambiguous POST without dispatching it again.
  useEffect(() => {
    const match = history.data?.pages
      .flat()
      .find((item) => item.turn.idempotencyKey.key === pending?.key);
    if (
      pending &&
      (match?.turn.status === 'ACCEPTED' || match?.turn.status === 'REJECTED')
    ) {
      removePendingCommand(storageKey);
      setPending(null);
      setCommandError(null);
    }
  }, [history.data, pending, storageKey]);
  const lastTerminal = useRef('');
  useEffect(() => {
    const latest = history.data?.pages[0]?.[0];
    if (
      latest &&
      !isTurnPending(latest) &&
      latest.turn.turnId !== lastTerminal.current
    ) {
      lastTerminal.current = latest.turn.turnId;
      void refreshMetadata();
    }
  });
  const mutation = useMutation({
    mutationKey: [...chatKeys.history(userId, scope, id), 'submit'],
    gcTime: 0,
    mutationFn: (command: PendingChatCommand) => submitChat(scope, id, command),
    retry: false,
    networkMode: 'always',
  });
  const send = async (text: string, replay = false, originalTurnId?: string) => {
    if (
      submitting.current || cancelling.current ||
      history.isError ||
      !history.isSuccess ||
      history.isFetching
    )
      return false;
    if (replay && !pending) return false;
    if (!replay && (pending || unfinished)) return false;
    const latest = turns.at(-1);
    if (!replay && originalTurnId && (!latest || latest.turn.turnId !== originalTurnId || !canRegenerateTurn(latest))) return false;
    const body = {expectedVersion: conversation.version, text, externalTransferConfirmed: true as const};
    const command: PendingChatCommand =
      replay && pending
        ? pending
        : originalTurnId ? {key: crypto.randomUUID(), kind: 'REGENERATION', body: {...body, originalTurnId}}
          : {key: crypto.randomUUID(), body};
    submitting.current = true;
    setWorking(true);
    setCommandError(null);
    setCancelError(null);
    setCancellation(null);
    try {
      storePendingCommand(storageKey, command);
    } catch {
      submitting.current = false;
      setWorking(false);
      setStorageFailed(true);
      return false;
    }
    setStorageFailed(false);
    setPending(command);
    observedFrom.current = Date.now();
    let accepted = false;
    let accessDenied = false;
    try {
      const result = await mutation.mutateAsync(command);
      accepted = result.turn.status === 'ACCEPTED';
      if (!active.current) return accepted;
      if (
        result.turn.status === 'ACCEPTED' ||
        result.turn.status === 'REJECTED'
      )
        clearPending();
    } catch (error) {
      if (!active.current) return false;
      accessDenied = isAccessError(error);
      setCommandError(error);
      // Only a confirmed boundary rejection may be discarded. 503/transport errors stay reconcilable.
      if (
        error instanceof AiNewApiError &&
        [400, 401, 403, 404, 409, 429].includes(error.status) &&
        error.facts?.sideEffectStatus === 'NONE' &&
        error.facts.resultCertainty === 'CONFIRMED'
      )
        clearPending();
    } finally {
      if (active.current && !accessDenied) {
        await Promise.all([history.refetch(), refreshMetadata()]);
      } else if (active.current) {
        await refreshMetadata();
      }
      submitting.current = false;
      if (active.current) setWorking(false);
    }
    return accepted;
  };
  const cancelMutation = useMutation({
    mutationKey: [...chatKeys.history(userId, scope, id), 'cancel'], gcTime: 0,
    mutationFn: (turnId: string) => cancelChat(scope, id, turnId), retry: false, networkMode: 'always',
  });
  const cancel = async (turnId: string) => {
    const turn = turns.find((item) => item.turn.turnId === turnId);
    if (submitting.current || cancelling.current || history.isError || !turn?.execution || !isTurnPending(turn)) return;
    if (cancellation?.turnId === turnId && cancellation.status !== 'UNCONFIRMED') return;
    cancelling.current = true;
    setWorking(true);
    setCancelError(null);
    observedFrom.current = Date.now();
    let accessDenied = false;
    try {
      const status = await cancelMutation.mutateAsync(turnId);
      if (active.current) setCancellation({turnId, status});
    } catch (error) {
      accessDenied = isAccessError(error);
      if (active.current) {
        setCancellation({turnId, status: 'UNCONFIRMED'});
        setCancelError(error);
      }
    } finally {
      if (active.current && !accessDenied) await history.refetch();
      else if (active.current) await refreshMetadata();
      cancelling.current = false;
      if (active.current) setWorking(false);
    }
  };
  const refresh = async () => {
    observedFrom.current = Date.now();
    const [result] = await Promise.all([history.refetch(), refreshMetadata()]);
    if (active.current && result.isSuccess) {
      setCommandError(null);
      setCancelError(null);
    }
  };
  return {
    history,
    turns,
    unfinished,
    pending,
    commandError,
    storageFailed,
    busy: working || mutation.isPending || cancelMutation.isPending,
    sending: mutation.isPending || (working && submitting.current),
    cancellation, cancelError, cancelling: cancelMutation.isPending || (working && cancelling.current), cancel,
    send,
    refresh,
    observationPaused:
      unfinished && Date.now() - observedFrom.current >= observationWindow,
  };
}
