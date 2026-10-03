import {useInfiniteQuery, useMutation, useQueryClient,} from '@tanstack/react-query';
import {useEffect, useRef, useState} from 'react';
import {getChatHistory, submitChat} from '@/services/ai-new/chat';
import {AiNewApiError} from '@/services/ai-new/request';
import type {ChatTurnResult, PendingChatCommand} from '@/types/ai-new/chat';
import type {Conversation, WorkspaceSelection,} from '@/types/ai-new/conversation';
import {pendingStorageKey, readPendingCommand, removePendingCommand, storePendingCommand,} from '../pendingCommands';
import {chatKeys} from '../queryKeys';

export function isTurnPending(item: ChatTurnResult) {
  return (
    item.turn.status === 'PREPARING' ||
    item.turn.status === 'READY' ||
    item.execution?.status === 'ACCEPTED' ||
    item.execution?.status === 'RUNNING'
  );
}

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
  const active = useRef(true);
  const submitting = useRef(false);
  const observedFrom = useRef(Date.now());
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
    getNextPageParam: (page) =>
      page.length === 20 ? page.at(-1)?.turn.sequence : undefined,
    retry: false,
    refetchIntervalInBackground: false,
    refetchInterval: (query) =>
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
  const send = async (text: string, replay = false) => {
    if (
      submitting.current ||
      history.isError ||
      !history.isSuccess ||
      history.isFetching
    )
      return false;
    if (replay && !pending) return false;
    if (!replay && (pending || unfinished)) return false;
    const command: PendingChatCommand =
      replay && pending
        ? pending
        : {
          key: crypto.randomUUID(),
          body: {
            expectedVersion: conversation.version,
            text,
            externalTransferConfirmed: true,
          },
        };
    submitting.current = true;
    setCommandError(null);
    try {
      storePendingCommand(storageKey, command);
    } catch {
      submitting.current = false;
      setStorageFailed(true);
      return false;
    }
    setStorageFailed(false);
    setPending(command);
    observedFrom.current = Date.now();
    let received = false;
    try {
      const result = await mutation.mutateAsync(command);
      received = true;
      if (!active.current) return true;
      if (
        result.turn.status === 'ACCEPTED' ||
        result.turn.status === 'REJECTED'
      )
        clearPending();
    } catch (error) {
      if (!active.current) return false;
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
      if (active.current) {
        await Promise.all([history.refetch(), refreshMetadata()]);
      }
      submitting.current = false;
    }
    return received;
  };
  const refresh = async () => {
    observedFrom.current = Date.now();
    setCommandError(null);
    await Promise.all([history.refetch(), refreshMetadata()]);
  };
  return {
    history,
    turns,
    unfinished,
    pending,
    commandError,
    storageFailed,
    sending: mutation.isPending,
    send,
    refresh,
    observationPaused:
      unfinished && Date.now() - observedFrom.current >= observationWindow,
  };
}
