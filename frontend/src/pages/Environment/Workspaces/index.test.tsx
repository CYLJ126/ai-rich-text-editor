import React, {useState} from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {history} from '@umijs/max';
import {ConfigProvider} from 'antd';
import {getWorkspace} from '@/services/platform/workspace';
import {getManagedModel} from '@/services/platform/model';
import {listWorkspaceModelOptions} from '@/services/platform/ai';
import {aiLocation, parseAiIntent} from '@/features/ainew/aiNavigation';
import {browseAccessibleTenants} from '@/services/platform/tenant';
import {retainWorkspaceSelection} from '@/features/workspace/useWorkspace';
import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';
import type WorkspaceDetailForm from '@/features/workspace/WorkspaceDetailForm';
import Page from './index';

vi.mock('@umijs/max', () => ({
  useModel: () => ({initialState: {currentUser: {id: 'alice'}}}), history: {
    block: vi.fn(() => () => {
    }), listen: vi.fn(() => () => {
    }), push: vi.fn()
  }
}));
vi.mock('@/features/workspace/useWorkspace', () => ({
  useWorkspaceManagement: () => directory,
  useCurrentWorkspace: () => chat,
  registerWorkspaceGuard: vi.fn(() => () => {
  }),
  retainWorkspaceSelection: vi.fn(),
}));
vi.mock('@/services/platform/model', () => ({getManagedModel: vi.fn()}));
vi.mock('@/services/platform/ai', () => ({listWorkspaceModelOptions: vi.fn()}));
vi.mock('@/services/platform/tenant', () => ({browseAccessibleTenants: vi.fn()}));
vi.mock('@/services/platform/workspace', async original => ({
  ...await original<typeof import('@/services/platform/workspace')>(), getWorkspace: vi.fn()
}));
vi.mock('@/features/workspace/WorkspaceDetailForm', () => ({
  default: function Detail({
                             scope,
                             tab,
                             tenants,
                             onSaved,
                             onUnsafeChange,
                             onCancel,
                             focusedModel
                           }: React.ComponentProps<typeof WorkspaceDetailForm>) {
    const [dirty, setDirty] = useState(false);
    useConfigurationDraft({group: 'workspace-metadata', label: '空间基本信息', status: dirty ? 'dirty' : 'clean'});
    return <section aria-label="配置详情测试">
      {scope ? `编辑目标 ${scope.workspaceId} · ${tab}` : '新增空间表单'}
      {!scope && tenants?.map(tenant => <div key={tenant.value}>{tenant.label}</div>)}
      <button type="button" onClick={() => {
        setDirty(true);
        onUnsafeChange?.(true);
      }}>模拟修改
      </button>
      <button type="button" onClick={() => {
        setDirty(false);
        onUnsafeChange?.(false);
        onSaved?.({...space, scope: {...space.scope, workspaceId: scope?.workspaceId ?? 'C'}});
      }}>模拟保存
      </button>
      {onCancel && <button type="button" onClick={onCancel}>取消新增</button>}
      {focusedModel && <span>精确版本 {focusedModel.binding.version}</span>}
    </section>;
  }
}));
const space = {
  scope: {tenantId: 't', workspaceId: 'B'},
  name: '空间 B',
  state: 0 as const,
  currentRole: 0 as const,
  version: 1
};
const chatSpace = {...space, scope: {...space.scope, workspaceId: 'A'}};
const executeSpace = {...space, scope: {...space.scope, workspaceId: 'C'}};
const exact = {resourceId: 'model', binding: {type: 'binding', id: 'binding', version: 'v1'}};
let chat = chatSpace;
const pref = {defaultScope: space.scope, startupScope: space.scope, lastScope: null, favorites: [], version: 0};
let directory: ReturnType<typeof import('@/features/workspace/useWorkspace').useWorkspaceManagement>;
const select = vi.fn();
beforeEach(() => {
  vi.resetAllMocks();
  window.history.replaceState(null, '', '/Environment/Workspaces');
  chat = chatSpace;
  vi.mocked(getWorkspace).mockImplementation(async scope => ({body: {data: {...space, scope}}}) as never);
  vi.mocked(getManagedModel).mockResolvedValue({body: {data: {state: 'PUBLISHED'}}} as never);
  vi.mocked(listWorkspaceModelOptions).mockResolvedValue({body: {data: {items: [exact], nextCursor: null}}} as never);
  directory = {
    records: [{...space, defaultWorkspace: false}], preference: pref, search: '', loaded: true,
    load: vi.fn(), loadMore: vi.fn(), updatePreference: vi.fn(), retry: vi.fn(),
    favorite: vi.fn((scope: typeof space.scope) => directory.preference?.favorites.some(item =>
      item.tenantId === scope.tenantId && item.workspaceId === scope.workspaceId) ?? false)
  } as unknown as typeof directory;
  vi.mocked(retainWorkspaceSelection).mockReturnValue({select, release: vi.fn()});
  vi.mocked(browseAccessibleTenants).mockResolvedValue({
    body: {
      data: {
        records: [{id: 'org-without-space', name: '尚无空间的组织'}], nextCursor: null, exhausted: true,
      }
    }
  } as never);
});
afterEach(cleanup);

