import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {act, cleanup, fireEvent, render, screen, waitFor, within,} from '@testing-library/react';
import {App} from 'antd';
import React, {useSyncExternalStore} from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import type {ChatBootstrap, Conversation} from '@/types/ai-new/conversation';
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
      } = {},
    ) => {
      if (path.endsWith('/bootstrap')) return bootstrap;
      if (revoked)
        throw {
          response: {
            status: 403,
            data: {
              code: 'arte.common.unauthorized',
              stage: 'identity',
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
              stage: 'store',
              retryable: false,
              sideEffectStatus: rejectWrites === 409 ? 'NONE' : 'UNKNOWN',
              resultCertainty: rejectWrites === 409 ? 'CONFIRMED' : 'UNKNOWN',
            },
          },
        };
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
