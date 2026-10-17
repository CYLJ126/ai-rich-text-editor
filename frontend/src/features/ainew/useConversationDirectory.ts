import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {
  AiApiError,
  type AiScope,
  changeConversationMetadata,
  type ConversationListItem,
  type ConversationMetadataReceipt,
  type ConversationMetadataRequest,
  type ConversationResponse,
  type ConversationTitleMetadata,
  type ConversationWindow,
  createDefaultConversation,
  type CreateDefaultConversationRequest,
  getConversationMetadata,
  getMetadataCommandReceipt,
  listConversationsCursor,
  queryConversationWindow,
} from '@/services/arte-ai';
import {
  type BrowserContext,
  type Operation,
  readOperation,
  readValue,
  RECOVERY_WARNING,
  removeValue,
  saveOperation,
  STORAGE_WARNING,
  storageKey,
  writeValue,
} from './storage';
import {useConversations} from './useConversations';

export const DIRECTORY_LIMIT = 100;
export const DIRECTORY_PAGE_SIZE = 20;

export interface DirectoryAnchor {
  id: string;
  offset: number
}

interface WindowState {
  temporary: ConversationListItem | null;
  pinned: ConversationListItem[];
  normal: ConversationListItem[];
  cursor: string | null;
  hasMore: boolean;
  total: number | null;
  headEvicted: boolean;
  revision: number;
  placement: { top: boolean; anchor: DirectoryAnchor | null; locateId?: string };
}

export interface TitleEditor {
  target: ConversationListItem;
  draft: string;
  touched: boolean;
  ready: boolean;
  status: 'editing' | 'saving' | 'error' | 'uncertain';
  error: string | null;
}

const emptyWindow = (): WindowState => ({
  temporary: null, pinned: [], normal: [], cursor: null, hasMore: false, total: null,
  headEvicted: false, revision: 0, placement: {top: true, anchor: null}
});
const knownFailure = (e: unknown) => e instanceof AiApiError && e.httpStatus >= 400 && e.httpStatus < 500
  && e.httpStatus !== 408 && e.code !== '205027';
const titleFields = (value: ConversationTitleMetadata): ConversationTitleMetadata => ({
  title: value.title,
  metadataVersion: value.metadataVersion,
  titleManuallyEdited: value.titleManuallyEdited,
  autoTitleResolved: value.autoTitleResolved
});
const mergeTitle = (incoming: ConversationListItem, existing?: ConversationTitleMetadata) =>
  existing && existing.metadataVersion > incoming.metadataVersion ? {...incoming, ...titleFields(existing)} : incoming;
const unique = (items: ConversationListItem[]) => Array.from(new Map(items.map(item => [item.conversationId, item])).values());

function creationDate() {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(new Date());
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(value => value.type === type)?.value;
  return `${part('year')}-${part('month')}-${part('day')}`;
}

function rejectMessage(error: unknown) {
  if (error instanceof AiApiError) {
    if (error.code === '205043') return '最多可置顶7个会话，请先取消一个。';
    if (error.httpStatus === 409) return '名称或置顶已变化，请检查最新内容后重新保存。';
    if (error.httpStatus === 403) return '当前没有修改此会话的权限，草稿已保留。';
    if (error.httpStatus === 404) return '会话已不可访问，草稿已保留。';
  }
  return '保存结果未确认，请确认原操作，草稿和请求已保留。';
}

function validWindow(value: ConversationWindow) {
  for (const page of [value.pinned, value.normal]) {
    if (!Array.isArray(page.records) || typeof page.hasMore !== 'boolean'
      || (page.hasMore && !page.nextCursor)) throw new Error('Invalid conversation window');
  }
  if (value.pinned.records.length > 7 || value.normal.records.length + value.pinned.records.length > DIRECTORY_LIMIT)
    throw new Error('Conversation window exceeds limit');
}

