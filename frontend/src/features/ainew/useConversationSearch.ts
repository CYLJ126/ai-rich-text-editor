import {useCallback, useEffect, useRef, useState} from 'react';
import {AiApiError, closeSearch, searchConversations} from '@/services/arte-ai';
import type {ConversationSearchItem, ConversationSearchPage} from '@/services/arte-ai/conversation-search-contract';
import type {BrowserContext} from './storage';
import {searchQuery} from './searchText';

export type SearchRow = ConversationSearchItem | { entryKey: string; conversationId: string; unavailable: true };
type Page = Omit<ConversationSearchPage, 'records'> & { records: SearchRow[] };
export type SearchDirection = 'before' | 'after' | 'first';
export type SearchInvalidation = {
  kind: 'entry' | 'conversation' | 'scope';
  conversationId?: string;
  entryKey?: string
};

export class SearchTargetUnavailable extends Error {
  constructor(readonly kind: 'entry' | 'conversation') {
    super('该结果已不可用');
  }
}

export class SearchNavigationCancelled extends Error {
}

const initial = () => ({
  active: false,
  pages: [] as Page[],
  query: '',
  session: '',
  loading: false,
  paging: null as SearchDirection | null,
  error: '',
  expired: false,
  partial: false,
  revision: 0,
  placement: 'first' as SearchDirection,
  busy: null as { key: string; mode: 'message' | 'conversation' } | null
});

function errorMessage(error: unknown) {
  if (error instanceof AiApiError) {
    if (error.httpStatus === 410) return '搜索已过期，按 Enter 重新搜索';
    if (error.httpStatus === 429) return '活动搜索已达上限，请先关闭另一个标签页或设备上的搜索。';
    if (error.httpStatus === 401) return '登录已失效，请重新登录。';
    if (error.httpStatus === 403) return '当前没有读取此范围内容的权限。';
    if (error.httpStatus === 409 && error.code === '205030') return '会话历史仍在变化，请再次打开目标。';
    if (error.httpStatus === 400) return error.desc || '关键词不可用，请修改后按 Enter 搜索。';
  }
  return '读取暂时失败；首次搜索按 Enter 重查，翻页可再次显式加载。';
}

