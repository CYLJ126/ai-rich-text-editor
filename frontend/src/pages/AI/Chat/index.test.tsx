import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {act, cleanup, fireEvent, render, screen, waitFor, within,} from '@testing-library/react';
import {App} from 'antd';
import React, {useSyncExternalStore} from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import type {ChatBootstrap, Conversation} from '@/types/ai-new/conversation';
import type {ChatTurnResult} from '@/types/ai-new/chat';
import ChatPage from './index';

const state = vi.hoisted(() => ({
  location: {pathname: '/AI/Chat', search: ''},
  listeners: new Set<() => void>(),
  userId: '7',
  access: true,
  request: vi.fn(),
}));
vi.mock('@umijs/max', () => ({
  request: state.request,
  useModel: () => ({initialState: {currentUser: {id: state.userId}}}),
  useAccess: () => ({canViewAiChat: state.access}),
  useLocation: () =>
    useSyncExternalStore(
      (callback) => {
        state.listeners.add(callback);
        return () => state.listeners.delete(callback);
      },
      () => state.location,
    ),
  history: {
    replace: (location: { pathname: string; search: string }) => {
      state.location = location;
      for (const listener of state.listeners) listener();
    },
  },
}));
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({
                    children,
                    title,
                  }: {
    children: React.ReactNode;
    title: string;
  }) => (
    <main>
      <h1>{title}</h1>
      {children}
    </main>
  ),
}));

const scope = {tenantId: 'tenant-real', workspaceId: 'workspace-real'};
const model = {
  name: 'Verified model',
  providerId: 'compatible-chat',
  bindingRef: {
    definitionType: 'ai-binding',
    definitionId: 'default-model',
    version: 'v1',
  },
  destination: 'https://model.example/v1/chat/completions',
  purpose: 'model.generate',
  inputTypes: ['text'],
  streaming: false,
  contextMaxBytes: 8192,
  maxOutputTokens: 2048,
};
let bootstrap: ChatBootstrap;
let conversations: Conversation[];
let client: QueryClient;
let rejectWrites: number | null;
let revoked: boolean;
let turns: ChatTurnResult[];
const makeConversation = (id: string, title: string): Conversation => ({
  conversationId: id,
  title,
  scope: {...scope, principal: {principalId: '7', type: 'USER'}},
  modelBindingRef: model.bindingRef,
  status: 'ACTIVE',
  version: 1,
  resources: [],
  createdAt: '2026-10-02T00:00:00Z',
  updatedAt: '2026-10-02T00:00:00Z',
  deletedAt: null,
});

