import {act, cleanup, renderHook, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/arte-ai';
import {readOperation, saveOperation, storageKey, writeValue} from './storage';
import {useConversationDirectory} from './useConversationDirectory';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai', async original => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  getConversation: vi.fn(), listConversations: vi.fn(), createConversation: vi.fn(), createDefaultConversation: vi.fn(),
  queryConversationWindow: vi.fn(), listConversationsCursor: vi.fn(), getConversationMetadata: vi.fn(),
  changeConversationMetadata: vi.fn(), getMetadataCommandReceipt: vi.fn(),
}));
const scope = {tenantId: 't', workspaceId: 'w'}, context = {userId: 'u', scope};
const item = (n: number, pinned = false): api.ConversationListItem => ({
  conversationId: `c${n}`, title: `会话${n}`,
  metadataVersion: 0, pinned, titleManuallyEdited: false, autoTitleResolved: false, previewText: `问题${n}`,
  userMessageCount: n, lastMessageGeneratedAt: '2026-10-14T12:00:00Z', sortActivityAt: '2026-10-14T12:00:00Z',
  createdAt: '2026-10-14T12:00:00Z', statisticsReady: true
});
const reply = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'ok', data}});
const page = (records: api.ConversationListItem[], cursor: string | null = null): api.ConversationCursorPage => ({
  records,
  nextCursor: cursor,
  hasMore: !!cursor,
  total: null
});
const windowData = (start = 0, pins = 0, size = 20): api.ConversationWindow => ({
  pinned: page(Array.from({length: pins}, (_, n) => item(10_000 + n, true))),
  normal: {...page(Array.from({length: size}, (_, n) => item(start + n)), `cursor-${start + size}`), total: 1000},
  anchorConversationId: start ? `c${start}` : null, anchorFallback: false,
});
const detail = (id: string): api.ConversationResponse => ({
  conversationId: id,
  title: '详情名称',
  version: 3,
  chatProfile: null,
  resources: [],
  state: 'ACTIVE',
  createdAt: '2026-10-14T12:00:00Z',
  updatedAt: '2026-10-14T12:00:00Z'
});
const receipt = (r: api.ConversationMetadataRequest, key: string): api.ConversationMetadataReceipt => ({
  conversationId: r.conversationId, commandId: key, changed: true, pinnedCount: 1,
  conversation: {
    ...item(Number(r.conversationId.slice(1))), metadataVersion: r.expectedMetadataVersion + 1,
    ...(r.kind === 'rename' ? {title: r.title, titleManuallyEdited: true} : {pinned: r.pinned})
  },
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

const setup = () => renderHook(() => useConversationDirectory(scope, context));
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  localStorage.clear();
  vi.mocked(api.queryConversationWindow).mockResolvedValue(reply(windowData()));
  vi.mocked(api.getConversation).mockImplementation(async r => reply(detail(r.conversationId)));
  vi.mocked(api.getConversationMetadata).mockImplementation(async r => reply(item(Number(r.conversationId.slice(1)))));
  vi.mocked(api.listConversationsCursor).mockImplementation(async r => {
    const start = Number(r.cursor?.split('-')[1]);
    return reply(page(Array.from({length: 20}, (_, n) => item(start + n)), `cursor-${start + 20}`));
  });
  vi.mocked(api.changeConversationMetadata).mockImplementation(async (r, key) => reply(receipt(r, key)));
  vi.mocked(api.createDefaultConversation).mockResolvedValue(reply(detail('new')));
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

async function loaded(pins = 0) {
  vi.mocked(api.queryConversationWindow).mockResolvedValue(reply(windowData(0, pins)));
  const h = setup();
  await waitFor(() => expect(h.result.current.records).toHaveLength(20 + pins));
  return h;
}

async function edit(h: Awaited<ReturnType<typeof loaded>>, id = 'c0', text = '我的名称') {
  await act(async () => {
    await h.result.current.beginEdit(id);
  });
  await waitFor(() => expect(h.result.current.editor?.ready).toBe(true));
  act(() => h.result.current.changeDraft(text));
}

async function advance(h: Awaited<ReturnType<typeof loaded>>, n: number) {
  for (let i = 0; i < n; i++) await act(async () => {
    await h.result.current.loadMore();
  });
}

it('isolates directory from selection, full detail updates, title feedback and window focus', async () => {
  writeValue(storageKey(context, 'pins'), ['c50'], true);
  const h = await loaded();
  await act(async () => {
    await h.result.current.select('c0');
  });
  act(() => h.result.current.updateConversation({...detail('c0'), version: 99, title: '旧详情'}));
  act(() => h.result.current.onTitleUpdated('c0', {...item(0), title: '首问标题', metadataVersion: 1}));
  h.rerender();
  window.dispatchEvent(new Event('focus'));
  expect(api.listConversations).not.toHaveBeenCalled();
  expect(api.queryConversationWindow).toHaveBeenCalledTimes(1);
  expect(api.listConversationsCursor).not.toHaveBeenCalled();
  expect(h.result.current.normal[0].title).toBe('首问标题');
  expect(h.result.current.normal[0].userMessageCount).toBe(0);
  expect(h.result.current.selected?.version).toBe(99);
  expect(h.result.current.selected?.title).toBe('首问标题');
  expect(h.result.current.pinned).toHaveLength(0);
});
it('keeps at most 100 rows including seven pins through 1000 rows and returns to database page one', async () => {
  const h = await loaded(7);
  await act(async () => {
    await h.result.current.select('c0');
  });
  for (let n = 0; n < 49; n++) {
    await advance(h, 1);
    expect(h.result.current.records.length).toBeLessThanOrEqual(100);
    expect(h.result.current.pinned).toHaveLength(7);
  }
  expect(h.result.current.normal).toHaveLength(93);
  expect(h.result.current.normal[0].conversationId).toBe('c907');
  expect(h.result.current.records.some(r => r.conversationId === 'c0')).toBe(false);
  expect(h.result.current.selectedId).toBe('c0');
  await act(async () => {
    await h.result.current.refreshFirst();
  });
  expect(api.queryConversationWindow).toHaveBeenLastCalledWith({scope, normalSize: 20}, expect.anything());
  expect(h.result.current.normal[0].conversationId).toBe('c0');
  expect(h.result.current.headEvicted).toBe(false);
  expect(h.result.current.selected?.conversationId).toBe('c0');
});
it('coalesces continuation and preserves the original cursor on failure until explicit retry', async () => {
  const h = await loaded(), d = deferred<ReturnType<typeof reply<api.ConversationCursorPage>>>();
  vi.mocked(api.listConversationsCursor).mockReturnValueOnce(d.promise);
  let a!: Promise<boolean>, b!: Promise<boolean>;
  act(() => {
    a = h.result.current.loadMore();
    b = h.result.current.loadMore();
  });
  expect(api.listConversationsCursor).toHaveBeenCalledTimes(1);
  await act(async () => {
    d.reject(new Error('offline'));
    await Promise.all([a, b]);
    await h.result.current.loadMore();
  });
  expect(h.result.current.normal).toHaveLength(20);
  expect(h.result.current.cursor).toBe('cursor-20');
  expect(api.listConversationsCursor).toHaveBeenCalledTimes(1);
  await act(async () => {
    await h.result.current.loadMore(true);
  });
  expect(vi.mocked(api.listConversationsCursor).mock.calls.map(([r]) => r.cursor)).toEqual(['cursor-20', 'cursor-20']);
});
it('discards a late continuation after a fresh generation even when abort is ignored', async () => {
  const h = await loaded(), d = deferred<ReturnType<typeof reply<api.ConversationCursorPage>>>();
  vi.mocked(api.listConversationsCursor).mockReturnValueOnce(d.promise);
  let loading!: Promise<boolean>;
  act(() => {
    loading = h.result.current.loadMore();
  });
  vi.mocked(api.queryConversationWindow).mockResolvedValue(reply(windowData(300)));
  await act(async () => {
    await h.result.current.refreshFirst();
  });
  await act(async () => {
    d.resolve(reply(page([item(20)], 'late')));
    await loading;
  });
  expect(h.result.current.normal[0].conversationId).toBe('c300');
  expect(h.result.current.moreError).toBeNull();
});
it('retains the old window and current chat if the database first-page refresh fails', async () => {
  const h = await loaded();
  await act(async () => {
    await h.result.current.select('c0');
  });
  await advance(h, 1);
  const rows = h.result.current.normal;
  vi.mocked(api.queryConversationWindow).mockRejectedValueOnce(new Error('offline'));
  await act(async () => {
    await h.result.current.refreshFirst();
  });
  expect(h.result.current.normal).toBe(rows);
  expect(h.result.current.selectedId).toBe('c0');
  expect(h.result.current.listError).not.toBeNull();
});
it('preserves typing while a fresh target metadata read is in flight', async () => {
  const h = await loaded(), d = deferred<ReturnType<typeof reply<api.ConversationListItem>>>();
  vi.mocked(api.getConversationMetadata).mockReturnValueOnce(d.promise);
  await act(async () => {
    await h.result.current.beginEdit('c0');
  });
  act(() => h.result.current.changeDraft('我的输入'));
  await act(async () => {
    d.resolve(reply({...item(0), metadataVersion: 9, title: '他处名称'}));
    await d.promise;
  });
  expect(h.result.current.editor?.draft).toBe('我的输入');
  await act(async () => {
    await h.result.current.finishEdit(true);
  });
  expect(api.changeConversationMetadata).toHaveBeenCalledWith(expect.objectContaining({
    title: '我的输入',
    expectedMetadataVersion: 9
  }), expect.any(String), expect.anything());
});
it('shares Enter/blur writes and edits another title without selecting it', async () => {
  const h = await loaded();
  await edit(h, 'c0', ' 改名 ');
  const d = deferred<ReturnType<typeof reply<api.ConversationMetadataReceipt>>>();
  vi.mocked(api.changeConversationMetadata).mockReturnValueOnce(d.promise);
  let a!: Promise<boolean>, b!: Promise<boolean>, next!: Promise<void>;
  act(() => {
    a = h.result.current.finishEdit(true);
    b = h.result.current.finishEdit();
    next = h.result.current.beginEdit('c1');
  });
  await waitFor(() => expect(api.changeConversationMetadata).toHaveBeenCalledTimes(1));
  const [r, key] = vi.mocked(api.changeConversationMetadata).mock.calls[0];
  expect(r).toMatchObject({conversationId: 'c0', title: '改名'});
  await act(async () => {
    d.resolve(reply(receipt(r, key)));
    await Promise.all([a, b, next]);
  });
  expect(api.getConversation).not.toHaveBeenCalled();
  expect(h.result.current.editor?.target.conversationId).toBe('c1');
  expect(api.queryConversationWindow).toHaveBeenCalledTimes(1);
  expect(h.result.current.normal[0].title).toBe('改名');
});
it('keeps invalid drafts and prevents refresh/switch; Esc followed by blur never saves', async () => {
  const h = await loaded();
  await edit(h, 'c0', '   ');
  await act(async () => {
    expect(await h.result.current.refreshFirst()).toBe(false);
    await h.result.current.select('c1');
  });
  expect(h.result.current.editor?.error).toContain('不能为空');
  expect(api.getConversation).not.toHaveBeenCalled();
  act(() => {
    h.result.current.cancelEdit();
  });
  await act(async () => {
    await h.result.current.finishEdit();
  });
  expect(api.changeConversationMetadata).not.toHaveBeenCalled();
  expect(h.result.current.editor).toBeNull();
});
it('keeps a conflict draft and retries with fresh metadata and a new key only after an explicit save', async () => {
  const h = await loaded();
  await edit(h);
  vi.mocked(api.changeConversationMetadata).mockRejectedValueOnce(new api.AiApiError(409, {code: '205030'}));
  vi.mocked(api.getConversationMetadata).mockResolvedValue(reply({...item(0), metadataVersion: 4, title: '并发名称'}));
  await act(async () => {
    await h.result.current.finishEdit(true);
    await h.result.current.beginEdit('c1');
  });
  expect(h.result.current.editor?.draft).toBe('我的名称');
  expect(h.result.current.editor?.target.title).toBe('并发名称');
  expect(api.changeConversationMetadata).toHaveBeenCalledTimes(1);
  expect(api.queryConversationWindow).toHaveBeenCalledTimes(1);
  await act(async () => {
    await h.result.current.finishEdit(true);
  });
  const calls = vi.mocked(api.changeConversationMetadata).mock.calls;
  expect(calls[1][0].expectedMetadataVersion).toBe(4);
  expect(calls[1][1]).not.toBe(calls[0][1]);
});
it('restores an uncertain rename and explicitly replays the original request only after checking its receipt', async () => {
  const h = await loaded();
  await edit(h, 'c0', '固定草稿');
  vi.mocked(api.changeConversationMetadata).mockRejectedValueOnce(new Error('timeout'));
  await act(async () => {
    await h.result.current.finishEdit(true);
  });
  const original = vi.mocked(api.changeConversationMetadata).mock.calls[0].slice(0, 2);
  expect(readOperation(context, 'conversation-metadata', '').value?.key).toBe(original[1]);
  await act(async () => {
    await h.result.current.select('c1');
  });
  expect(api.getConversation).not.toHaveBeenCalled();
  h.unmount();
  const next = await loaded();
  expect(api.changeConversationMetadata).toHaveBeenCalledTimes(1);
  expect(next.result.current.editor?.draft).toBe('固定草稿');
  vi.mocked(api.getMetadataCommandReceipt).mockRejectedValueOnce(new api.AiApiError(404, {code: '205028'}));
  await act(async () => {
    await next.result.current.confirmCommand();
  });
  expect(vi.mocked(api.changeConversationMetadata).mock.calls[1].slice(0, 2)).toEqual(original);
  expect(next.result.current.editor).toBeNull();
  expect(next.result.current.normal[0].title).toBe('固定草稿');
  expect(api.queryConversationWindow).toHaveBeenCalledTimes(2);
});
it('confirms an original rejection without creating another write', async () => {
  saveOperation(context, 'conversation-metadata', '', {
    key: 'old',
    request: {scope, kind: 'rename', conversationId: 'c0', expectedMetadataVersion: 0, title: '草稿'}
  });
  const h = await loaded();
  vi.mocked(api.getMetadataCommandReceipt).mockResolvedValue(reply({
    commandId: 'old',
    outcome: 'VERSION_CONFLICT',
    receipt: null
  }));
  await act(async () => {
    await h.result.current.confirmCommand();
  });
  expect(api.changeConversationMetadata).not.toHaveBeenCalled();
  expect(h.result.current.editor?.draft).toBe('草稿');
  expect(h.result.current.editor?.status).toBe('error');
  expect(h.result.current.pendingCommand).toBeNull();
});
it('pauses before an edited row could be recycled and resumes after editing is resolved', async () => {
  const h = await loaded();
  await advance(h, 4);
  await edit(h);
  const count = vi.mocked(api.listConversationsCursor).mock.calls.length;
  await act(async () => {
    await h.result.current.loadMore();
  });
  expect(h.result.current.paused).toBe(true);
  expect(api.listConversationsCursor).toHaveBeenCalledTimes(count);
  expect(h.result.current.records).toHaveLength(100);
  act(() => {
    h.result.current.cancelEdit();
  });
  await advance(h, 1);
  expect(h.result.current.normal[0].conversationId).toBe('c20');
});
it('checks the edited victim again if editing begins while continuation is already in flight', async () => {
  const h = await loaded();
  await advance(h, 4);
  const d = deferred<ReturnType<typeof reply<api.ConversationCursorPage>>>();
  vi.mocked(api.listConversationsCursor).mockReturnValueOnce(d.promise);
  let loading!: Promise<boolean>;
  act(() => {
    loading = h.result.current.loadMore();
  });
  await edit(h, 'c0', '不能丢失');
  await act(async () => {
    d.resolve(reply(page(Array.from({length: 20}, (_, n) => item(100 + n)), 'cursor-120')));
    await loading;
  });
  expect(h.result.current.normal[0].conversationId).toBe('c0');
  expect(h.result.current.cursor).toBe('cursor-100');
  expect(h.result.current.editor?.draft).toBe('不能丢失');
  expect(h.result.current.records).toHaveLength(100);
});
it('refreshes once after pinning and seeks the next visible normal anchor within the 100-row capacity', async () => {
  const h = await loaded();
  await advance(h, 5);
  act(() => h.result.current.captureAnchor(exclude => ({id: exclude === 'c20' ? 'c21' : 'c20', offset: -8})));
  vi.mocked(api.queryConversationWindow).mockResolvedValue(reply({
    ...windowData(21, 1, 99),
    anchorConversationId: 'c21'
  }));
  await act(async () => {
    await Promise.all([h.result.current.setPinned(item(20)), h.result.current.setPinned(item(20))]);
  });
  expect(api.changeConversationMetadata).toHaveBeenCalledTimes(1);
  expect(api.queryConversationWindow).toHaveBeenLastCalledWith({
    scope,
    normalSize: 100,
    anchorConversationId: 'c21'
  }, expect.anything());
  expect(h.result.current.records).toHaveLength(100);
  expect(h.result.current.placement.anchor).toEqual({id: 'c21', offset: -8});
  expect(h.result.current.headEvicted).toBe(true);
});
it('does not let an older refresh or a full execution snapshot overwrite a newer title acknowledgement', async () => {
  const h = await loaded();
  await act(async () => {
    await h.result.current.select('c0');
  });
  const d = deferred<ReturnType<typeof reply<api.ConversationWindow>>>();
  vi.mocked(api.queryConversationWindow).mockReturnValueOnce(d.promise);
  let refresh!: Promise<boolean>;
  act(() => {
    refresh = h.result.current.refreshFirst();
  });
  await waitFor(() => expect(api.queryConversationWindow).toHaveBeenCalledTimes(2));
  act(() => h.result.current.onTitleUpdated('c0', {...item(0), title: '新名称', metadataVersion: 5}));
  await act(async () => {
    d.resolve(reply(windowData()));
    await refresh;
  });
  expect(h.result.current.normal[0].title).toBe('新名称');
  act(() => h.result.current.updateConversation({...detail('c0'), version: 100, title: '迟到详情'}));
  expect(h.result.current.selected?.title).toBe('新名称');
});
it('does not confuse the retention limit or malformed empty continuation with the true end', async () => {
  const h = await loaded();
  await advance(h, 4);
  expect(h.result.current.hasMore).toBe(true);
  vi.mocked(api.listConversationsCursor).mockResolvedValueOnce(reply(page([], 'cursor-120')));
  await advance(h, 1);
  expect(h.result.current.moreError).not.toBeNull();
  expect(h.result.current.hasMore).toBe(true);
  expect(h.result.current.cursor).toBe('cursor-100');
  vi.mocked(api.listConversationsCursor).mockResolvedValueOnce(reply(page([item(100)], null)));
  await act(async () => {
    await h.result.current.loadMore(true);
  });
  expect(h.result.current.hasMore).toBe(false);
  expect(h.result.current.records).toHaveLength(100);
});
it('creates a date-named conversation without a form, restores its timeout key, and refreshes only on success', async () => {
  vi.useFakeTimers({toFake: ['Date']});
  vi.setSystemTime(new Date('2026-10-14T15:59:59Z'));
  const h = await loaded();
  vi.mocked(api.createDefaultConversation).mockRejectedValueOnce(new Error('timeout'));
  await act(async () => {
    await Promise.all([h.result.current.create(), h.result.current.create()]);
  });
  expect(api.createDefaultConversation).toHaveBeenCalledTimes(1);
  const original = vi.mocked(api.createDefaultConversation).mock.calls[0].slice(0, 2);
  expect(original[0]).toEqual({scope, localDate: '2026-10-14'});
  expect(readOperation<api.CreateDefaultConversationRequest>(context, 'create-default', '').value?.request)
    .toEqual({scope, localDate: '2026-10-14'});
  h.unmount();
  vi.setSystemTime(new Date('2026-10-14T16:00:01Z'));
  const next = await loaded();
  expect(api.createDefaultConversation).toHaveBeenCalledTimes(1);
  await act(async () => {
    await next.result.current.create();
  });
  expect(vi.mocked(api.createDefaultConversation).mock.calls[1].slice(0, 2)).toEqual(original);
  expect(api.createConversation).not.toHaveBeenCalled();
  expect(api.queryConversationWindow).toHaveBeenCalledTimes(3);
  expect(next.result.current.selectedId).toBe('new');
  await act(async () => {
    await next.result.current.create();
  });
  const fresh = vi.mocked(api.createDefaultConversation).mock.calls[2];
  expect(fresh[0]).toEqual({scope, localDate: '2026-10-15'});
  expect(fresh[1]).not.toBe(original[1]);
});

it('captures the click date before waiting for an existing title save across midnight', async () => {
  vi.useFakeTimers({toFake: ['Date']});
  vi.setSystemTime(new Date('2026-10-14T15:59:59Z'));
  const h = await loaded();
  await edit(h);
  const save = deferred<ReturnType<typeof reply<api.ConversationMetadataReceipt>>>();
  vi.mocked(api.changeConversationMetadata).mockReturnValueOnce(save.promise);
  let creation!: Promise<boolean>;
  act(() => {
    creation = h.result.current.create();
  });
  await waitFor(() => expect(api.changeConversationMetadata).toHaveBeenCalledTimes(1));
  expect(api.createDefaultConversation).not.toHaveBeenCalled();
  vi.setSystemTime(new Date('2026-10-14T16:00:01Z'));
  await act(async () => {
    const [request, key] = vi.mocked(api.changeConversationMetadata).mock.calls[0];
    save.resolve(reply(receipt(request, key)));
    await creation;
  });
  expect(vi.mocked(api.createDefaultConversation).mock.calls[0][0]).toEqual({scope, localDate: '2026-10-14'});
});

it('replays legacy pending default creation without adding a date or changing its key', async () => {
  const original = {key: 'legacy-default', request: {scope}};
  saveOperation(context, 'create-default', '', original);
  const h = await loaded();
  await act(async () => {
    await h.result.current.create();
  });
  expect(vi.mocked(api.createDefaultConversation).mock.calls[0].slice(0, 2))
    .toEqual([original.request, original.key]);
});
it('starts a new generation after creation even if an older initial read is still pending', async () => {
  const d = deferred<ReturnType<typeof reply<api.ConversationWindow>>>();
  vi.mocked(api.queryConversationWindow).mockReturnValueOnce(d.promise);
  const h = setup();
  await act(async () => {
    await h.result.current.create();
  });
  expect(api.queryConversationWindow).toHaveBeenCalledTimes(2);
  await act(async () => {
    d.resolve(reply(windowData(300)));
    await d.promise;
  });
  expect(h.result.current.normal[0].conversationId).toBe('c0');
});
it('discards responses from unmounted scopes and blocks new writes when recovery is corrupt', async () => {
  const d = deferred<ReturnType<typeof reply<api.ConversationWindow>>>();
  vi.mocked(api.queryConversationWindow).mockReturnValueOnce(d.promise);
  const first = setup();
  first.unmount();
  const other = {userId: 'other', scope: {tenantId: 'other', workspaceId: 'space'}};
  const next = renderHook(() => useConversationDirectory(other.scope, other));
  await waitFor(() => expect(next.result.current.records).toHaveLength(20));
  await act(async () => {
    d.resolve(reply(windowData(300)));
    await d.promise;
  });
  expect(next.result.current.normal[0].conversationId).toBe('c0');
  next.unmount();
  sessionStorage.setItem(storageKey(context, 'conversation-metadata'), 'invalid');
  const broken = await loaded();
  await act(async () => {
    await broken.result.current.create();
    await broken.result.current.beginEdit('c0');
    await broken.result.current.setPinned(item(0));
  });
  expect(broken.result.current.storageWarning).toContain('恢复记录');
  expect(api.createDefaultConversation).not.toHaveBeenCalled();
  expect(api.changeConversationMetadata).not.toHaveBeenCalled();
});

it('leaves an unchanged long Unicode auto-title untouched when editing is opened and blurred', async () => {
  const h = await loaded();
  const title = '👨🏽‍👩🏽‍👧🏽‍👦🏽'.repeat(20);
  vi.mocked(api.getConversationMetadata).mockResolvedValueOnce(reply({...item(0), title, metadataVersion: 2}));
  await act(async () => {
    await h.result.current.beginEdit('c0');
  });
  await waitFor(() => expect(h.result.current.editor?.ready).toBe(true));
  expect(h.result.current.editor?.draft).toBe(title);
  await act(async () => {
    expect(await h.result.current.finishEdit()).toBe(true);
  });
  expect(h.result.current.editor).toBeNull();
  expect(api.changeConversationMetadata).not.toHaveBeenCalled();
});

it('rejects a mismatched target read without retargeting the draft or issuing a write', async () => {
  const h = await loaded();
  vi.mocked(api.getConversationMetadata).mockResolvedValue(reply(item(99)));
  await act(async () => {
    await h.result.current.beginEdit('c0');
  });
  await waitFor(() => expect(h.result.current.editor?.status).toBe('error'));
  expect(h.result.current.editor?.target.conversationId).toBe('c0');
  expect(h.result.current.editor?.error).toContain('无法读取最新名称');
  await act(async () => {
    expect(await h.result.current.finishEdit(true)).toBe(false);
  });
  expect(api.changeConversationMetadata).not.toHaveBeenCalled();
});
