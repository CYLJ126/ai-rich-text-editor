import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {history} from '@umijs/max';
import {getWorkspace} from '@/services/platform/workspace';
import {listWorkspaceModelOptions} from '@/services/platform/ai';
import Page from './index';

const route = vi.hoisted(() => ({
  search: '',
  listeners: new Set<() => void>(),
  current: {tenantId: 't', workspaceId: 'A'}
}));
const select = vi.hoisted(() => vi.fn(async (_scope: unknown, onSelected?: () => void) => {
  onSelected?.();
  return true;
}));
const guard = vi.hoisted(() => vi.fn(() => () => {
}));
vi.mock('@umijs/max', () => ({
  history: {
    get location() {
      return {search: route.search};
    },
    listen: (fn: () => void) => {
      route.listeners.add(fn);
      return () => route.listeners.delete(fn);
    },
    push: vi.fn((url: string) => {
      route.search = url.includes('?') ? url.slice(url.indexOf('?')) : '';
      route.listeners.forEach(fn => {
        fn();
      });
    }),
    block: guard,
  }, useModel: () => ({initialState: {currentUser: {id: 'alice'}}})
}));
vi.mock('@/features/workspace/useWorkspace', () => ({
  useWorkspaceDirectory: () => ({current: {scope: route.current}, select}),
  registerWorkspaceGuard: vi.fn(() => () => {
  }),
  protectWorkspaceChange: (_user: string, _scope: unknown, proceed: () => void) => proceed(),
}));
vi.mock('@/services/platform/workspace', async original => ({
  ...await original<typeof import('@/services/platform/workspace')>(),
  getWorkspace: vi.fn()
}));
vi.mock('@/services/platform/ai', () => ({listWorkspaceModelOptions: vi.fn()}));
vi.mock('@/features/workspace/WorkspacePicker', () => ({
  default: ({onConfigure, configurationDisabled}: {
    onConfigure: (view: typeof space) => void;
    configurationDisabled: boolean
  }) =>
    <button type="button" disabled={configurationDisabled} onClick={() => onConfigure(space)}>配置 B</button>
}));
vi.mock('@/features/ainew/WorkspaceAiGovernanceEditor', () => ({
  default: ({context, onUpdated, onUnsafeChange}: {
    context: { scope: { workspaceId: string } }; onUpdated: () => void; onUnsafeChange: (value: boolean) => void
  }) => <div>编辑目标 {context.scope.workspaceId}
    <button type="button" onClick={() => onUnsafeChange(true)}>模拟未保存模型修改</button>
    <button type="button" onClick={() => {
      onUnsafeChange(false);
      onUpdated();
    }}>模拟保存
    </button>
  </div>
}));
const space = {
  scope: {tenantId: 't', workspaceId: 'B'},
  name: '空间 B',
  state: 0 as const,
  currentRole: 0 as const,
  version: 1
};
const model = {resourceId: 'r', binding: {type: 'binding', id: 'b', version: 'v1'}};
beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.clear();
  route.search = '';
  route.current = {tenantId: 't', workspaceId: 'A'};
  vi.mocked(getWorkspace).mockResolvedValue({body: {data: space}} as never);
  vi.mocked(listWorkspaceModelOptions).mockResolvedValue({body: {data: {items: [model], nextCursor: null}}} as never);
});
afterEach(cleanup);
it('opens target B locally, leaving chat A and preferences untouched until explicit chat action', async () => {
  render(<Page/>);
  fireEvent.click(screen.getByRole('button', {name: '配置 B'}));
  await screen.findByText('编辑目标 B');
  expect(select).not.toHaveBeenCalled();
  expect(route.current.workspaceId).toBe('A');
  expect(getWorkspace).toHaveBeenCalledWith(space.scope, expect.anything());
  fireEvent.click(screen.getByRole('button', {name: '在此空间打开聊天'}));
  await waitFor(() => expect(select).toHaveBeenCalledWith(space.scope, expect.any(Function)));
  expect(history.push).toHaveBeenLastCalledWith('/AI/ChatNew');
});
it('keeps missing target missing and refuses incomplete or arbitrary return routes', () => {
  route.search = '?tab=ai';
  const view = render(<Page/>);
  expect(screen.getByText(/不会自动使用当前聊天空间/)).toBeInTheDocument();
  expect(getWorkspace).not.toHaveBeenCalled();
  view.unmount();
  route.search = '?tab=ai&tenantId=t&returnTo=https://outside.test';
  render(<Page/>);
  expect(screen.getByRole('button', {name: '配置 B'})).toBeDisabled();
  expect(getWorkspace).not.toHaveBeenCalled();
});
it('verifies exact version and returns to original management M, not target B', async () => {
  route.search = '?tab=ai&tenantId=t&workspaceId=B&management-tenantId=t&management-workspaceId=M&chat-tenantId=t&chat-workspaceId=A&resourceId=r&bindingId=b&bindingVersion=v1&returnTo=models';
  render(<Page/>);
  await screen.findByText('编辑目标 B');
  expect(listWorkspaceModelOptions).toHaveBeenCalledWith(space.scope, undefined, expect.anything());
  fireEvent.click(screen.getByRole('button', {name: '返回模型管理'}));
  const url = vi.mocked(history.push).mock.calls.at(-1)?.[0];
  expect(url).toContain('workspaceId=M');
  expect(url).toContain('execution-workspaceId=B');
  expect(url).toContain('bindingVersion=v1');
  expect(select).not.toHaveBeenCalled();
});
it('rejects stale or mismatched versions without mounting the editor or selecting latest', async () => {
  route.search = '?tab=ai&tenantId=t&workspaceId=B&resourceId=r&bindingId=b&bindingVersion=old';
  render(<Page/>);
  await screen.findByText(/所定位的模型版本不可读/);
  expect(screen.queryByText('编辑目标 B')).not.toBeInTheDocument();
  expect(select).not.toHaveBeenCalled();
});
it('blocks all scope/navigation changes while dirty and signals save without switching chat', async () => {
  route.search = '?tab=ai&tenantId=t&workspaceId=B';
  render(<Page/>);
  await screen.findByText('编辑目标 B');
  fireEvent.click(screen.getByRole('button', {name: '模拟未保存模型修改'}));
  expect(screen.getByRole('button', {name: '配置 B'})).toBeDisabled();
  expect(screen.getByRole('button', {name: '返回模型管理'})).toBeDisabled();
  expect(guard).toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '模拟保存'}));
  await screen.findByText(/空间 AI 操作已保存/);
  expect(select).not.toHaveBeenCalled();
  expect(localStorage.setItem).toHaveBeenCalled();
});
it('does not allow a delayed B response to overwrite newly selected target C', async () => {
  let resolve!: (value: never) => void;
  vi.mocked(getWorkspace).mockImplementationOnce(() => new Promise(done => {
    resolve = done;
  }));
  route.search = '?tenantId=t&workspaceId=B';
  render(<Page/>);
  await waitFor(() => expect(getWorkspace).toHaveBeenCalledTimes(1));
  vi.mocked(getWorkspace).mockResolvedValue({
    body: {
      data: {
        ...space,
        scope: {...space.scope, workspaceId: 'C'}
      }
    }
  } as never);
  act(() => {
    route.search = '?tenantId=t&workspaceId=C';
    route.listeners.forEach(fn => {
      fn();
    });
  });
  await screen.findByText('编辑目标 C');
  await act(async () => resolve({body: {data: space}} as never));
  expect(screen.queryByText('编辑目标 B')).not.toBeInTheDocument();
});
