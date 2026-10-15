import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/arte-ai';
import {saveOperation, storageKey, writeValue} from './storage';
import {type ConversationDirectory, useConversationDirectory} from './useConversationDirectory';
import ConversationDirectorySidebar from './ConversationDirectorySidebar';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/components/DynamicIcon', () => ({
  MyDynamicIcon: ({iconName}: { iconName: string }) => <i data-icon={iconName}/>
}));
vi.mock('@/services/arte-ai', async original => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  queryConversationWindow: vi.fn(), listConversationsCursor: vi.fn(), getConversationMetadata: vi.fn(),
  changeConversationMetadata: vi.fn(), getConversation: vi.fn(), getMetadataCommandReceipt: vi.fn(),
}));
const scope = {tenantId: 't', workspaceId: 'w'}, context = {userId: 'ui', scope};
const item = (n: number): api.ConversationListItem => ({
  conversationId: `c${n}`,
  title: `会话${n}`,
  pinned: false,
  metadataVersion: 0,
  titleManuallyEdited: false,
  autoTitleResolved: false,
  previewText: `问题${n}`,
  userMessageCount: n,
  lastMessageGeneratedAt: n ? '2026-10-14T12:00:00Z' : null,
  sortActivityAt: '2026-10-14T12:00:00Z',
  createdAt: '2026-10-14T12:00:00Z',
  statisticsReady: true
});
const reply = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'ok', data}});
const page = (start: number, count = 20): api.ConversationCursorPage => ({
  records: Array.from({length: count}, (_, n) => item(start + n)),
  nextCursor: `p${start + count}`,
  hasMore: true,
  total: 1000
});
let current: ConversationDirectory;

function Harness({writable = true}: { writable?: boolean }) {
  const directory = useConversationDirectory(scope, context);
  current = directory;
  return <>
    <button onClick={() => void directory.refreshFirst()}>刷新会话列表</button>
    <button onClick={() => void directory.loadMore(true)}>续页</button>
    <span data-testid="selected">{directory.selectedId}</span><textarea aria-label="聊天草稿" defaultValue="保持草稿"/>
    <ConversationDirectorySidebar directory={directory} writable={writable} readBlocked={false}
                               onOpen={id => {
                                 void directory.select(id);
                               }} onCreate={() => {
      void directory.create();
    }}/></>;
}

beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  localStorage.clear();
  vi.mocked(api.queryConversationWindow).mockResolvedValue(reply({
    normal: page(0),
    pinned: {...page(0, 0), hasMore: false, nextCursor: null},
    anchorFallback: false,
    anchorConversationId: null
  }));
  vi.mocked(api.listConversationsCursor).mockImplementation(async r => reply(page(Number(r.cursor?.slice(1)))));
  vi.mocked(api.getConversationMetadata).mockImplementation(async r => reply(item(Number(r.conversationId.slice(1)))));
  vi.mocked(api.getConversation).mockImplementation(async r => reply({
    conversationId: r.conversationId,
    title: '详情',
    version: 2,
    resources: [],
    chatProfile: null,
    state: 'ACTIVE',
    createdAt: '2026-10-14T12:00:00Z',
    updatedAt: '2026-10-14T12:00:00Z'
  }));
  vi.mocked(api.changeConversationMetadata).mockImplementation(async (r, key) => reply({
    conversationId: r.conversationId, commandId: key,
    conversation: {
      ...item(Number(r.conversationId.slice(1))),
      metadataVersion: 1,
      title: r.kind === 'rename' ? r.title : '会话',
      pinned: r.kind === 'pin' && r.pinned
    },
    pinnedCount: 0, changed: true
  }));
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

async function load() {
  render(<Harness/>);
  await screen.findByRole('button', {name: '打开会话：会话0'});
}

async function edit(n = 0) {
  fireEvent.click(screen.getByRole('button', {name: `修改会话名称：会话${n}`}));
  const input = await screen.findByLabelText(`会话名称：c${n}`);
  await waitFor(() => expect(current.editor?.ready).toBe(true));
  return input;
}

