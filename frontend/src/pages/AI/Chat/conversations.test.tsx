import {act, cleanup, fireEvent, render, renderHook, screen, waitFor, within,} from '@testing-library/react';
import React from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import messages from '@/locales/zh-CN/aiChat';
import {
  AiApiError,
  type ConversationResponse,
  createConversation,
  getConversation,
  listConversations,
} from '@/services/arte-ai';
import ConversationWorkspace from './ConversationWorkspace';
import {useConversations} from './useConversations';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/arte-ai')>()),
  createConversation: vi.fn(),
  getConversation: vi.fn(),
  listConversations: vi.fn(),
}));
const scope = {tenantId: 'tenant-1', workspaceId: 'workspace-1'};
const conversation = (id: string, version = 1): ConversationResponse => ({
  conversationId: id,
  title: `会话 ${id}`,
  version,
  chatProfile: null,
  resources: [],
  state: 'ACTIVE',
  createdAt: '2026-10-07T01:00:00Z',
  updatedAt: '2026-10-07T01:00:00Z',
});
const detail = (data: ConversationResponse) => ({
  httpStatus: 200,
  body: {success: true as const, code: '200', desc: 'OK', data},
});
const page = (
  records: ConversationResponse[] = [],
  current = 1,
  total = records.length,
) => ({
  httpStatus: 200,
  body: {
    success: true as const,
    code: '200',
    desc: 'OK',
    records,
    current,
    size: 20,
    total,
  },
});
const t = (key: string) =>
  messages[`app.aiChat.${key}` as keyof typeof messages];

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return {promise, resolve};
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(listConversations).mockResolvedValue(page());
  vi.mocked(getConversation).mockImplementation(async ({conversationId}) =>
    detail(conversation(conversationId, 3)),
  );
  vi.mocked(createConversation).mockResolvedValue(detail(conversation('new')));
});
afterEach(cleanup);

describe('会话请求与并发处理', () => {
  it('只在已应用 scope 下加载，分页使用真实后端页码', async () => {
    const absent = renderHook(() => useConversations(null));
    expect(listConversations).not.toHaveBeenCalled();
    absent.unmount();
    const {result} = renderHook(() => useConversations(scope));
    await waitFor(() => expect(result.current.listLoading).toBe(false));
    vi.mocked(listConversations).mockResolvedValueOnce(
      page([conversation('second')], 2, 25),
    );
    await act(() => result.current.loadPage(2));
    expect(listConversations).toHaveBeenLastCalledWith(
      {scope, page: {current: 2, size: 20}},
      expect.anything(),
    );
    expect(result.current.records[0].conversationId).toBe('second');
    expect(result.current.page).toBe(2);
    expect(result.current.total).toBe(25);
  });

  it('快速刷新时忽略旧列表响应，即使传输层没有遵守 abort', async () => {
    const old = deferred<ReturnType<typeof page>>();
    vi.mocked(listConversations).mockReturnValueOnce(old.promise);
    const {result} = renderHook(() => useConversations(scope));
    const signal = vi.mocked(listConversations).mock.calls[0][1]?.signal;
    vi.mocked(listConversations).mockResolvedValueOnce(
      page([conversation('latest')]),
    );
    await act(() => result.current.loadPage(1));
    await act(async () => old.resolve(page([conversation('old')])));
    expect(signal?.aborted).toBe(true);
    expect(result.current.records[0].conversationId).toBe('latest');
  });

  it('连续选择会话时保留最后一次详情和最新版本', async () => {
    const old = deferred<ReturnType<typeof detail>>();
    vi.mocked(getConversation).mockReturnValueOnce(old.promise);
    const {result} = renderHook(() => useConversations(scope));
    act(() => {
      void result.current.select('a');
    });
    await act(() => result.current.select('b'));
    await act(async () => old.resolve(detail(conversation('a'))));
    expect(result.current.selectedId).toBe('b');
    expect(result.current.selected?.conversationId).toBe('b');
    expect(result.current.selected?.version).toBe(3);
  });

  it('阻止重复提交；失败重试复用键和标题，下一次创建使用新键', async () => {
    const pending = deferred<ReturnType<typeof detail>>();
    vi.mocked(createConversation).mockRejectedValueOnce(
      new TypeError('network'),
    );
    const {result} = renderHook(() => useConversations(scope));
    await act(() => result.current.create('first'));
    const firstCall = vi.mocked(createConversation).mock.calls[0];
    vi.mocked(createConversation).mockReturnValueOnce(pending.promise);
    let retry!: Promise<boolean>;
    act(() => {
      retry = result.current.create('changed');
    });
    await act(() => result.current.create('double click'));
    expect(createConversation).toHaveBeenCalledTimes(2);
    expect(vi.mocked(createConversation).mock.calls[1].slice(0, 2)).toEqual(
      firstCall.slice(0, 2),
    );
    await act(async () => {
      pending.resolve(detail(conversation('new')));
      await retry;
    });
    await waitFor(() =>
      expect(result.current.selected?.conversationId).toBe('new'),
    );
    expect(result.current.records).toEqual([]); // New item may be outside page 1.
    expect(result.current.pendingTitle).toBeNull();
    await act(() => result.current.create('second'));
    const secondCall = vi.mocked(createConversation).mock.calls[2];
    expect(secondCall[0].title).toBe('second');
    expect(secondCall[1]).not.toBe(firstCall[1]);
  });

  it('卸载时取消列表、详情与创建请求，迟到响应不触发后续请求', async () => {
    const pending = deferred<ReturnType<typeof detail>>();
    vi.mocked(createConversation).mockReturnValueOnce(pending.promise);
    const {result, unmount} = renderHook(() => useConversations(scope));
    act(() => {
      void result.current.select('a');
      void result.current.create('new');
    });
    const signals = [
      vi.mocked(listConversations).mock.calls[0][1]?.signal,
      vi.mocked(getConversation).mock.calls[0][1]?.signal,
      vi.mocked(createConversation).mock.calls[0][2]?.signal,
    ];
    unmount();
    expect(signals.every((signal) => signal?.aborted)).toBe(true);
    await act(async () => pending.resolve(detail(conversation('new'))));
    expect(listConversations).toHaveBeenCalledTimes(1);
    expect(getConversation).toHaveBeenCalledTimes(1);
  });
});

