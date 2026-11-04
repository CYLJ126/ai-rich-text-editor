import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {checkWorkspaceAccess} from '@/services/platform/workspace';
import Page from './index';

vi.mock('@umijs/max', () => ({
  useModel: () => ({initialState: {currentUser: {id: 'alice'}}}), history: {
    block: vi.fn(() => () => {
    }), listen: vi.fn(() => () => {
    }), push: vi.fn()
  }
}));
vi.mock('@/features/workspace/useWorkspace', () => ({useWorkspaceManagement: () => directory}));
vi.mock('@/features/workspace/usePolicyDirectory', () => ({usePolicyDirectory: () => candidates}));
vi.mock('@/services/platform/workspace', async original => ({
  ...await original<typeof import('@/services/platform/workspace')>(), checkWorkspaceAccess: vi.fn()
}));
vi.mock('@/features/workspace/WorkspacePolicyDialog', () => ({
  default: ({target}: { target: typeof space }) => <div>政策目标 {target.scope.workspaceId}</div>
}));
vi.mock('@/features/workspace/WorkspaceDetailForm', () => ({
  default: ({scope, onSaved, onUnsafeChange, onCancel}: {
    scope?: typeof space.scope;
    onSaved: (view: typeof space) => void;
    onUnsafeChange: (unsafe: boolean) => void;
    onCancel?: () => void
  }) => <section aria-label="配置详情测试">{scope ? `编辑目标 ${scope.workspaceId}` : '新增空间表单'}
    <button onClick={() => onUnsafeChange(true)}>模拟修改</button>
    <button onClick={() => {
      onUnsafeChange(false);
      onSaved({...space, scope: {...space.scope, workspaceId: scope?.workspaceId ?? 'C'}});
    }}>模拟保存
    </button>
    {onCancel && <button onClick={onCancel}>取消新增</button>}
  </section>
}));
const space = {
  scope: {tenantId: 't', workspaceId: 'B'},
  name: '空间 B',
  state: 0 as const,
  currentRole: 0 as const,
  version: 1
};
const pref = {defaultScope: space.scope, startupScope: space.scope, lastScope: null, favorites: [], version: 0};
let directory: ReturnType<typeof import('@/features/workspace/useWorkspace').useWorkspaceManagement>;
let candidates: ReturnType<typeof import('@/features/workspace/usePolicyDirectory').usePolicyDirectory>;
beforeEach(() => {
  vi.clearAllMocks();
  window.history.replaceState(null, '', '/Environment/Workspaces?tenantId=t&workspaceId=B&returnTo=chat');
  directory = {
    records: [{...space, defaultWorkspace: false}], preference: pref, search: '', loaded: true,
    load: vi.fn(), loadMore: vi.fn(), updatePreference: vi.fn(), favorite: vi.fn(() => false)
  } as unknown as typeof directory;
  candidates = {
    records: [{...space, defaultWorkspace: false}],
    hasMore: false,
    error: '',
    reload: vi.fn()
  } as unknown as typeof candidates;
  vi.mocked(checkWorkspaceAccess).mockResolvedValue({body: {data: {allowed: true}}} as never);
});
afterEach(cleanup);

it('starts empty even with a URL target, and only opens a detail after clicking a card', async () => {
  const page = render(<Page/>);
  expect(screen.queryByRole('region', {name: '配置详情测试'})).not.toBeInTheDocument();
  expect(screen.queryByRole('button', {name: '返回原聊天'})).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '配置 空间 B'}));
  expect(screen.getByText('编辑目标 B')).toBeInTheDocument();
  page.unmount();
  render(<Page/>);
  expect(screen.queryByRole('region', {name: '配置详情测试'})).not.toBeInTheDocument();
});
it('blocks target and toolbar actions while the form is dirty and uses the selected policy scope', async () => {
  render(<Page/>);
  fireEvent.click(screen.getByRole('button', {name: '配置 空间 B'}));
  fireEvent.click(screen.getByRole('button', {name: '模拟修改'}));
  expect(screen.getByRole('button', {name: '新建工作空间'})).toBeDisabled();
  expect(screen.getByRole('button', {name: '租户与空间政策'})).toBeDisabled();
  expect(screen.getByRole('button', {name: '配置 空间 B'})).toHaveAttribute('aria-disabled', 'true');
  fireEvent.click(screen.getByRole('button', {name: '模拟保存'}));
  fireEvent.click(screen.getByRole('button', {name: '租户与空间政策'}));
  expect(screen.getByText('政策目标 B')).toBeInTheDocument();
});
it('creates in a separate initial form and opens the saved new workspace locally', async () => {
  render(<Page/>);
  await waitFor(() => expect(screen.getByRole('button', {name: '新建工作空间'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '新建工作空间'}));
  expect(screen.getByText('新增空间表单')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '模拟保存'}));
  expect(screen.getByText('编辑目标 C')).toBeInTheDocument();
});
it('merges search and refresh and clears the account startup preference from its toolbar icon', async () => {
  render(<Page/>);
  fireEvent.change(screen.getByLabelText('搜索工作空间'), {target: {value: '空间'}});
  fireEvent.click(screen.getByRole('button', {name: /^搜\s*索$/}));
  expect(directory.load).toHaveBeenCalledWith('空间');
  fireEvent.click(screen.getByRole('button', {name: '清除启动空间偏好'}));
  expect(directory.updatePreference).toHaveBeenCalledWith({startupScope: null});
});