it('respects composition, Esc followed by blur, and Enter/blur request coalescing', async () => {
  await load();
  let input = await edit();
  fireEvent.compositionStart(input);
  fireEvent.change(input, {target: {value: '中文组词'}});
  fireEvent.keyDown(input, {key: 'Enter', keyCode: 229, isComposing: true});
  expect(api.changeConversationMetadata).not.toHaveBeenCalled();
  fireEvent.compositionEnd(input);
  fireEvent.keyDown(input, {key: 'Escape'});
  fireEvent.blur(input);
  expect(api.changeConversationMetadata).not.toHaveBeenCalled();
  input = await edit();
  fireEvent.change(input, {target: {value: '新名称'}});
  fireEvent.keyDown(input, {key: 'Enter'});
  fireEvent.blur(input);
  await screen.findByRole('button', {name: '修改会话名称：新名称'});
  expect(api.changeConversationMetadata).toHaveBeenCalledTimes(1);
  expect(api.queryConversationWindow).toHaveBeenCalledTimes(1);
  expect(api.getConversation).not.toHaveBeenCalled();
});
it('edits a different title without opening it and opens only non-operation regions', async () => {
  await load();
  const input = await edit();
  fireEvent.change(input, {target: {value: '已保存名称'}});
  fireEvent.blur(input);
  fireEvent.click(screen.getByRole('button', {name: '修改会话名称：会话1'}));
  await screen.findByLabelText('会话名称：c1');
  expect(api.getConversation).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText('问题2'));
  await waitFor(() => expect(screen.getByTestId('selected')).toHaveTextContent('c2'));
  expect(api.queryConversationWindow).toHaveBeenCalledTimes(1);
});
it('keeps focus/draft and blocks other editors or selection after an uncertain save', async () => {
  await load();
  const input = await edit();
  fireEvent.change(input, {target: {value: '不能丢失'}});
  vi.mocked(api.changeConversationMetadata).mockRejectedValueOnce(new Error('timeout'));
  fireEvent.keyDown(input, {key: 'Enter'});
  await screen.findByText('名称保存结果待确认');
  fireEvent.click(screen.getByRole('button', {name: '修改会话名称：会话1'}));
  fireEvent.click(screen.getByText('问题2'));
  expect(screen.getByLabelText('会话名称：c0')).toHaveValue('不能丢失');
  expect(screen.queryByLabelText('会话名称：c1')).not.toBeInTheDocument();
  expect(api.getConversation).not.toHaveBeenCalled();
  expect(api.changeConversationMetadata).toHaveBeenCalledTimes(1);
});
it('shows a style-only search and server date/message counts without filtering or querying', async () => {
  await load();
  fireEvent.change(screen.getByLabelText('搜索会话'), {target: {value: '不存在的名称'}});
  expect(screen.getAllByRole('listitem')).toHaveLength(20);
  expect(screen.getByText('0轮')).toBeInTheDocument();
  expect(screen.getAllByText('26-10')).toHaveLength(19);
  expect(screen.getByText('—')).toBeInTheDocument();
  expect(api.queryConversationWindow).toHaveBeenCalledTimes(1);
  expect(api.listConversationsCursor).not.toHaveBeenCalled();
});
it('compensates the exact removed height and only a user scroll to the recycled top reloads database page one', async () => {
  await load();
  const root = screen.getByLabelText('会话滚动列表');
  Object.defineProperty(root, 'clientHeight', {configurable: true, get: () => 180});
  Object.defineProperty(root, 'scrollHeight', {
    configurable: true,
    get: () => root.querySelectorAll('[data-conversation-id]').length * 64
  });
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const nodes = Array.from(root.querySelectorAll('[data-normal-id]'));
    const index = nodes.indexOf(this);
    const top = this === root ? 100 : index >= 0 ? 100 + index * 64 - root.scrollTop : 0;
    return {
      top,
      bottom: top + (this === root ? 180 : 64),
      height: this === root ? 180 : 64,
      left: 0,
      right: 320,
      width: 320,
      x: 0,
      y: top,
      toJSON: () => ({})
    };
  });
  for (let n = 0; n < 4; n++) await act(async () => {
    await current.loadMore(true);
  });
  await act(async () => {
    await current.select('c0');
  });
  root.scrollTop = 88 * 64 + 12;
  fireEvent.scroll(root);
  const before = root.scrollTop;
  await act(async () => {
    await current.loadMore(true);
  });
  expect(screen.getAllByRole('listitem')).toHaveLength(100);
  expect(root.scrollTop).toBe(before - 20 * 64);
  expect(screen.getByTestId('selected')).toHaveTextContent('c0');
  expect(screen.getByLabelText('聊天草稿')).toHaveValue('保持草稿');
  expect(api.queryConversationWindow).toHaveBeenCalledTimes(1);
  fireEvent.wheel(root, {deltaY: -100});
  root.scrollTop = 0;
  fireEvent.scroll(root);
  await waitFor(() => expect(api.queryConversationWindow).toHaveBeenCalledTimes(2));
  expect(api.queryConversationWindow).toHaveBeenLastCalledWith({scope, normalSize: 20}, expect.anything());
  expect(root.scrollTop).toBe(0);
});
it('keeps an out-of-window recovered operation visible without adding another real conversation row', async () => {
  saveOperation(context, 'conversation-metadata', '', {
    key: 'pending',
    request: {scope, kind: 'rename', conversationId: 'c200', expectedMetadataVersion: 1, title: '保留的草稿'}
  });
  await load();
  const panel = screen.getByLabelText('恢复名称编辑');
  expect(within(panel).getByLabelText('会话名称：c200')).toHaveValue('保留的草稿');
  expect(screen.getAllByRole('listitem')).toHaveLength(20);
  expect(api.changeConversationMetadata).not.toHaveBeenCalled();
  expect(screen.getByRole('button', {name: '确认原操作'})).toBeEnabled();
});
it('disables title mutation in a read-only workspace but keeps conversation reading available', async () => {
  render(<Harness writable={false}/>);
  expect(await screen.findByRole('button', {name: '修改会话名称：会话0'})).toBeDisabled();
  fireEvent.click(screen.getByRole('button', {name: '打开会话：会话0'}));
  await waitFor(() => expect(api.getConversation).toHaveBeenCalledTimes(1));
  expect(api.getConversationMetadata).not.toHaveBeenCalled();
  expect(api.changeConversationMetadata).not.toHaveBeenCalled();
});
it('uses blur submission for a recovered draft outside the current window', async () => {
  writeValue(storageKey(context, 'title-editor'), {
    target: item(200), draft: '恢复后失焦保存', touched: true,
    ready: true, status: 'editing', error: null
  });
  await load();
  const input = screen.getByLabelText('会话名称：c200');
  fireEvent.blur(input);
  await waitFor(() => expect(screen.queryByLabelText('恢复名称编辑')).not.toBeInTheDocument());
  expect(api.changeConversationMetadata).toHaveBeenCalledTimes(1);
  expect(api.changeConversationMetadata).toHaveBeenCalledWith(expect.objectContaining({
    conversationId: 'c200',
    kind: 'rename', title: '恢复后失焦保存'
  }), expect.any(String), expect.anything());
  expect(screen.getAllByRole('listitem')).toHaveLength(20);
  expect(api.queryConversationWindow).toHaveBeenCalledTimes(1);
});


it('does not page a hidden Splitter region when collapse resets its scroll position', async () => {
  await load();
  const root = screen.getByLabelText('会话滚动列表');
  Object.defineProperty(root, 'clientHeight', {configurable: true, value: 180});
  Object.defineProperty(root, 'scrollHeight', {configurable: true, value: 180});
  root.hidden = true;
  fireEvent.scroll(root);
  expect(api.listConversationsCursor).not.toHaveBeenCalled();
  root.hidden = false;
  Object.defineProperty(root, 'clientHeight', {configurable: true, value: 0});
  fireEvent.scroll(root);
  expect(api.listConversationsCursor).not.toHaveBeenCalled();
});