/** 按账号／scope 挂载；只保存五个有界页段，没有历史 seen 集合和持久搜索缓存。 */
export function useConversationSearch(context: BrowserContext, options: {
  beforeEnter: () => Promise<boolean>; onEnter: () => void; onExit: () => Promise<unknown>;
  onInvalidated: (value: SearchInvalidation) => void;
}) {
  const [input, setInput] = useState('');
  const [state, setState] = useState(initial);
  const current = useRef(state), handlers = useRef(options);
  handlers.current = options;
  const alive = useRef(true), generation = useRef(0), composing = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const request = useRef<AbortController | null>(null), navigation = useRef<AbortController | null>(null);
  const pendingKey = useRef<string | null>(null);
  const placementCapture = useRef<() => void>(() => {
  });
  const captureAnchor = useCallback((fn: () => void) => {
    placementCapture.current = fn;
    return () => {
      if (placementCapture.current === fn) placementCapture.current = () => {
      };
    };
  }, []);
  const publish = (value: typeof state) => {
    current.current = value;
    if (alive.current) setState(value);
  };
  const close = (id: string) => {
    if (id) void closeSearch({scope: context.scope, searchSessionId: id}).catch(() => {
    });
  };

  function cancel() {
    generation.current++;
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
    request.current?.abort();
    navigation.current?.abort();
    pendingKey.current = null;
  }

  function reset(keepInput: boolean) {
    cancel();
    close(current.current.session);
    publish(initial());
    if (!keepInput) setInput('');
  }

  async function exit(refresh = true) {
    reset(false);
    if (refresh) await handlers.current.onExit();
  }

  function invalidate(value: SearchInvalidation) {
    if (value.kind === 'scope') {
      reset(false);
      handlers.current.onInvalidated(value);
      return;
    }
    const s = current.current;
    cancel();
    publish({
      ...s, busy: null, paging: null, loading: false, pages: s.pages.map(page => ({
        ...page, records: page.records.map(row =>
          (value.kind === 'conversation' ? row.conversationId === value.conversationId : row.entryKey === value.entryKey)
            ? {entryKey: row.entryKey, conversationId: row.conversationId, unavailable: true} : row)
      }))
    });
    handlers.current.onInvalidated(value);
  }

  async function submit(raw = input) {
    const parsed = searchQuery(raw);
    if (composing.current) return;
    if (parsed.empty) {
      await exit();
      return;
    }
    if (pendingKey.current === parsed.query) return;
    cancel();
    close(current.current.session);
    const batch = generation.current;
    publish({...initial(), active: true, query: parsed.query, loading: parsed.valid});
    if (!parsed.valid) {
      publish({...current.current, error: '关键词最多 128 个可见字符。'});
      return;
    }
    pendingKey.current = parsed.query;
    try {
      if (!await handlers.current.beforeEnter() || batch !== generation.current || !alive.current) {
        if (batch === generation.current) publish({
          ...current.current,
          active: false,
          loading: false,
          error: '请先完成或取消会话名称编辑或原操作。'
        });
        return;
      }
      handlers.current.onEnter();
      const controller = new AbortController();
      request.current = controller;
      const {body} = await searchConversations({
        scope: context.scope,
        query: parsed.query,
        groupIds: [],
        size: 20
      }, {signal: controller.signal});
      if (batch !== generation.current || controller.signal.aborted || !alive.current) {
        close(body.data.searchSessionId);
        return;
      }
      validate(body.data);
      publish({
        ...current.current,
        pages: [body.data],
        session: body.data.searchSessionId,
        loading: false,
        partial: body.data.partialPage,
        revision: current.current.revision + 1
      });
    } catch (error) {
      if (batch !== generation.current || !alive.current) return;
      if (error instanceof AiApiError && [401, 403].includes(error.httpStatus)) {
        invalidate({kind: 'scope'});
        publish({...current.current, error: errorMessage(error)});
      } else publish({...current.current, loading: false, error: errorMessage(error)});
    } finally {
      if (batch === generation.current) pendingKey.current = null;
    }
  }

  function change(value: string) {
    setInput(value);
    if (!composing.current && searchQuery(value).query === current.current.query && current.current.active && (current.current.pages.length > 0 || pendingKey.current)) return;
    if (!current.current.active && searchQuery(value).empty) return;
    // 即使组词或空输入等待 Enter，旧片段也立即释放；此时不显示无意义的持续加载。
    cancel();
    close(current.current.session);
    const parsed = searchQuery(value);
    publish({...initial(), active: true, query: parsed.query, loading: !composing.current && parsed.valid});
    if (!composing.current && !parsed.empty) timer.current = setTimeout(() => {
      timer.current = null;
      void submit(value);
    }, 300);
  }

  function composition(start: boolean, value = input) {
    composing.current = start;
    if (start) {
      cancel();
      close(current.current.session);
      publish({...initial(), active: current.current.active, query: '', loading: false});
    } else change(value);
  }

  async function load(direction: SearchDirection) {
    const s = current.current;
    if (!s.active || s.loading || s.paging || s.busy || s.expired || !s.pages.length) return;
    const cursor = direction === 'first' ? s.pages[0].firstCursor : direction === 'before' ? s.pages[0].previousCursor : s.pages.at(-1)!.nextCursor;
    if (!cursor) return;
    const batch = generation.current, controller = new AbortController();
    request.current = controller;
    publish({...s, paging: direction, error: ''});
    try {
      const {body} = await searchConversations({
        scope: context.scope,
        query: s.query,
        groupIds: [],
        size: 20,
        cursor
      }, {signal: controller.signal});
      if (batch !== generation.current || controller.signal.aborted || !alive.current) return;
      validate(body.data);
      if (body.data.searchSessionId !== s.session) throw new Error('Search session changed');
      let pages: Page[] = direction === 'first' ? [body.data] : direction === 'before' ? [body.data, ...s.pages].slice(0, 5) : [...s.pages, body.data].slice(-5);
      // 只在窗口内去重；新页记录覆盖旧副本，失效占位不会被旧缓存重新放回。
      const fresh = new Set(body.data.records.map(row => row.entryKey));
      pages = pages.map(page => page === body.data ? page : {
        ...page,
        records: page.records.filter(row => !fresh.has(row.entryKey))
      });
      // 读取期间仍可滚动；提交新窗口前取当前锚点，不拉回请求发起时的位置。
      placementCapture.current();
      publish({
        ...current.current,
        pages,
        paging: null,
        partial: body.data.partialPage,
        revision: s.revision + 1,
        placement: direction
      });
    } catch (error) {
      if (batch !== generation.current || !alive.current) return;
      if (error instanceof AiApiError && [401, 403].includes(error.httpStatus)) {
        invalidate({kind: 'scope'});
        publish({...current.current, error: errorMessage(error)});
      } else publish({
        ...current.current,
        paging: null,
        error: errorMessage(error),
        expired: error instanceof AiApiError && error.httpStatus === 410
      });
    }
  }

  async function open(row: ConversationSearchItem, mode: 'message' | 'conversation', work: (signal: AbortSignal) => Promise<() => void>) {
    const s = current.current;
    if (!s.active || s.loading || s.paging || s.busy || !s.pages.some(p => p.records.some(r => r.entryKey === row.entryKey && !('unavailable' in r)))) return;
    const batch = generation.current, controller = new AbortController();
    navigation.current = controller;
    publish({...s, busy: {key: row.entryKey, mode}, error: ''});
    try {
      const commit = await work(controller.signal);
      if (!alive.current || controller.signal.aborted || batch !== generation.current) return;
      commit();
      if (mode === 'conversation') await exit(false);
    } catch (error) {
      if (!alive.current || controller.signal.aborted || batch !== generation.current) return;
      if (error instanceof SearchNavigationCancelled) return;
      if (error instanceof SearchTargetUnavailable) invalidate({
        kind: error.kind,
        conversationId: row.conversationId,
        entryKey: row.entryKey
      });
      else if (error instanceof AiApiError && [401, 403].includes(error.httpStatus)) invalidate({kind: 'scope'});
      publish({
        ...current.current,
        error: error instanceof SearchTargetUnavailable ? '该结果已不可用' : errorMessage(error)
      });
    } finally {
      if (batch === generation.current) publish({...current.current, busy: null});
    }
  }

  useEffect(() => {
    alive.current = true;
    const logout = (event: Event) => {
      if ((event as CustomEvent).detail === context.userId) reset(false);
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      alive.current = false;
      cancel();
      close(current.current.session);
      current.current = initial();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [context.userId, context.scope.tenantId, context.scope.workspaceId]);
  return {
    ...state,
    input,
    change,
    submit,
    composition,
    exit,
    load,
    open,
    invalidate,
    captureAnchor,
    rows: state.pages.flatMap(page => page.records),
    previous: state.pages[0]?.previousCursor ?? null,
    next: state.pages.at(-1)?.nextCursor ?? null
  };
}

function validate(page: ConversationSearchPage) {
  if (!page.searchSessionId || !page.firstCursor || page.total !== null || !Array.isArray(page.records) || page.records.length > 20
    || page.records.some(row => !row.entryKey || !row.conversationId || typeof row.title !== 'string' || !row.snippet || typeof row.snippet.text !== 'string' || !Array.isArray(row.snippet.highlights))) throw new Error('Invalid search page');
}

export type ConversationSearch = ReturnType<typeof useConversationSearch>;
