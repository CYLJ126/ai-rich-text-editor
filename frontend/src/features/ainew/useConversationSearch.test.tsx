import {act, cleanup, renderHook} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/arte-ai';
import {SearchTargetUnavailable, useConversationSearch} from './useConversationSearch';
import {searchExcerpt, searchQuery} from './searchText';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai', async original => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  searchConversations: vi.fn(),
  closeSearch: vi.fn()
}));
const context = {userId: 'search-user', scope: {tenantId: 't', workspaceId: 'w'}};
const row = (n: number, conv = `c${n}`): api.ConversationSearchItem => ({
  entryKey: `e${n}`,
  conversationId: conv,
  title: `标题${n}`,
  snippet: {text: `正文${n}`, highlights: []},
  matchKind: 'MESSAGE',
  canLocateMessage: true,
  turnId: `t${n}`,
  messageId: `m${n}`,
  invocationId: null,
  candidateOrdinal: null,
  partial: false
});
const page = (start = 0, count = 20, partial = false): api.ConversationSearchPage => ({
  searchSessionId: 'sid',
  records: Array.from({length: count}, (_, n) => row(start + n)),
  previousCursor: start ? `before${start}` : null,
  nextCursor: `after${start + count}`,
  firstCursor: 'first',
  partialPage: partial,
  endReason: null,
  total: null
});
const response = (data: api.ConversationSearchPage) => ({
  httpStatus: 200,
  body: {success: true as const, code: '0', desc: 'ok', data}
});

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((ok, fail) => {
    resolve = ok;
    reject = fail;
  });
  return {promise, resolve, reject};
}