const choose = () => fireEvent.click(screen.getByRole('button', {name: '配置 空间 B'}));
const withoutMotion = ({children}: { children: React.ReactNode }) =>
  <ConfigProvider theme={{token: {motion: false}}}>{children}</ConfigProvider>;

it('opens an overview only after selecting a card when the menu has no target', () => {
  const page = render(<Page/>);
  expect(screen.getByText('从左侧选择工作空间，或点击列表上方的新建按钮。')).toBeInTheDocument();
  expect(screen.queryByRole('region', {name: '配置详情测试'})).not.toBeInTheDocument();
  choose();
  expect(screen.getByText('编辑目标 B · overview')).toBeInTheDocument();
  expect(retainWorkspaceSelection).not.toHaveBeenCalled();
  page.unmount();
  render(<Page/>);
  expect(screen.queryByRole('region', {name: '配置详情测试'})).not.toBeInTheDocument();
});

it('protects dirty details before entering the selected workspace and reports only committed selection', async () => {
  select.mockImplementation(async (_scope, commit) => {
    commit();
    return true;
  });
  render(<Page/>);
  choose();
  fireEvent.click(screen.getByRole('button', {name: '模拟修改'}));
  fireEvent.click(screen.getByRole('button', {name: '进入此空间'}));
  expect(screen.getByText('处理当前配置后继续')).toBeInTheDocument();
  expect(select).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '留在本页'}));
  fireEvent.click(screen.getByRole('button', {name: '模拟保存'}));
  fireEvent.click(screen.getByRole('button', {name: '进入此空间'}));
  expect(await screen.findByText('已进入此空间，聊天使用空间已切换。')).toBeInTheDocument();
  expect(select).toHaveBeenCalledWith(space.scope, expect.any(Function));
});

it('uses the independent tenant directory for creation and opens the created space locally', async () => {
  render(<Page/>);
  expect(browseAccessibleTenants).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '新建工作空间'}));
  expect(screen.getByText('新增空间表单')).toBeInTheDocument();
  expect(await screen.findByText('尚无空间的组织')).toBeInTheDocument();
  expect(screen.queryByRole('button', {name: '进入此空间'})).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '模拟保存'}));
  expect(screen.getByText('编辑目标 C · overview')).toBeInTheDocument();
  expect(retainWorkspaceSelection).not.toHaveBeenCalled();
  expect(directory.updatePreference).not.toHaveBeenCalled();
});

it('clears the account startup preference from the directory without selecting a workspace', () => {
  const page = render(<Page/>);
  fireEvent.change(screen.getByLabelText('搜索工作空间'), {target: {value: '空间'}});
  fireEvent.click(screen.getByRole('button', {name: /^搜\s*索$/}));
  expect(directory.load).toHaveBeenCalledWith('空间');
  fireEvent.click(screen.getByRole('button', {name: '清除启动空间偏好'}));
  expect(directory.updatePreference).toHaveBeenCalledWith({startupScope: null});
  expect(screen.queryByRole('region', {name: '配置详情测试'})).not.toBeInTheDocument();
  directory = {...directory, preference: {...pref, startupScope: null}};
  page.rerender(<Page/>);
  expect(screen.getByRole('button', {name: '清除启动空间偏好'})).toBeDisabled();
});