describe('minimal chat loop', () => {
  it('requires a fresh transfer confirmation, observes completion and sends the next message with the new version', async () => {
    selectFirst();
    view();
    let dialog = await sendDialog('第一个问题');
    expect(within(dialog).getByText(model.destination)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', {name: '确认并发送'})).toBeDisabled();
    fireEvent.click(within(dialog).getByRole('button', {name: /取.*消/}));
    expect(state.request.mock.calls.filter(([, options]) => options.method === 'POST')).toHaveLength(0);
    dialog = await sendDialog('第一个问题');
    confirmSend(dialog);
    await screen.findByText('生成中');
    expect(screen.queryByText('回答1')).not.toBeInTheDocument();
    expect(screen.getByRole('button', {name: '发送消息'})).toBeDisabled();
    const first = state.request.mock.calls.find(([, options]) => options.method === 'POST');
    expect(first?.[1].data).toEqual({
      ...scope,
      expectedVersion: 1,
      text: '第一个问题',
      externalTransferConfirmed: true
    });
    turns[0].execution = {
      executionId: 'execution-1',
      status: 'SUCCEEDED',
      result: {output: [{text: '回答1'}]},
      error: null
    };
    await screen.findByText('回答1', {}, {timeout: 4500});
    const reads = state.request.mock.calls.filter(([path]) => path.endsWith('/turns')).length;
    await new Promise((resolve) => setTimeout(resolve, 2200));
    expect(state.request.mock.calls.filter(([path]) => path.endsWith('/turns'))).toHaveLength(reads);
    dialog = await sendDialog('第二个问题');
    expect(within(dialog).getByRole('checkbox')).not.toBeChecked();
    confirmSend(dialog);
    await waitFor(() => expect(state.request.mock.calls.filter(([, options]) => options.method === 'POST')).toHaveLength(2));
    const second = state.request.mock.calls.filter(([, options]) => options.method === 'POST')[1];
    expect(second[1].data.expectedVersion).toBe(2);
    expect(second[1].headers['Idempotency-Key']).not.toBe(first?.[1].headers['Idempotency-Key']);
  });
  it('retains an ambiguous submission across refresh and explicitly replays its original body and key', async () => {
    selectFirst();
    const original = state.request.getMockImplementation();
    let failed = false;
    state.request.mockImplementation(async (path, options = {}) => {
      if (path.endsWith('/turns') && options.method === 'POST') {
        if (!failed) {
          failed = true;
          conversations[0].version = 2;
          const item = makeTurn(1, options.data.text, 'RUNNING', options.headers['Idempotency-Key']);
          item.turn.status = 'READY';
          item.execution = null;
          turns = [item];
          throw {response: {status: 503}};
        }
        turns[0].turn.status = 'ACCEPTED';
        turns[0].execution = {
          executionId: 'execution-1',
          status: 'SUCCEEDED',
          result: {output: [{text: '已恢复的回答'}]},
          error: null
        };
      }
      return original?.(path, options);
    });
    const rendered = view();
    confirmSend(await sendDialog('不要改变这个问题'));
    await screen.findByText('提交结果尚待核对');
    const posts = () => state.request.mock.calls.filter(([, options]) => options.method === 'POST');
    expect(posts()).toHaveLength(1);
    expect(sessionStorage.length).toBe(1);
    const first = posts()[0][1];
    rendered.unmount();
    client.clear();
    view();
    await screen.findByText('提交结果尚待核对');
    const replay = screen.getByRole('button', {name: '重放原提交'});
    await waitFor(() => expect(replay).toBeEnabled());
    expect(screen.getByRole('button', {name: '发送消息'})).toBeDisabled();
    fireEvent.click(replay);
    confirmSend(await screen.findByRole('dialog'));
    await screen.findByText('已恢复的回答');
    expect(posts()).toHaveLength(2);
    expect(posts()[1][1].data).toEqual(first.data);
    expect(posts()[1][1].headers['Idempotency-Key']).toBe(first.headers['Idempotency-Key']);
    expect(sessionStorage.length).toBe(0);
    expect(conversations[0].version).toBe(2);
  });
  it('loads history in chronological order using an exclusive sequence cursor', async () => {
    selectFirst();
    turns = Array.from({length: 22}, (_, index) => makeTurn(22 - index, `问题${22 - index}`));
    view();
    const log = await screen.findByRole('log');
    await within(log).findByText('问题22');
    expect(log.querySelectorAll('article')).toHaveLength(20);
    fireEvent.click(screen.getByRole('button', {name: '加载更早的消息'}));
    await within(log).findByText('问题1');
    expect(log.querySelectorAll('article')).toHaveLength(22);
    expect(log.querySelector('article')?.textContent).toContain('问题1');
    expect(state.request.mock.calls.some(([, options]) => options.params?.beforeSequence === 3)).toBe(true);
  });
  it('renders unknown outcomes without claiming a reply or automatically repeating the call', async () => {
    selectFirst();
    turns = [makeTurn(1, '结果未确认的问题', 'OUTCOME_UNKNOWN')];
    view();
    await screen.findByText(/模型调用结果未知/);
    expect(screen.queryByText('回答1')).not.toBeInTheDocument();
    expect(state.request.mock.calls.filter(([, options]) => options.method === 'POST')).toHaveLength(0);
  });
  it('does not dispatch when pending-request storage fails or text exceeds the byte limit', async () => {
    selectFirst();
    view();
    const dialog = await sendDialog('一个问题');
    const storage = vi.spyOn(sessionStorage, 'setItem').mockImplementation(() => {
      throw new Error('disabled');
    });
    confirmSend(dialog);
    await screen.findByText(/无法保存待核对请求/);
    expect(state.request.mock.calls.filter(([, options]) => options.method === 'POST')).toHaveLength(0);
    storage.mockRestore();
    fireEvent.change(screen.getByRole('textbox', {name: '消息内容'}), {target: {value: '中'.repeat(3000)}});
    expect(screen.getByRole('button', {name: '发送消息'})).toBeDisabled();
  });
  it('does not turn a reconciled replay dialog into a fresh paid submission', async () => {
    selectFirst();
    const key = crypto.randomUUID();
    sessionStorage.setItem(`arte-ai-new:pending:${JSON.stringify(['7', scope.tenantId, scope.workspaceId, 'first'])}`,
      JSON.stringify({key, body: {expectedVersion: 1, text: '原问题', externalTransferConfirmed: true}}));
    const item = makeTurn(1, '原问题', 'RUNNING', key);
    item.turn.status = 'READY';
    item.execution = null;
    turns = [item];
    view();
    const replay = await screen.findByRole('button', {name: '重放原提交'});
    await waitFor(() => expect(replay).toBeEnabled());
    fireEvent.click(replay);
    const dialog = await screen.findByRole('dialog');
    turns = [makeTurn(1, '原问题', 'SUCCEEDED', key)];
    await act(async () => {
      await client.invalidateQueries({queryKey: ['ai-new', '7', scope.tenantId, scope.workspaceId, 'history']});
    });
    await waitFor(() => expect(sessionStorage.length).toBe(0));
    await act(async () => {
      confirmSend(dialog);
    });
    expect(state.request.mock.calls.filter(([, options]) => options.method === 'POST')).toHaveLength(0);
  });
  it('discards a late submit response and its cached mutation after logout', async () => {
    selectFirst();
    const original = state.request.getMockImplementation();
    let resolveSubmission!: (value: ChatTurnResult) => void;
    let requestKey = '';
    state.request.mockImplementation((path, options = {}) => {
      if (path.endsWith('/turns') && options.method === 'POST') {
        requestKey = options.headers['Idempotency-Key'];
        return new Promise<ChatTurnResult>((resolve) => {
          resolveSubmission = resolve;
        });
      }
      return original?.(path, options);
    });
    const rendered = view();
    confirmSend(await sendDialog('发送后退出'));
    await waitFor(() => expect(resolveSubmission).toBeDefined());
    state.userId = '';
    rendered.rerender(<QueryClientProvider client={client}><App><ChatPage/></App></QueryClientProvider>);
    await screen.findByText('无权访问新聊天');
    const reads = state.request.mock.calls.length;
    await act(async () => {
      resolveSubmission(makeTurn(1, '发送后退出', 'SUCCEEDED', requestKey));
    });
    expect(state.request.mock.calls).toHaveLength(reads);
    expect(sessionStorage.length).toBe(0);
    expect(client.getQueryCache().findAll({queryKey: ['ai-new', '7']})).toHaveLength(0);
    await waitFor(() => expect(client.getMutationCache().getAll()).toHaveLength(0));
  });
  it('hides previous message text when permission for history is revoked', async () => {
    selectFirst();
    turns = [makeTurn(1, '私有问题')];
    view();
    await screen.findByText('回答1');
    const original = state.request.getMockImplementation();
    state.request.mockImplementation((path, options) => path.endsWith('/turns')
      ? Promise.reject({response: {status: 403}}) : original?.(path, options));
    await act(async () => {
      await client.invalidateQueries({queryKey: ['ai-new', '7', scope.tenantId, scope.workspaceId, 'history']});
    });
    await screen.findByText(/登录状态或当前权限不可用/);
    expect(screen.queryByText('私有问题')).not.toBeInTheDocument();
    expect(screen.queryByText('回答1')).not.toBeInTheDocument();
  });
});
beforeEach(() => {
  localStorage.setItem('umi_locale', 'zh-CN');
  state.location = {pathname: '/AI/Chat', search: ''};
  state.listeners.clear();
  state.userId = '7';
  state.access = true;
  bootstrap = {
    enabled: true,
    unavailableReason: null,
    workspaces: [{...scope, allowedActions: ['resource.ai_process']}],
    defaultModel: model,
  };
  conversations = [makeConversation('first', '原会话')];
  rejectWrites = null;
  revoked = false;
  turns = [];
  sessionStorage.clear();
  client = new QueryClient({
    defaultOptions: {
      queries: {retry: false, gcTime: 0},
      mutations: {retry: false},
    },
  });
  state.request.mockReset();
  state.request.mockImplementation(
    async (
      path: string,
      options: {
        method?: string;
        params?: Record<string, unknown>;
        data?: Record<string, unknown>;
        headers?: Record<string, string>;
      } = {},
    ) => {
      if (path.endsWith('/bootstrap')) return bootstrap;
      if (revoked)
        throw {
          response: {
            status: 403,
            data: {
              code: 'arte.common.unauthorized',
              failureStage: 'identity',
              retryable: false,
              sideEffectStatus: 'NONE',
              resultCertainty: 'CONFIRMED',
            },
          },
        };
      const method = options.method ?? 'GET';
      if (method !== 'GET' && rejectWrites)
        throw {
          response: {
            status: rejectWrites,
            data: {
              code:
                rejectWrites === 409
                  ? 'arte.common.version_conflict'
                  : 'arte.common.outcome_unknown',
              failureStage: 'store',
              retryable: false,
              sideEffectStatus: rejectWrites === 409 ? 'NONE' : 'UNKNOWN',
              resultCertainty: rejectWrites === 409 ? 'CONFIRMED' : 'UNKNOWN',
            },
          },
        };
      if (path.endsWith('/turns')) {
        if (method === 'POST') {
          const key = options.headers?.['Idempotency-Key'] ?? '';
          let turn = turns.find((item) => item.turn.idempotencyKey.key === key);
          if (!turn) {
            turn = makeTurn(turns.length + 1, String(options.data?.text), 'RUNNING', key);
            conversations[0].version++;
            turns.unshift(turn);
          }
          return turn;
        }
        return turns.filter((item) => options.params?.beforeSequence === undefined || item.turn.sequence < Number(options.params.beforeSequence)).slice(0, 20);
      }
      if (path.endsWith('/conversations')) {
        if (method === 'POST') {
          const item = makeConversation('created', String(options.data?.title));
          conversations.unshift(item);
          return item;
        }
        const filtered = conversations.filter((item) =>
          item.title.includes(String(options.params?.title ?? '')),
        );
        return filtered.slice(
          Number(options.params?.offset),
          Number(options.params?.offset) + Number(options.params?.limit),
        );
      }
      const id = decodeURIComponent(path.split('/').at(-1) ?? '');
      const item = conversations.find((value) => value.conversationId === id);
      if (!item) throw {response: {status: 404}};
      if (method === 'PATCH') {
        item.title = String(options.data?.title);
        item.version++;
        return {...item};
      }
      if (method === 'DELETE') {
        conversations = conversations.filter((value) => value !== item);
        return {...item, status: 'DELETED', deletedAt: item.updatedAt};
      }
      return {...item};
    },
  );
});
afterEach(() => {
  cleanup();
  client.clear();
});

function view() {
  return render(
    <QueryClientProvider client={client}>
      <App>
        <ChatPage/>
      </App>
    </QueryClientProvider>,
  );
}

async function renameDialog() {
  fireEvent.click(
    await screen.findByRole('button', {name: '重命名：原会话'}),
  );
  return screen.findByRole('dialog');
}

function makeTurn(sequence: number, text: string, status: 'RUNNING' | 'SUCCEEDED' | 'OUTCOME_UNKNOWN' = 'SUCCEEDED', key = `history-${sequence}`): ChatTurnResult {
  return {
    turn: {
      turnId: `turn-${sequence}`,
      conversationId: 'first',
      sequence,
      kind: 'MESSAGE',
      status: 'ACCEPTED',
      input: [{role: 'USER', parts: [{text}]}],
      idempotencyKey: {key},
      rejectionError: null,
      createdAt: '2026-10-03T00:00:00Z'
    },
    execution: {
      executionId: `execution-${sequence}`,
      status,
      result: status === 'SUCCEEDED' ? {output: [{text: `回答${sequence}`}]} : null,
      error: null
    },
  };
}

function selectFirst() {
  state.location.search = '?tenantId=tenant-real&workspaceId=workspace-real&conversationId=first';
}

async function sendDialog(text: string) {
  const input = await screen.findByRole('textbox', {name: '消息内容'});
  await waitFor(() => expect(input).toBeEnabled());
  fireEvent.change(input, {target: {value: text}});
  fireEvent.click(screen.getByRole('button', {name: '发送消息'}));
  return screen.findByRole('dialog');
}

function confirmSend(dialog: HTMLElement) {
  fireEvent.click(within(dialog).getByRole('checkbox'));
  fireEvent.click(within(dialog).getByRole('button', {name: '确认并发送'}));
}

describe('new conversation management', () => {
  it('creates, selects, renames and soft-deletes using server workspace and versions', async () => {
    view();
    await screen.findByRole('button', {name: '原会话'});
    fireEvent.click(screen.getByRole('button', {name: '新建会话'}));
    let dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('会话名称'), {
      target: {value: '新会话'},
    });
    fireEvent.click(within(dialog).getByRole('button', {name: /保.*存/}));
    await screen.findByRole('heading', {name: '新会话'});
    expect(state.location.search).toContain('tenantId=tenant-real');
    expect(state.location.search).toContain('conversationId=created');
    const create = state.request.mock.calls.find(
      ([, options]) => options.method === 'POST',
    );
    expect(create?.[1].data).toEqual({...scope, title: '新会话'});
    fireEvent.click(screen.getByRole('button', {name: /重命名：新会话/}));
    dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('会话名称'), {
      target: {value: '新名称'},
    });
    fireEvent.click(within(dialog).getByRole('button', {name: /保.*存/}));
    await screen.findByRole('heading', {name: '新名称'});
    expect(
      state.request.mock.calls.find(
        ([, options]) => options.method === 'PATCH',
      )?.[1].data.expectedVersion,
    ).toBe(1);
    await waitFor(() =>
      expect(
        screen.getByRole('button', {name: '删除：新名称'}),
      ).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole('button', {name: '删除：新名称'}));
    const confirmation = (
      await screen.findAllByText('删除这个会话？')
    )[0].closest('[role="dialog"]') as HTMLElement;
    fireEvent.click(
      within(confirmation).getByRole('button', {name: /删.*除/}),
    );
    await waitFor(() =>
      expect(state.location.search).not.toContain('conversationId='),
    );
    expect(
      state.request.mock.calls.find(
        ([, options]) => options.method === 'DELETE',
      )?.[1].params.expectedVersion,
    ).toBe(2);
    expect(
      conversations.some((item) => item.conversationId === 'created'),
    ).toBe(false);
  });
  it('restores selected conversation from URL and paginates with look-ahead', async () => {
    conversations = Array.from({length: 22}, (_, index) =>
      makeConversation(`c${index}`, `会话${index}`),
    );
    state.location.search =
      '?tenantId=tenant-real&workspaceId=workspace-real&conversationId=c21';
    view();
    await screen.findByRole('heading', {name: '会话21'});
    fireEvent.click(screen.getByRole('button', {name: '下一页'}));
    await screen.findByRole('button', {name: '会话21'});
    expect(screen.getByRole('button', {name: '下一页'})).toBeDisabled();
    expect(
      state.request.mock.calls.some(
        ([, options]) =>
          options.params?.offset === 20 && options.params.limit === 21,
      ),
    ).toBe(true);
  });
  it('does not auto-repeat uncertain creation and tells the user to query first', async () => {
    rejectWrites = 503;
    view();
    await screen.findByRole('button', {name: '原会话'});
    fireEvent.click(screen.getByRole('button', {name: '新建会话'}));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('会话名称'), {
      target: {value: '名称'},
    });
    fireEvent.click(within(dialog).getByRole('button', {name: /保.*存/}));
    await screen.findByText(/无法确认操作结果/);
    expect(
      state.request.mock.calls.filter(
        ([, options]) => options.method === 'POST',
      ),
    ).toHaveLength(1);
  });
  it('refreshes after a version conflict and leaves the newer title intact', async () => {
    view();
    const dialog = await renameDialog();
    conversations[0].title = '他处修改';
    conversations[0].version = 2;
    rejectWrites = 409;
    fireEvent.change(within(dialog).getByLabelText('会话名称'), {
      target: {value: '我的修改'},
    });
    fireEvent.click(within(dialog).getByRole('button', {name: /保.*存/}));
    await screen.findByText(/会话已被更新/);
    await screen.findByRole('button', {name: '他处修改'});
    expect(conversations[0].title).toBe('他处修改');
  });
  it('shows disabled and denied states without making conversation requests', async () => {
    bootstrap = {
      enabled: false,
      unavailableReason: 'CHAT_DISABLED',
      workspaces: [],
      defaultModel: null,
    };
    const rendered = view();
    await screen.findByText('新聊天尚未启用');
    expect(state.request.mock.calls).toHaveLength(1);
    rendered.unmount();
    client.clear();
    state.access = false;
    view();
    await screen.findByText('无权访问新聊天');
    expect(state.request.mock.calls).toHaveLength(1);
  });
  it('searches literal title text and resets pagination', async () => {
    conversations = [
      makeConversation('percent', '100%完成'),
      makeConversation('other', '其他'),
    ];
    view();
    await screen.findByRole('button', {name: '100%完成'});
    const search = screen.getByRole('searchbox', {name: '按会话标题检索'});
    fireEvent.change(search, {target: {value: '100%'}});
    fireEvent.keyDown(search, {
      key: 'Enter',
      code: 'Enter',
      charCode: 13,
      keyCode: 13,
    });
    await waitFor(() =>
      expect(
        state.request.mock.calls.some(
          ([, options]) =>
            options.params?.title === '100%' && options.params.offset === 0,
        ),
      ).toBe(true),
    );
    await waitFor(() =>
      expect(
        screen.queryByRole('button', {name: '其他'}),
      ).not.toBeInTheDocument(),
    );
  });
  it('discards late mutation responses after logout', async () => {
    const rendered = view();
    await screen.findByRole('button', {name: '原会话'});
    let resolveCreate!: (value: Conversation) => void;
    const original = state.request.getMockImplementation();
    state.request.mockImplementation((path, options) =>
      options?.method === 'POST'
        ? new Promise<Conversation>((resolve) => {
          resolveCreate = resolve;
        })
        : original?.(path, options),
    );
    fireEvent.click(screen.getByRole('button', {name: '新建会话'}));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('会话名称'), {
      target: {value: '待创建'},
    });
    fireEvent.click(within(dialog).getByRole('button', {name: /保.*存/}));
    await waitFor(() => expect(resolveCreate).toBeDefined());
    const originalSearch = state.location.search;
    state.userId = '';
    rendered.rerender(
      <QueryClientProvider client={client}>
        <App>
          <ChatPage/>
        </App>
      </QueryClientProvider>,
    );
    await screen.findByText('无权访问新聊天');
    await act(async () => {
      resolveCreate(makeConversation('late', '待创建'));
    });
    expect(state.location.search).toBe(originalSearch);
    expect(
      client.getQueryCache().findAll({queryKey: ['ai-new', '7']}),
    ).toHaveLength(0);
  });
  it('does not send forged workspace selections from a URL', async () => {
    state.location.search = '?tenantId=forged&workspaceId=forged';
    view();
    await screen.findByText(/链接中的空间当前不可用/);
    expect(state.request.mock.calls).toHaveLength(1);
  });
  it('hides cached details when current permission is revoked', async () => {
    state.location.search =
      '?tenantId=tenant-real&workspaceId=workspace-real&conversationId=first';
    view();
    await screen.findByRole('heading', {name: '原会话'});
    revoked = true;
    await act(async () => {
      await client.invalidateQueries({queryKey: ['ai-new', '7']});
    });
    await screen.findByText('无权访问新聊天');
    expect(
      screen.queryByRole('heading', {name: '原会话'}),
    ).not.toBeInTheDocument();
  });
});
