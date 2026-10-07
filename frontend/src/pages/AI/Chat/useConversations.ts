import {useCallback, useEffect, useRef, useState} from 'react';
import {
  type AiScope,
  type ConversationResponse,
  createConversation,
  getConversation,
  listConversations,
} from '@/services/arte-ai';

export const CONVERSATION_PAGE_SIZE = 20;

/** The workspace component is remounted when scope changes. */
export function useConversations(scope: AiScope | null) {
  const [records, setRecords] = useState<ConversationResponse[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<unknown>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selected, setSelected] = useState<ConversationResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<unknown>(null);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<unknown>(null);
  const [pendingTitle, setPendingTitle] = useState<string | null>(null);
  const listRequest = useRef<AbortController | null>(null);
  const detailRequest = useRef<AbortController | null>(null);
  const createRequest = useRef<AbortController | null>(null);
  const pendingCreate = useRef<{ key: string; title: string } | null>(null);

  const loadPage = useCallback(
    async (current: number) => {
      if (!scope) return;
      listRequest.current?.abort();
      const controller = new AbortController();
      listRequest.current = controller;
      setPage(current);
      setRecords([]);
      setListError(null);
      setListLoading(true);
      try {
        const {body} = await listConversations(
          {scope, page: {current, size: CONVERSATION_PAGE_SIZE}},
          {signal: controller.signal},
        );
        if (controller.signal.aborted) return;
        setRecords(body.records);
        setTotal(body.total);
        setPage(body.current);
      } catch (error) {
        if (!controller.signal.aborted) setListError(error);
      } finally {
        if (!controller.signal.aborted) setListLoading(false);
      }
    },
    [scope],
  );

  const select = useCallback(
    async (conversationId: string) => {
      if (!scope) return;
      detailRequest.current?.abort();
      const controller = new AbortController();
      detailRequest.current = controller;
      setSelectedId(conversationId);
      setSelected(null);
      setDetailError(null);
      setDetailLoading(true);
      try {
        const {body} = await getConversation(
          {scope, conversationId},
          {signal: controller.signal},
        );
        if (!controller.signal.aborted) setSelected(body.data);
      } catch (error) {
        if (!controller.signal.aborted) setDetailError(error);
      } finally {
        if (!controller.signal.aborted) setDetailLoading(false);
      }
    },
    [scope],
  );

  const create = async (title: string): Promise<boolean> => {
    if (!scope || createRequest.current) return false;
    // Keep the exact title and key even after a timeout or a closed dialog.
    const pending = pendingCreate.current ?? {
      key: crypto.randomUUID(),
      title,
    };
    pendingCreate.current = pending;
    setPendingTitle(pending.title);
    const controller = new AbortController();
    createRequest.current = controller;
    setCreating(true);
    setCreateError(null);
    try {
      const {body} = await createConversation(
        {scope, title: pending.title},
        pending.key,
        {signal: controller.signal},
      );
      if (controller.signal.aborted) return false;
      pendingCreate.current = null;
      setPendingTitle(null);
      // Use the server's real page: list ordering does not guarantee the new
      // conversation appears on page 1. Selection is independent of that page.
      void select(body.data.conversationId);
      void loadPage(1);
      return true;
    } catch (error) {
      if (!controller.signal.aborted) setCreateError(error);
      return false;
    } finally {
      if (!controller.signal.aborted) {
        createRequest.current = null;
        setCreating(false);
      }
    }
  };

  useEffect(() => {
    void loadPage(1);
    return () => {
      listRequest.current?.abort();
      detailRequest.current?.abort();
      createRequest.current?.abort();
    };
  }, [loadPage]);

  return {
    records,
    page,
    total,
    listLoading,
    listError,
    loadPage,
    selectedId,
    selected,
    detailLoading,
    detailError,
    select,
    creating,
    createError,
    pendingTitle,
    create,
  };
}