it('toggles preferences for a card without changing the selected configuration or usage space', () => {
  directory = {
    ...directory,
    records: [...directory.records, {...executeSpace, name: '空间 C', defaultWorkspace: false}]
  };
  const page = render(<Page/>);
  choose();
  const favorite = screen.getByRole('button', {name: '收藏 空间 C'});
  const startup = screen.getByRole('button', {name: '启动空间 空间 C'});
  expect(favorite).toHaveAttribute('aria-pressed', 'false');
  expect(startup).toHaveAttribute('aria-pressed', 'false');
  fireEvent.click(favorite);
  fireEvent.click(startup);
  expect(directory.updatePreference).toHaveBeenNthCalledWith(1, {favorites: [executeSpace.scope]});
  expect(directory.updatePreference).toHaveBeenNthCalledWith(2, {startupScope: executeSpace.scope});

  directory = {...directory, preference: {...pref, startupScope: executeSpace.scope, favorites: [executeSpace.scope]}};
  page.rerender(<Page/>);
  expect(favorite).toHaveAttribute('aria-pressed', 'true');
  expect(startup).toHaveAttribute('aria-pressed', 'true');
  expect(screen.getByRole('button', {name: '启动空间 空间 B'})).toHaveAttribute('aria-pressed', 'false');
  fireEvent.click(favorite);
  fireEvent.click(startup);
  expect(directory.updatePreference).toHaveBeenNthCalledWith(3, {favorites: []});
  expect(directory.updatePreference).toHaveBeenNthCalledWith(4, {startupScope: null});
  expect(screen.getByText('编辑目标 B · overview')).toBeInTheDocument();
  expect(retainWorkspaceSelection).not.toHaveBeenCalled();
});

it('keeps preference saving silent and offers original-operation recovery in a dialog only after failure', async () => {
  const page = render(<Page/>, {wrapper: withoutMotion});
  choose();
  fireEvent.click(screen.getByRole('button', {name: '收藏 空间 B'}));
  const preferencePending = {
    kind: 'preference' as const, key: 'original',
    request: {expectedPreferenceVersion: 0, favorites: [space.scope], startupScope: space.scope, lastScope: null}
  };
  directory = {...directory, preferenceSaving: true, preferencePending};
  page.rerender(<Page/>);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.queryByText('有待确认空间操作，请处理原操作。')).not.toBeInTheDocument();
  expect(screen.getByText('编辑目标 B · overview')).toBeInTheDocument();
  expect(screen.getByRole('button', {name: '收藏 空间 B'})).toHaveAttribute('aria-pressed', 'false');
  fireEvent.click(screen.getByRole('button', {name: '启动空间 空间 B'}));
  expect(directory.updatePreference).toHaveBeenCalledOnce();

  directory = {...directory, preferenceSaving: false, preferenceFailure: new Error('network')};
  page.rerender(<Page/>);
  expect(await screen.findByRole('dialog', {name: '偏好保存结果待确认'})).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '重试原操作'}));
  expect(directory.retry).toHaveBeenCalledOnce();
  directory = {
    ...directory,
    preferencePending: null,
    preferenceFailure: null,
    preference: {...pref, favorites: [space.scope]}
  };
  page.rerender(<Page/>);
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(screen.getByRole('button', {name: '收藏 空间 B'})).toHaveAttribute('aria-pressed', 'true');
});

it('shows a failure-only dialog when the server rejects a preference', async () => {
  const page = render(<Page/>, {wrapper: withoutMotion});
  directory = {...directory, preferenceFailure: new Error('偏好已在其他操作中更新，请核对后重试。')};
  page.rerender(<Page/>);
  expect(await screen.findByRole('dialog', {name: '偏好保存失败'})).toHaveTextContent('偏好已在其他操作中更新');
  expect(screen.queryByRole('button', {name: '重试原操作'})).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '知道了'}));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
});

