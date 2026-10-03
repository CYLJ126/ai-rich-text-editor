import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query';
import {useEffect, useRef} from 'react';
import {getChatBootstrap} from '@/services/ai-new/bootstrap';
import * as api from '@/services/ai-new/conversation';
import type {Conversation, WorkspaceSelection,} from '@/types/ai-new/conversation';
import {chatKeys} from '../queryKeys';

export const CONVERSATION_PAGE_SIZE = 20;

export function useChatBootstrap(userId: string, enabled: boolean) {
  return useQuery({
    queryKey: chatKeys.bootstrap(userId),
    queryFn: ({signal}) => getChatBootstrap(signal),
    enabled,
    retry: false,
    staleTime: 0,
  });
}

export function useConversations(
  userId: string,
  scope: WorkspaceSelection,
  title: string,
  page: number,
) {
  const offset = page * CONVERSATION_PAGE_SIZE;
  // One look-ahead row gives an honest next-page indicator without inventing a total.
  return useQuery({
    queryKey: chatKeys.list(
      userId,
      scope,
      title,
      offset,
      CONVERSATION_PAGE_SIZE + 1,
    ),
    queryFn: ({signal}) =>
      api.listConversations(
        scope,
        title,
        offset,
        CONVERSATION_PAGE_SIZE + 1,
        signal,
      ),
    retry: false,
  });
}

export function useConversation(
  userId: string,
  scope: WorkspaceSelection,
  id: string,
) {
  return useQuery({
    queryKey: chatKeys.detail(userId, scope, id),
    queryFn: ({signal}) => api.getConversation(scope, id, signal),
    enabled: Boolean(id),
    retry: false,
  });
}

export function useConversationCommands(
  userId: string,
  scope: WorkspaceSelection,
) {
  const client = useQueryClient();
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  const refresh = () =>
    active.current
      ? client.invalidateQueries({queryKey: chatKeys.scope(userId, scope)})
      : Promise.resolve();
  const save = (conversation: Conversation) => {
    if (!active.current) return;
    client.setQueryData(
      chatKeys.detail(userId, scope, conversation.conversationId),
      conversation,
    );
    return client.invalidateQueries({
      queryKey: chatKeys.lists(userId, scope),
    });
  };
  const create = useMutation({
    mutationFn: (title: string) => api.createConversation(scope, title),
    retry: false,
    onSuccess: save,
    onError: refresh,
  });
  const rename = useMutation({
    mutationFn: ({
                   conversation,
                   title,
                 }: {
      conversation: Conversation;
      title: string;
    }) => api.renameConversation(scope, conversation, title),
    retry: false,
    onSuccess: save,
    onError: refresh,
  });
  const remove = useMutation({
    mutationFn: (conversation: Conversation) =>
      api.deleteConversation(scope, conversation),
    retry: false,
    onSuccess: (conversation) => {
      if (!active.current) return;
      client.removeQueries({
        queryKey: chatKeys.detail(userId, scope, conversation.conversationId),
        exact: true,
      });
      return refresh();
    },
    onError: refresh,
  });
  return {
    create,
    rename,
    remove,
    busy: create.isPending || rename.isPending || remove.isPending,
  };
}