/** 仅新业务页使用；目录投影与当前聊天详情各自持有，不缓存已回收页。容器按账号／scope 挂载。 */
export function useConversationDirectory(scope: AiScope, context: BrowserContext) {
  const stableScope = useMemo(() => scope, [scope.tenantId, scope.workspaceId]);
  const selection = useConversations(stableScope, context, false);
  const selectionRef = useRef(selection);
  selectionRef.current = selection;
  const [state, setState] = useState<WindowState>(emptyWindow);
  const current = useRef(state);
  const suspended = useRef(false);
  const replace = (next: WindowState) => {
    current.current = next;
    setState(next);
  };
  const [selectedName, setSelectedName] = useState<(ConversationTitleMetadata & {
    conversationId: string
  }) | null>(null);
  const selectedNameRef = useRef(selectedName);
  const setName = (value: typeof selectedName) => {
    selectedNameRef.current = value;
    setSelectedName(value);
  };
  const [restored] = useState(() => ({
    command: readOperation<ConversationMetadataRequest>(context, 'conversation-metadata', ''),
    create: readOperation<CreateDefaultConversationRequest>(context, 'create-default', ''),
    editor: (() => {
      const stored = readValue<TitleEditor>(storageKey(context, 'title-editor'));
      const v = stored.value;
      if (v && (typeof v.target?.conversationId !== 'string' || !v.target.conversationId || v.target.conversationId.length > 256
        || typeof v.target.title !== 'string' || !Number.isSafeInteger(v.target.metadataVersion)
        || v.target.metadataVersion < 0 || typeof v.draft !== 'string' || v.draft.length > 1_000_066))
        return {value: null, warning: RECOVERY_WARNING};
      return stored;
    })(),
  }));
  const recoveryBlocked = !!(restored.command.warning || restored.create.warning || restored.editor.warning) || selection.storageWarning === RECOVERY_WARNING;
  const [storageWarning, setStorageWarning] = useState<string | null>(restored.command.warning ?? restored.create.warning ?? restored.editor.warning);
  const restoreEditor = (): TitleEditor | null => {
    const pending = restored.command.value;
    if (pending?.request.kind === 'rename') return {
      target: {
        conversationId: pending.request.conversationId, title: pending.request.title,
        metadataVersion: pending.request.expectedMetadataVersion, pinned: false, titleManuallyEdited: false,
        autoTitleResolved: false, previewText: null, userMessageCount: null, lastMessageGeneratedAt: null,
        sortActivityAt: '', createdAt: '', statisticsReady: false
      },
      draft: pending.request.title, touched: true, ready: true, status: 'uncertain', error: '请确认原名称保存操作。',
    };
    const value = restored.editor.value;
    return value && typeof value.target?.conversationId === 'string' && typeof value.target.title === 'string'
    && Number.isSafeInteger(value.target.metadataVersion) && value.target.metadataVersion >= 0 && typeof value.draft === 'string'
      ? {...value, ready: false, status: 'editing', error: null} : null;
  };
  const [editor, setEditorState] = useState<TitleEditor | null>(restoreEditor);
  const editing = useRef(editor);
  const editSession = useRef(0);
  const setEditor = (value: TitleEditor | null) => {
    editing.current = value;
    setEditorState(value);
    const ok = value ? writeValue(storageKey(context, 'title-editor'), value) : removeValue(storageKey(context, 'title-editor'));
    if (!ok) setStorageWarning(STORAGE_WARNING);
  };
  const pending = useRef(restored.command.value);
  const [pendingCommand, setPendingCommand] = useState(pending.current);
  const persistCommand = (value: Operation<ConversationMetadataRequest> | null) => {
    pending.current = value;
    setPendingCommand(value);
    if (!saveOperation(context, 'conversation-metadata', '', value)) setStorageWarning(STORAGE_WARNING);
  };
  const pendingCreate = useRef(restored.create.value);
  const [hasPendingCreate, setHasPendingCreate] = useState(!!pendingCreate.current);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [listError, setListError] = useState<unknown>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreErrorState] = useState<string | null>(null);
  const moreFailure = useRef<string | null>(null);
  const setMoreError = (value: string | null) => {
    moreFailure.current = value;
    setMoreErrorState(value);
  };
  const [paused, setPausedState] = useState(false);
  const pausedRef = useRef(false);
  const setPaused = (value: boolean) => {
    pausedRef.current = value;
    setPausedState(value);
  };
  const [commandBusy, setCommandBusy] = useState(false);
  const [commandError, setCommandError] = useState<string | null>(null);
  const alive = useRef(true);
  const generation = useRef(0);
  const windowRead = useRef<AbortController | null>(null);
  const continuation = useRef<AbortController | null>(null);
  const targetRead = useRef<AbortController | null>(null);
  const writeRequest = useRef<AbortController | null>(null);
  const creatingRequest = useRef<AbortController | null>(null);
  const readingWindow = useRef<Promise<boolean> | null>(null);
  const readingMore = useRef<Promise<boolean> | null>(null);
  const saving = useRef<Promise<boolean> | null>(null);
  const writing = useRef<Promise<boolean> | null>(null);
  const creatingPromise = useRef<Promise<boolean> | null>(null);
  const capture = useRef<(excludeId?: string) => DirectoryAnchor | null>(() => null);
  const captureAnchor = useCallback((fn: (excludeId?: string) => DirectoryAnchor | null) => {
    capture.current = fn;
  }, []);
  const row = (id: string) => [current.current.temporary, ...current.current.pinned, ...current.current.normal].find(item => item?.conversationId === id) ?? undefined;

  function patchTitle(id: string, metadata: ConversationTitleMetadata) {
    if (typeof metadata.title !== 'string' || !Number.isSafeInteger(metadata.metadataVersion) || metadata.metadataVersion < 0) return;
    const value = current.current;
    const patch = (item: ConversationListItem) => item.conversationId === id && metadata.metadataVersion >= item.metadataVersion
      ? {...item, ...titleFields(metadata)} : item;
    const pinned = value.pinned.map(patch), normal = value.normal.map(patch);
    const temporary = value.temporary ? patch(value.temporary) : null;
    if (temporary !== value.temporary || pinned.some((item, i) => item !== value.pinned[i]) || normal.some((item, i) => item !== value.normal[i])) replace({
      ...value,
      temporary,
      pinned,
      normal
    });
    if (selectionRef.current.selectedId === id && (!selectedNameRef.current || selectedNameRef.current.conversationId !== id
      || metadata.metadataVersion >= selectedNameRef.current.metadataVersion)) setName({conversationId: id, ...titleFields(metadata)});
  }

  const titleCallback = useRef(patchTitle);
  titleCallback.current = patchTitle;
  const onTitleUpdated = useCallback((id: string, metadata: ConversationTitleMetadata) => titleCallback.current(id, metadata), []);

  function applyWindow(value: ConversationWindow, anchor: DirectoryAnchor | null, first: boolean) {
    validWindow(value);
    const before = current.current;
    const existing = new Map([...before.pinned, ...before.normal].map(item => [item.conversationId, item]));
    const merge = (item: ConversationListItem) => mergeTitle(item, existing.get(item.conversationId)
      ?? (selectedNameRef.current?.conversationId === item.conversationId ? selectedNameRef.current : undefined));
    const pinned = unique(value.pinned.records).map(merge);
    const ids = new Set(pinned.map(item => item.conversationId));
    const normal = unique(value.normal.records).filter(item => !ids.has(item.conversationId)).map(merge).slice(0, DIRECTORY_LIMIT - pinned.length);
    replace({
      temporary: null,
      pinned,
      normal,
      cursor: value.normal.nextCursor,
      hasMore: value.normal.hasMore,
      total: value.normal.total,
      headEvicted: !first && !value.anchorFallback && before.headEvicted,
      revision: before.revision + 1,
      placement: {top: first || value.anchorFallback, anchor: value.anchorFallback ? null : anchor}
    });
    const selected = [...pinned, ...normal].find(item => item.conversationId === selectionRef.current.selectedId);
    if (selected && (!selectedNameRef.current || selectedNameRef.current.conversationId !== selected.conversationId
      || selected.metadataVersion >= selectedNameRef.current.metadataVersion)) setName({conversationId: selected.conversationId, ...titleFields(selected)});
  }

  async function readWindow(first: boolean, excludeId?: string, forceNew = false) {
    if (forceNew) {
      windowRead.current?.abort();
      readingWindow.current = null;
    }
    if (readingWindow.current) return readingWindow.current;
    const batch = ++generation.current;
    continuation.current?.abort();
    readingMore.current = null;
    setLoadingMore(false);
    const controller = new AbortController();
    windowRead.current = controller;
    setLoading(true);
    setListError(null);
    setMoreError(null);
    setPaused(false);
    const anchor = first ? null : capture.current(excludeId);
    const fromFirst = first || !anchor;
    const run = (async () => {
      try {
        const {body} = await queryConversationWindow({
          scope,
          normalSize: fromFirst ? DIRECTORY_PAGE_SIZE : Math.max(DIRECTORY_PAGE_SIZE, current.current.normal.length),
          ...(anchor ? {anchorConversationId: anchor.id} : {})
        }, {signal: controller.signal});
        if (!alive.current || controller.signal.aborted || batch !== generation.current) return false;
        if (!suspended.current) applyWindow(body.data, anchor, fromFirst);
        return true;
      } catch (error) {
        if (alive.current && !controller.signal.aborted && batch === generation.current) setListError(error);
        return false;
      } finally {
        if (alive.current && batch === generation.current) {
          setLoading(false);
          readingWindow.current = null;
        }
      }
    })();
    readingWindow.current = run;
    return run;
  }

  async function loadMore(manual = false): Promise<boolean> {
    if (suspended.current) return false;
    if (readingMore.current) return readingMore.current;
    const before = current.current;
    if (!before.hasMore || !before.cursor || readingWindow.current || (!manual && (moreFailure.current || pausedRef.current))) return false;
    const victims = before.normal.slice(0, Math.max(0, before.normal.length + DIRECTORY_PAGE_SIZE - (DIRECTORY_LIMIT - before.pinned.length)));
    if (editing.current && victims.some(item => item.conversationId === editing.current?.target.conversationId)) {
      setPaused(true);
      return false;
    }
    const controller = new AbortController();
    continuation.current = controller;
    const batch = generation.current;
    const cursor = before.cursor;
    setLoadingMore(true);
    setMoreError(null);
    setPaused(false);
    const run = (async () => {
      try {
        const {body} = await listConversationsCursor({
          scope,
          pinned: false,
          size: DIRECTORY_PAGE_SIZE,
          cursor
        }, {signal: controller.signal});
        if (!alive.current || controller.signal.aborted || batch !== generation.current) return false;
        const page = body.data;
        if (!Array.isArray(page.records) || page.records.length > DIRECTORY_PAGE_SIZE || (page.hasMore && (!page.nextCursor || page.nextCursor === cursor)))
          throw new Error('Invalid continuation');
        const value = current.current;
        const ids = new Set([...value.pinned, ...value.normal].map(item => item.conversationId));
        const added = unique(page.records).filter(item => !ids.has(item.conversationId)).map(item => mergeTitle(item,
          item.conversationId === value.temporary?.conversationId ? value.temporary
            : item.conversationId === selectedNameRef.current?.conversationId ? selectedNameRef.current : undefined));
        if (!added.length && page.hasMore) throw new Error('Empty continuation');
        const combined = [...value.normal, ...added];
        const temporary = value.temporary && !combined.some(item => item.conversationId === value.temporary?.conversationId) ? value.temporary : null;
        const remove = Math.max(0, combined.length - (DIRECTORY_LIMIT - value.pinned.length - (temporary ? 1 : 0)));
        if (editing.current && combined.slice(0, remove).some(item => item.conversationId === editing.current?.target.conversationId)) {
          setPaused(true);
          return false;
        }
        replace({
          ...value, temporary, normal: combined.slice(remove), cursor: page.nextCursor, hasMore: page.hasMore,
          headEvicted: value.headEvicted || remove > 0, revision: value.revision + 1,
          placement: {top: false, anchor: capture.current()}
        });
        return true;
      } catch {
        if (alive.current && !controller.signal.aborted && batch === generation.current) setMoreError('下一页暂时加载失败，请重试。');
        return false;
      } finally {
        if (alive.current && batch === generation.current) {
          setLoadingMore(false);
          readingMore.current = null;
        }
      }
    })();
    readingMore.current = run;
    return run;
  }

  async function readEditor(id: string) {
    targetRead.current?.abort();
    const controller = new AbortController();
    targetRead.current = controller;
    try {
      const {body} = await getConversationMetadata({scope, conversationId: id}, {signal: controller.signal});
      if (!alive.current || controller.signal.aborted || editing.current?.target.conversationId !== id) return false;
      if (body.data.conversationId !== id) throw new Error('Mismatched conversation metadata');
      const value = editing.current;
      setEditor({
        ...value,
        target: mergeTitle(body.data, row(id)),
        draft: value.touched ? value.draft : mergeTitle(body.data, row(id)).title,
        ready: true
      });
      return true;
    } catch (error) {
      if (alive.current && !controller.signal.aborted && editing.current?.target.conversationId === id)
        setEditor({
          ...editing.current,
          ready: false,
          status: 'error',
          error: error instanceof AiApiError && [403, 404].includes(error.httpStatus)
            ? rejectMessage(error) : '无法读取最新名称，请重试，草稿已保留。'
        });
      return false;
    }
  }

  const editorRead = useRef<Promise<boolean> | null>(null);

  function closeEditor() {
    editSession.current++;
    targetRead.current?.abort();
    editorRead.current = null;
    setEditor(null);
    setPaused(false);
  }

  async function applyReceipt(receipt: ConversationMetadataReceipt, original: Operation<ConversationMetadataRequest>) {
    if (receipt.commandId !== original.key || receipt.conversationId !== original.request.conversationId
      || receipt.conversation.conversationId !== original.request.conversationId
      || receipt.conversation.metadataVersion < original.request.expectedMetadataVersion
      || (original.request.kind === 'pin' && receipt.conversation.pinned !== original.request.pinned)) throw new Error('Invalid metadata receipt');
    patchTitle(receipt.conversationId, receipt.conversation);
    persistCommand(null);
    setCommandError(null);
    if (original.request.kind === 'rename') closeEditor();
    else await readWindow(false, original.request.pinned ? original.request.conversationId : undefined);
  }

  async function rejectWrite(error: unknown, original: Operation<ConversationMetadataRequest>) {
    if (knownFailure(error)) {
      persistCommand(null);
      setCommandError(rejectMessage(error));
      if (original.request.kind === 'rename' && editing.current?.target.conversationId === original.request.conversationId) {
        setEditor({...editing.current, status: 'error', error: rejectMessage(error)});
        // 只读此目标，并保留草稿；错误后必须由用户明确再次保存。
        if (error instanceof AiApiError && error.httpStatus === 409) {
          const promise = readEditor(original.request.conversationId);
          editorRead.current = promise;
          await promise;
        }
      }
      if (original.request.kind === 'pin' && error instanceof AiApiError && error.httpStatus === 409) await readWindow(false);
    } else {
      setCommandError(rejectMessage(error));
      if (original.request.kind === 'rename' && editing.current) setEditor({
        ...editing.current,
        status: 'uncertain',
        error: rejectMessage(error)
      });
    }
    return false;
  }

  async function sendCommand(original: Operation<ConversationMetadataRequest>) {
    if (writing.current) return writing.current;
    const controller = new AbortController();
    writeRequest.current = controller;
    setCommandBusy(true);
    setCommandError(null);
    const run = (async () => {
      try {
        const {body} = await changeConversationMetadata(original.request, original.key, {signal: controller.signal});
        if (!alive.current || controller.signal.aborted) return false;
        await applyReceipt(body.data, original);
        return true;
      } catch (error) {
        if (!alive.current || controller.signal.aborted) return false;
        return await rejectWrite(error, original);
      } finally {
        if (alive.current) {
          setCommandBusy(false);
          writing.current = null;
        }
      }
    })();
    writing.current = run;
    return run;
  }

  async function finishEdit(explicit = false): Promise<boolean> {
    if (saving.current) return saving.current;
    if (recoveryBlocked && editing.current) return false;
    if (!editing.current) return true;
    if (pending.current || editing.current.status === 'uncertain' || (!explicit && editing.current.status === 'error')) return false;
    const session = editSession.current;
    const run = (async () => {
      if (editorRead.current && !await editorRead.current && !explicit) return false;
      if (session !== editSession.current) return true;
      if (!editing.current) return true;
      if (!editing.current.ready && !await readEditor(editing.current.target.conversationId)) return false;
      if (session !== editSession.current || !editing.current) return true;
      const value = editing.current;
      if (value.draft === value.target.title) {
        closeEditor();
        return true;
      }
      const title = value.draft.trim();
      if (!title || title.length > 256) {
        setEditor({...value, status: 'error', error: '会话名称不能为空，且不能超过256个字符。'});
        return false;
      }
      if (title === value.target.title) {
        closeEditor();
        return true;
      }
      const original: Operation<ConversationMetadataRequest> = {
        key: crypto.randomUUID(), request: {
          scope,
          kind: 'rename',
          conversationId: value.target.conversationId,
          expectedMetadataVersion: value.target.metadataVersion,
          title,
        }
      };
      persistCommand(original);
      setEditor({...value, status: 'saving', error: null});
      return sendCommand(original);
    })();
    saving.current = run;
    try {
      return await run;
    } finally {
      saving.current = null;
    }
  }

  async function beginEdit(id: string) {
    if (editing.current?.target.conversationId === id) return;
    if (!await finishEdit() || pending.current || readingWindow.current || recoveryBlocked) return;
    const value = row(id);
    if (!value || !alive.current) return;
    editSession.current++;
    setCommandError(null);
    setEditor({target: value, draft: value.title, touched: false, ready: false, status: 'editing', error: null});
    editorRead.current = readEditor(id);
  }

  function changeDraft(draft: string) {
    if (!editing.current || pending.current) return;
    setCommandError(null);
    setEditor({...editing.current, draft, touched: true, status: 'editing', error: null});
  }

  function cancelEdit() {
    if (pending.current || editing.current?.status === 'saving') return false;
    closeEditor();
    return true;
  }

  async function confirmCommand() {
    if (!pending.current || writing.current) return false;
    const original = pending.current;
    const controller = new AbortController();
    writeRequest.current = controller;
    setCommandBusy(true);
    const run = (async () => {
      try {
        const {body} = await getMetadataCommandReceipt({scope, commandId: original.key}, {signal: controller.signal});
        if (!alive.current || controller.signal.aborted) return false;
        if (body.data.commandId !== original.key) throw new Error('Invalid command receipt');
        if (body.data.receipt) {
          await applyReceipt(body.data.receipt, original);
          return true;
        }
        const codes: Record<string, string> = {
          VERSION_CONFLICT: '205030',
          PIN_LIMIT_REACHED: '205043',
          IDEMPOTENCY_CONFLICT: '205027'
        };
        return await rejectWrite(new AiApiError(body.data.outcome === 'NOT_FOUND' ? 404 : 409, {code: codes[body.data.outcome]}), original);
      } catch (error) {
        if (!alive.current || controller.signal.aborted) return false;
        // 404 不是“原操作未执行”的证明，只能原样重放，不能换键。
        if (error instanceof AiApiError && error.httpStatus === 404) {
          writing.current = null;
          return await sendCommand(original);
        }
        setCommandError('暂时无法确认原操作，请保留当前草稿并重试。');
        return false;
      } finally {
        if (alive.current) {
          setCommandBusy(false);
          writing.current = null;
        }
      }
    })();
    writing.current = run;
    return run;
  }

  async function setPinned(item: ConversationListItem) {
    if (!await finishEdit() || pending.current || writing.current || readingWindow.current || recoveryBlocked) return false;
    const actual = row(item.conversationId);
    if (!actual) return false;
    const original: Operation<ConversationMetadataRequest> = {
      key: crypto.randomUUID(), request: {
        scope,
        kind: 'pin',
        conversationId: actual.conversationId,
        expectedMetadataVersion: actual.metadataVersion,
        pinned: !actual.pinned,
      }
    };
    persistCommand(original);
    return sendCommand(original);
  }

  async function refreshFirst() {
    if (!await finishEdit() || pending.current) return false;
    suspended.current = false;
    return readWindow(true);
  }

  async function create(): Promise<boolean> {
    if (creatingPromise.current) return creatingPromise.current;
    // 在编辑保存等异步保护前固定点击时的日期；旧待确认请求不补日期、不换键。
    const candidate = pendingCreate.current ?? {
      key: crypto.randomUUID(), request: {scope, localDate: creationDate()}
    };
    if (!await finishEdit() || pending.current || recoveryBlocked) return false;
    if (creatingPromise.current) return creatingPromise.current;
    const run = (async () => {
      // 升级前遗留的创建请求只能确认原请求，不能换成新的默认命名请求。
      if (selectionRef.current.pendingTitle != null) {
        if (!await selectionRef.current.create(selectionRef.current.pendingTitle)) return false;
        if (!suspended.current) await readWindow(true, undefined, true);
        return true;
      }
      const original = pendingCreate.current ?? candidate;
      pendingCreate.current = original;
      setHasPendingCreate(true);
      if (!saveOperation(context, 'create-default', '', original)) setStorageWarning(STORAGE_WARNING);
      const controller = new AbortController();
      creatingRequest.current = controller;
      setCreating(true);
      setCreateError(null);
      try {
        const {body} = await createDefaultConversation(original.request, original.key, {signal: controller.signal});
        if (!alive.current || controller.signal.aborted) return false;
        pendingCreate.current = null;
        setHasPendingCreate(false);
        if (!saveOperation(context, 'create-default', '', null)) setStorageWarning(STORAGE_WARNING);
        void select(body.data.conversationId);
        if (!suspended.current) await readWindow(true, undefined, true);
        return true;
      } catch (error) {
        if (!alive.current || controller.signal.aborted) return false;
        setCreateError(error);
        if (knownFailure(error)) {
          pendingCreate.current = null;
          setHasPendingCreate(false);
          if (!saveOperation(context, 'create-default', '', null)) setStorageWarning(STORAGE_WARNING);
        }
        return false;
      } finally {
        if (alive.current) setCreating(false);
      }
    })();
    creatingPromise.current = run;
    try {
      return await run;
    } finally {
      creatingPromise.current = null;
    }
  }

  async function select(id: string) {
    if (!await finishEdit()) return false;
    if (current.current.temporary && current.current.temporary.conversationId !== id) replace({
      ...current.current,
      temporary: null
    });
    const metadata = row(id);
    setName(metadata ? {conversationId: id, ...titleFields(metadata)} : null);
    await selectionRef.current.select(id);
    return true;
  }

  function releaseRows() {
    suspended.current = true;
    generation.current++;
    windowRead.current?.abort();
    continuation.current?.abort();
    readingWindow.current = null;
    readingMore.current = null;
    setLoading(false);
    setLoadingMore(false);
    setListError(null);
    setMoreError(null);
    replace({...emptyWindow(), revision: current.current.revision + 1});
  }

  function openPrepared(detail: ConversationResponse, window?: ConversationWindow, metadata?: ConversationListItem) {
    if (window) {
      validWindow(window);
      if (!metadata || metadata.conversationId !== detail.conversationId) throw new Error('Missing target metadata');
      suspended.current = false;
      applyWindow(window, null, true);
      const s = current.current;
      const found = [...s.pinned, ...s.normal].some(item => item.conversationId === detail.conversationId);
      replace({
        ...s,
        temporary: found ? null : metadata,
        normal: s.normal.slice(0, DIRECTORY_LIMIT - s.pinned.length - (found ? 0 : 1)),
        placement: {top: false, anchor: null, locateId: detail.conversationId}
      });
      setName({conversationId: detail.conversationId, ...titleFields(metadata)});
    } else setName(null);
    selectionRef.current.commitSelection(detail);
  }

  useEffect(() => {
    alive.current = true;
    void readWindow(true);
    return () => {
      alive.current = false;
      generation.current++;
      windowRead.current?.abort();
      continuation.current?.abort();
      targetRead.current?.abort();
      writeRequest.current?.abort();
      creatingRequest.current?.abort();
      readingWindow.current = null;
      readingMore.current = null;
    };
  }, [context.userId, scope.tenantId, scope.workspaceId]);
  const unsafeToLeave = (!!storageWarning || !!selection.storageWarning) && (!!editor || !!pendingCommand || hasPendingCreate || creating || selection.unsafeToLeave);
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
  const selected = useMemo(() => selection.selected && selectedName?.conversationId === selection.selected.conversationId
    ? {...selection.selected, title: selectedName.title} : selection.selected, [selection.selected, selectedName]);
  return {
    ...selection, ...state,
    records: [...(state.temporary ? [state.temporary] : []), ...state.pinned, ...state.normal],
    selected,
    total: (state.total ?? state.normal.length) + state.pinned.length,
    listLoading: loading,
    listError,
    loadingMore,
    moreError,
    paused,
    loadMore,
    refreshFirst,
    captureAnchor,
    releaseRows,
    openPrepared,
    select,
    create,
    creating: creating || selection.creating,
    hasPendingCreate: hasPendingCreate || selection.pendingTitle != null,
    createError: createError ?? selection.createError,
    storageWarning: storageWarning ?? selection.storageWarning,
    unsafeToLeave,
    editor,
    beginEdit,
    changeDraft,
    cancelEdit,
    finishEdit,
    pendingCommand,
    commandBusy,
    commandError,
    confirmCommand,
    setPinned,
    onTitleUpdated,
    updateConversation: selection.updateConversation
  };
}

export type ConversationDirectory = ReturnType<typeof useConversationDirectory>;