it.each(['ai', 'policy'])('validates an explicit %s link and opens the corresponding detail tab', async tab => {
  window.history.replaceState(null, '', `/Environment/Workspaces?account=alice&tenantId=t&workspaceId=B&tab=${tab}`);
  vi.mocked(getWorkspace).mockResolvedValue({body: {data: space}} as never);
  render(<Page/>);
  expect(await screen.findByText(`编辑目标 B · ${tab}`)).toBeInTheDocument();
  expect(getWorkspace).toHaveBeenCalledWith(space.scope, {signal: expect.any(AbortSignal)});
  expect(directory.updatePreference).not.toHaveBeenCalled();
  expect(retainWorkspaceSelection).not.toHaveBeenCalled();
});

it('keeps the current usage space when revalidation fails', async () => {
  select.mockResolvedValue(false);
  render(<Page/>);
  choose();
  fireEvent.click(screen.getByRole('button', {name: '进入此空间'}));
  await waitFor(() => expect(screen.getByText(/未切换使用空间/)).toBeInTheDocument());
  expect(screen.queryByText('已进入此空间，聊天使用空间已切换。')).not.toBeInTheDocument();
});

const taskPath = () => aiLocation('workspaces', 'alice', {
  scope: executeSpace.scope, execution: executeSpace.scope, management: space.scope, chat: chatSpace.scope,
  model: exact, returnTo: 'models',
});

it('verifies C, verifies B resource ownership and restores v1 with a guarded return to B', async () => {
  window.history.replaceState(null, '', taskPath());
  render(<Page/>);
  await screen.findByText('编辑目标 C · ai');
  expect(screen.getByText('精确版本 v1')).toBeInTheDocument();
  expect(getManagedModel).toHaveBeenCalledWith(space.scope, 'model', expect.anything());
  expect(listWorkspaceModelOptions).toHaveBeenCalledWith(executeSpace.scope, undefined, expect.anything());
  fireEvent.click(screen.getByRole('button', {name: '模拟修改'}));
  fireEvent.click(screen.getByRole('button', {name: '返回模型详情'}));
  expect(screen.getByText('处理当前配置后继续')).toBeInTheDocument();
  expect(history.push).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '留在本页'}));
  fireEvent.click(screen.getByRole('button', {name: '模拟保存'}));
  fireEvent.click(screen.getByRole('button', {name: '返回模型详情'}));
  const route = vi.mocked(history.push).mock.calls[0][0] as string;
  expect(parseAiIntent(route.split('?')[1], 'alice')).toMatchObject({
    scope: space.scope, execution: executeSpace.scope, chat: chatSpace.scope, model: exact, returnTo: 'chat',
  });
  expect(retainWorkspaceSelection).not.toHaveBeenCalled();
});

it.each(['latest', 'different'])('rejects a stale or substituted version %s and retains the original usage scope', async version => {
  const path = taskPath();
  if (version === 'latest') window.history.replaceState(null, '', path.replace('bindingVersion=v1', 'bindingVersion=latest'));
  else {
    window.history.replaceState(null, '', path);
    vi.mocked(listWorkspaceModelOptions).mockResolvedValue({
      body: {
        data: {
          items: [
            {...exact, binding: {...exact.binding, version: 'v2'}}], nextCursor: null
        }
      }
    } as never);
  }
  render(<Page/>);
  await screen.findByText(/精确版本|指定模型版本/);
  expect(screen.queryByText('编辑目标 C · ai')).not.toBeInTheDocument();
  expect(retainWorkspaceSelection).not.toHaveBeenCalled();
});

it('blocks returning to a changed chat source without switching workspaces', async () => {
  chat = executeSpace;
  window.history.replaceState(null, '', taskPath());
  render(<Page/>);
  await screen.findByText('编辑目标 C · ai');
  expect(screen.getByRole('button', {name: '返回聊天'})).toBeDisabled();
  expect(retainWorkspaceSelection).not.toHaveBeenCalled();
  expect(history.push).not.toHaveBeenCalled();
});