const options = () => ({
  beforeEnter: vi.fn(async () => true),
  onEnter: vi.fn(),
  onExit: vi.fn(async () => true),
  onInvalidated: vi.fn()
});
beforeEach(() => {
  vi.useFakeTimers();
  vi.mocked(api.searchConversations).mockReset();
  vi.mocked(api.closeSearch).mockReset();
  vi.mocked(api.closeSearch).mockResolvedValue({httpStatus: 200, body: {success: true, code: '0', desc: 'ok'}});
  vi.mocked(api.searchConversations).mockResolvedValue(response(page()));
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

it('debounces valid changes, preserves emoji, pauses IME, merges in-flight Enter and renews only on explicit Enter', async () => {
  const h = renderHook(() => useConversationSearch(context, options()));
  act(() => h.result.current.composition(true));
  act(() => h.result.current.change('中文'));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(500);
    await h.result.current.submit();
  });
  expect(api.searchConversations).not.toHaveBeenCalled();
  act(() => h.result.current.composition(false, '中文'));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(299);
  });
  expect(api.searchConversations).not.toHaveBeenCalled();
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1);
  });
  expect(api.searchConversations).toHaveBeenCalledOnce();
  act(() => h.result.current.change(' 中文 '));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(500);
  });
  expect(api.searchConversations).toHaveBeenCalledOnce();
  const pending = deferred<ReturnType<typeof response>>();
  vi.mocked(api.searchConversations).mockReturnValue(pending.promise);
  let done!: Promise<void>;
  act(() => {
    done = h.result.current.submit();
  });
  await act(async () => {
    await h.result.current.submit();
  });
  expect(api.searchConversations).toHaveBeenCalledTimes(2);
  await act(async () => {
    pending.resolve(response(page()));
    await done;
  });
  expect(searchQuery('\u200b\ufeff\u0301\n').empty).toBe(true);
  expect(searchQuery('👨‍👩‍👧‍👦').valid).toBe(true);
  expect(searchQuery('汉'.repeat(129)).valid).toBe(false);
});
it('removes A immediately, closes late A, and never restores A on B failure', async () => {
  const a = deferred<ReturnType<typeof response>>(), b = deferred<ReturnType<typeof response>>();
  vi.mocked(api.searchConversations).mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);
  const h = renderHook(() => useConversationSearch(context, options()));
  let first!: Promise<void>;
  act(() => {
    first = h.result.current.submit('A');
  });
  await act(async () => {
  });
  act(() => h.result.current.change('B'));
  expect(h.result.current.rows).toEqual([]);
  expect(h.result.current.loading).toBe(true);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(300);
  });
  await act(async () => {
    a.resolve(response({...page(), searchSessionId: 'old'}));
    await first;
  });
  expect(h.result.current.rows).toEqual([]);
  await act(async () => {
    b.reject(new Error('offline'));
  });
  expect(h.result.current.rows).toEqual([]);
  expect(h.result.current.loading).toBe(false);
  expect(api.closeSearch).toHaveBeenCalledWith({scope: context.scope, searchSessionId: 'old'});
});
it('retains the creating request for cleanup and starts only the latest query after the old session closes', async () => {
  const a = deferred<ReturnType<typeof response>>();
  const closed = deferred<Awaited<ReturnType<typeof api.closeSearch>>>();
  vi.mocked(api.searchConversations).mockImplementationOnce((_query, options) => {
    options?.signal?.addEventListener('abort', () => a.reject(new DOMException('Aborted', 'AbortError')));
    return a.promise;
  });
  vi.mocked(api.closeSearch).mockReturnValueOnce(closed.promise);
  const h = renderHook(() => useConversationSearch(context, options()));
  let first!: Promise<void>;
  await act(async () => {
    first = h.result.current.submit('A');
  });
  act(() => h.result.current.change('B'));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(300);
  });
  act(() => h.result.current.change('C'));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(300);
  });
  expect(api.searchConversations).toHaveBeenCalledOnce();
  expect(h.result.current.rows).toEqual([]);
  await act(async () => {
    a.resolve(response({...page(), searchSessionId: 'old'}));
  });
  expect(api.closeSearch).toHaveBeenCalledWith({scope: context.scope, searchSessionId: 'old'});
  expect(api.searchConversations).toHaveBeenCalledOnce();
  await act(async () => {
    closed.resolve({httpStatus: 200, body: {success: true, code: '0', desc: 'ok'}});
    await first;
  });
  expect(api.searchConversations).toHaveBeenCalledTimes(2);
  expect(api.searchConversations).toHaveBeenLastCalledWith(expect.objectContaining({query: 'C'}));
  expect(h.result.current.query).toBe('C');
  expect(h.result.current.rows).toHaveLength(20);
});
it('waits for known-session release and retries failed release before allocating another slot', async () => {
  const h = renderHook(() => useConversationSearch(context, options()));
  await act(async () => {
    await h.result.current.submit('A');
  });
  vi.mocked(api.closeSearch).mockRejectedValue(new Error('offline'));
  act(() => h.result.current.change('B'));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(300);
  });
  expect(api.searchConversations).toHaveBeenCalledOnce();
  expect(h.result.current.loading).toBe(false);
  expect(h.result.current.error).toBeTruthy();
  const closed = deferred<Awaited<ReturnType<typeof api.closeSearch>>>();
  vi.mocked(api.closeSearch).mockReturnValueOnce(closed.promise);
  let retry!: Promise<void>;
  await act(async () => {
    retry = h.result.current.submit('B');
  });
  expect(api.searchConversations).toHaveBeenCalledOnce();
  await act(async () => {
    closed.resolve({httpStatus: 200, body: {success: true, code: '0', desc: 'ok'}});
    await retry;
  });
  expect(api.searchConversations).toHaveBeenCalledTimes(2);
  expect(h.result.current.error).toBe('');
});
it('replaces searches without hitting the three-session cap when two other searches are active', async () => {
  let active = 2, created = 0;
  let release = deferred<void>();
  vi.mocked(api.searchConversations).mockImplementation(async () => {
    if (active >= 3) throw new api.AiApiError(429, {code: '205045'});
    active++;
    return response({...page(), searchSessionId: `session-${++created}`});
  });
  vi.mocked(api.closeSearch).mockImplementation(async () => {
    await release.promise;
    active--;
    return {httpStatus: 200, body: {success: true, code: '0', desc: 'ok'}};
  });
  const h = renderHook(() => useConversationSearch(context, options()));
  await act(async () => {
    await h.result.current.submit('q0');
  });
  for (let n = 1; n <= 4; n++) {
    let next!: Promise<void>;
    await act(async () => {
      next = h.result.current.submit(`q${n}`);
    });
    expect(created).toBe(n);
    expect(active).toBe(3);
    await act(async () => {
      release.resolve();
      await next;
    });
    expect(created).toBe(n + 1);
    expect(active).toBe(3);
    expect(h.result.current.error).toBe('');
    release = deferred<void>();
  }
  h.unmount();
  await act(async () => {
    release.resolve();
  });
  expect(active).toBe(2);
});
it.each(['exit', 'unmount', 'scope'] as const)('closes a late initial response after %s without restoring results', async mode => {
  const pending = deferred<ReturnType<typeof response>>();
  vi.mocked(api.searchConversations).mockImplementationOnce((_query, options) => {
    options?.signal?.addEventListener('abort', () => pending.reject(new DOMException('Aborted', 'AbortError')));
    return pending.promise;
  });
  const h = renderHook(() => useConversationSearch(context, options()));
  let first!: Promise<void>;
  await act(async () => {
    first = h.result.current.submit('A');
  });
  if (mode === 'unmount') h.unmount();
  else if (mode === 'exit') await act(async () => {
    await h.result.current.exit();
  });
  else act(() => h.result.current.invalidate({kind: 'scope'}));
  await act(async () => {
    pending.resolve(response({...page(), searchSessionId: 'late'}));
    await first;
  });
  expect(api.closeSearch).toHaveBeenCalledWith({scope: context.scope, searchSessionId: 'late'});
  expect(api.searchConversations).toHaveBeenCalledOnce();
  expect(h.result.current.rows).toEqual([]);
});
it('exits on invisible Enter or explicit clear, never persists query or fragments', async () => {
  const o = options(), h = renderHook(() => useConversationSearch(context, o));
  await act(async () => {
    await h.result.current.submit('敏感词');
  });
  expect(o.onEnter).toHaveBeenCalledOnce();
  act(() => h.result.current.change('\u200b '));
  expect(h.result.current.loading).toBe(false);
  await act(async () => {
    await h.result.current.submit();
  });
  expect(h.result.current.active).toBe(false);
  expect(o.onExit).toHaveBeenCalledOnce();
  expect(api.searchConversations).toHaveBeenCalledOnce();
  expect(sessionStorage.getItem('敏感词')).toBeNull();
  expect(Object.values(sessionStorage).join('')).not.toContain('正文0');
});
it('keeps a bounded five-page window with reverse and same-PIT first boundaries; failures preserve the cursor', async () => {
  vi.mocked(api.searchConversations).mockImplementation(async r => response(r.cursor?.startsWith('after') ? page(Number(r.cursor.slice(5))) : r.cursor?.startsWith('before') ? page(Number(r.cursor.slice(6)) - 20) : page()));
  const h = renderHook(() => useConversationSearch(context, options()));
  await act(async () => {
    await h.result.current.submit('词');
  });
  for (let n = 0; n < 7; n++) await act(async () => {
    await h.result.current.load('after');
  });
  expect(h.result.current.rows).toHaveLength(100);
  expect(h.result.current.rows[0].entryKey).toBe('e60');
  expect(h.result.current.previous).toBe('before60');
  await act(async () => {
    await h.result.current.load('before');
  });
  expect(h.result.current.rows[0].entryKey).toBe('e40');
  expect(h.result.current.rows).toHaveLength(100);
  const old = h.result.current.next;
  vi.mocked(api.searchConversations).mockRejectedValueOnce(new Error('offline'));
  await act(async () => {
    await h.result.current.load('after');
  });
  expect(h.result.current.next).toBe(old);
  expect(h.result.current.rows).toHaveLength(100);
  await act(async () => {
    await h.result.current.load('first');
  });
  expect(h.result.current.rows[0].entryKey).toBe('e0');
  expect(api.searchConversations).toHaveBeenLastCalledWith(expect.objectContaining({cursor: 'first'}), expect.anything());
});
it('keeps empty budget pages explicit, marks expiry without removing valid rows, and still allows location', async () => {
  const h = renderHook(() => useConversationSearch(context, options()));
  await act(async () => {
    await h.result.current.submit('词');
  });
  vi.mocked(api.searchConversations).mockResolvedValueOnce(response({...page(20, 0, true), nextCursor: 'more'}));
  await act(async () => {
    await h.result.current.load('after');
  });
  expect(h.result.current.next).toBe('more');
  expect(h.result.current.partial).toBe(true);
  vi.mocked(api.searchConversations).mockRejectedValueOnce(new api.AiApiError(410, {code: '205044'}));
  await act(async () => {
    await h.result.current.load('after');
  });
  expect(h.result.current.expired).toBe(true);
  expect(h.result.current.rows).toHaveLength(20);
  const commit = vi.fn();
  await act(async () => {
    await h.result.current.open(row(0), 'message', async () => commit);
  });
  expect(commit).toHaveBeenCalledOnce();
  expect(h.result.current.active).toBe(true);
});
it('scrubs every row of a known invalid conversation and rejects an in-flight page that could restore it', async () => {
  const o = options(), h = renderHook(() => useConversationSearch(context, o));
  vi.mocked(api.searchConversations).mockResolvedValueOnce(response({
    ...page(),
    records: [row(0, 'c'), row(1, 'c'), row(2, 'other')]
  }));
  await act(async () => {
    await h.result.current.submit('词');
  });
  const pending = deferred<ReturnType<typeof response>>();
  vi.mocked(api.searchConversations).mockReturnValueOnce(pending.promise);
  let read!: Promise<void>;
  act(() => {
    read = h.result.current.load('after');
  });
  act(() => h.result.current.invalidate({kind: 'conversation', conversationId: 'c'}));
  await act(async () => {
    pending.resolve(response({...page(), records: [row(0, 'c')]}));
    await read;
  });
  expect(h.result.current.rows[0]).toEqual({entryKey: 'e0', conversationId: 'c', unavailable: true});
  expect(JSON.stringify(h.result.current.pages)).not.toContain('正文0');
  expect(h.result.current.rows[2]).toEqual(row(2, 'other'));
});
it('does not clear search on failed navigation, scrubs only a missing entry, and clears on successful lower action', async () => {
  const h = renderHook(() => useConversationSearch(context, options()));
  await act(async () => {
    await h.result.current.submit('词');
  });
  await act(async () => {
    await h.result.current.open(row(0), 'conversation', async () => {
      throw new Error('offline');
    });
  });
  expect(h.result.current.active).toBe(true);
  await act(async () => {
    await h.result.current.open(row(0), 'message', async () => {
      throw new SearchTargetUnavailable('entry');
    });
  });
  expect(h.result.current.rows[0]).toEqual({entryKey: 'e0', conversationId: 'c0', unavailable: true});
  await act(async () => {
    await h.result.current.open(row(1), 'conversation', async () => () => {
    });
  });
  expect(h.result.current.active).toBe(false);
  expect(h.result.current.input).toBe('');
});
it('clears all scope content on explicit denial and closes the known session on unmount', async () => {
  const o = options(), h = renderHook(() => useConversationSearch(context, o));
  await act(async () => {
    await h.result.current.submit('词');
  });
  vi.mocked(api.searchConversations).mockRejectedValueOnce(new api.AiApiError(403, {}));
  await act(async () => {
    await h.result.current.load('after');
  });
  expect(h.result.current.rows).toEqual([]);
  expect(o.onInvalidated).toHaveBeenCalledWith({kind: 'scope'});
  await act(async () => {
    await h.result.current.submit('词');
  });
  h.unmount();
  expect(api.closeSearch).toHaveBeenCalledWith({scope: context.scope, searchSessionId: 'sid'});
});
it('crops a snippet around its actual UTF-16 hit without splitting a grapheme', () => {
  const text = '👨‍👩‍👧‍👦'.repeat(250) + '命中' + '后'.repeat(100), start = text.indexOf('命中');
  const excerpt = searchExcerpt({text, highlights: [{start, end: start + 2}]});
  expect(excerpt.text).toContain('命中');
  expect(excerpt.text.slice(excerpt.highlights[0].start, excerpt.highlights[0].end)).toBe('命中');
  expect([...new Intl.Segmenter(undefined, {granularity: 'grapheme'}).segment(excerpt.text)].length).toBeLessThanOrEqual(182);
});
it('keeps the ordinary editor reachable when entering search is rejected', async () => {
  const o = options();
  o.beforeEnter.mockResolvedValue(false);
  const h = renderHook(() => useConversationSearch(context, o));
  await act(async () => {
    await h.result.current.submit('词');
  });
  expect(h.result.current.active).toBe(false);
  expect(h.result.current.loading).toBe(false);
  expect(o.onEnter).not.toHaveBeenCalled();
  expect(api.searchConversations).not.toHaveBeenCalled();
});
