import {useCallback, useEffect, useRef, useState} from 'react';
import {
  AiApiError,
  type AiScope,
  type ConversationResponse,
  createConversation,
  getConversation,
  listConversations,
} from '@/services/arte-ai';
import {
  type BrowserContext,
  readOperation,
  readValue,
  removeValue,
  saveOperation,
  STORAGE_WARNING,
  storageKey,
  writeValue
} from './storage';

export const CONVERSATION_PAGE_SIZE = 20;

/** The workspace component is remounted when scope changes. */
export function useConversations(scope: AiScope | null, context?: BrowserContext, listEnabled = true) {
  const [restored] = useState(() => readOperation<{ scope: AiScope; title: string }>(context, 'create', ''));
  const [storageWarning, setStorageWarning] = useState<string | null>(restored.warning);
  const persist = (value: { key: string; request: { scope: AiScope; title: string } } | null) => {
    if (!saveOperation(context, 'create', '', value)) setStorageWarning(STORAGE_WARNING);
  };
  const [records, setRecords] = useState<ConversationResponse[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<unknown>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selected, setSelected] = useState<ConversationResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<unknown>(null);
  const [selectionNotice, setSelectionNotice] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<unknown>(null);
  const [pendingTitle, setPendingTitle] = useState<string | null>(restored.value?.request.title ?? null);
  const listRequest = useRef<AbortController | null>(null);
  const detailRequest = useRef<AbortController | null>(null);
  const createRequest = useRef<AbortController | null>(null);
  const pendingCreate = useRef<{ key: string; title: string } | null>(restored.value ? {
    key: restored.value.key,
    title: restored.value.request.title
  } : null);
  const unsafeToLeave = !!storageWarning && (!!pendingTitle || creating);
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (unsafeToLeave) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [unsafeToLeave]);

  const loadPage = useCallback(
    async (current: number) => {
      if (!scope || !listEnabled) return;
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
    [scope, listEnabled],
  );

  const select = useCallback(
    async (conversationId: string) => {
      if (!scope) return;
      detailRequest.current?.abort();
      const controller = new AbortController();
      detailRequest.current = controller;
      setSelectedId(conversationId);
      setSelectionNotice(null);
      if (context && !writeValue(storageKey(context, 'selectedConversation'), conversationId)) setStorageWarning(STORAGE_WARNING);
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
        if (!controller.signal.aborted) {
          if (context && error instanceof AiApiError && error.httpStatus === 404) {
            setSelectedId(null);
            if (!removeValue(storageKey(context, 'selectedConversation'))) setStorageWarning(STORAGE_WARNING);
            setSelectionNotice('原会话已不可访问，请从列表选择其他会话。');
          } else setDetailError(error);
        }
      } finally {
        if (!controller.signal.aborted) setDetailLoading(false);
      }
    },
    [scope],
  );

  const create = async (title: string): Promise<boolean> => {
    if (!scope || createRequest.current || restored.warning) return false;
    // Keep the exact title and key even after a timeout or a closed dialog.
    const pending = pendingCreate.current ?? {
      key: crypto.randomUUID(),
      title,
    };
    pendingCreate.current = pending;
    persist({key: pending.key, request: {scope, title: pending.title}});
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
      persist(null);
      pendingCreate.current = null;
      setPendingTitle(null);
      // Use the server's real page: list ordering does not guarantee the new
      // conversation appears on page 1. Selection is independent of that page.
      void select(body.data.conversationId);
      void loadPage(1);
      return true;
    } catch (error) {
      if (!controller.signal.aborted) {
        setCreateError(error);
        if (error instanceof AiApiError && error.httpStatus >= 400 && error.httpStatus < 500 && error.httpStatus !== 408 && error.code !== '205027') {
          pendingCreate.current = null;
          setPendingTitle(null);
          persist(null);
        }
      }
      return false;
    } finally {
      if (!controller.signal.aborted) {
        createRequest.current = null;
        setCreating(false);
      }
    }
  };

  const updateConversation = useCallback(
    (conversation: ConversationResponse) => {
      setSelected((previous) =>
        previous?.conversationId === conversation.conversationId &&
        previous.version <= conversation.version
          ? conversation
          : previous,
      );
      setRecords((previous) =>
        previous.map((record) =>
          record.conversationId === conversation.conversationId &&
          record.version <= conversation.version
            ? conversation
            : record,
        ),
      );
    },
    [],
  );

  /** 搜索跳转先完成全部读取，再提交真实详情，失败时不拆掉当前聊天。 */
  const commitSelection = (value: ConversationResponse) => {
    detailRequest.current?.abort();
    setSelectedId(value.conversationId);
    setSelected(value);
    setDetailError(null);
    setDetailLoading(false);
    setSelectionNotice(null);
    if (context && !writeValue(storageKey(context, 'selectedConversation'), value.conversationId)) setStorageWarning(STORAGE_WARNING);
  };
  const invalidateSelection = () => {
    detailRequest.current?.abort();
    setSelected(null);
    setDetailLoading(false);
    setSelectionNotice('目标内容已不可访问，请重新选择会话。');
  };

  useEffect(() => {
    void loadPage(1);
    const selected = context ? readValue<string>(storageKey(context, 'selectedConversation')) : null;
    if (typeof selected?.value === 'string') void select(selected.value);
    return () => {
      listRequest.current?.abort();
      detailRequest.current?.abort();
      createRequest.current?.abort();
    };
  }, [loadPage]);

  return {
    selectionNotice,
    unsafeToLeave,
    storageWarning,
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
    updateConversation,
    commitSelection,
    invalidateSelection,
  };
}