describe('会话界面', () => {
  it('点击列表项查询详情，展示版本，消息输入仍为下一步占位', async () => {
    vi.mocked(listConversations).mockResolvedValue(page([conversation('a')]));
    render(<ConversationWorkspace scope={scope} dirty={false} t={t}/>);
    fireEvent.click(await screen.findByRole('button', {name: '会话 a a'}));
    const details = await screen.findByLabelText('会话详情');
    expect(within(details).getByText('3')).toBeInTheDocument();
    expect(getConversation).toHaveBeenCalledWith(
      {scope, conversationId: 'a'},
      expect.anything(),
    );
    expect(screen.getByRole('button', {name: '会话 a a'})).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', {name: '发送消息'})).toBeDisabled();
  });

  it('校验标题，创建失败后关闭再打开仍保留同一次重试', async () => {
    vi.mocked(createConversation).mockRejectedValueOnce(
      new TypeError('timeout'),
    );
    render(<ConversationWorkspace scope={scope} dirty={false} t={t}/>);
    fireEvent.click(screen.getByRole('button', {name: '新建会话'}));
    fireEvent.click(await screen.findByRole('button', {name: /^创\s*建$/}));
    await screen.findByText('请填写此项，不能只包含空白');
    expect(createConversation).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('会话标题'), {
      target: {value: '  test title  '},
    });
    fireEvent.click(await screen.findByRole('button', {name: /^创\s*建$/}));
    await screen.findByText(t('error.network'));
    const original = vi.mocked(createConversation).mock.calls[0];
    expect(original[0]).toEqual({scope, title: 'test title'});
    fireEvent.click(screen.getByRole('button', {name: /^关\s*闭$/}));
    fireEvent.click(screen.getByRole('button', {name: '新建会话'}));
    expect(screen.getByLabelText('会话标题')).toHaveValue('test title');
    expect(screen.getByLabelText('会话标题')).toBeDisabled();
    fireEvent.click(screen.getByRole('button', {name: '重试创建'}));
    await screen.findByLabelText('会话详情');
    expect(vi.mocked(createConversation).mock.calls[1].slice(0, 2)).toEqual(
      original.slice(0, 2),
    );
  });

  it('权限错误与空列表分开显示，修复后可重试', async () => {
    vi.mocked(listConversations).mockRejectedValueOnce(
      new AiApiError(403, {code: '403', desc: '无访问授权'}),
    );
    render(<ConversationWorkspace scope={scope} dirty={false} t={t}/>);
    await screen.findByText(/无访问授权.*HTTP 403/);
    expect(screen.getByText(t('error.permission'))).toBeInTheDocument();
    expect(screen.queryByText(t('conversationsEmpty'))).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', {name: /^重\s*试$/}));
    await screen.findByText(t('conversationsEmpty'));
  });
});
