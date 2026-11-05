import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {history} from '@umijs/max';
import ModelsPage from './index';
import ConnectionsPage from '@/pages/AI/Connections';
import ModelPlansPage from '@/pages/AI/ModelPlans';
import ModelSharingPage from '@/pages/AI/ModelSharing';
import * as coordination from '@/features/workspace/useWorkspace';
import * as api from '@/services/platform/workspace';
import {getConfigurationCapabilities, listWorkspaceModelOptions, type ModelSelection} from '@/services/platform/ai';
import {aiLocation, parseAiIntent} from '@/features/ainew/aiNavigation';

vi.mock('@umijs/max', () => ({
  history: {
    push: vi.fn(), replace: vi.fn(), block: vi.fn(() => () => {
    })
  },
  useModel: () => ({initialState: {currentUser: user}}),
}));
vi.mock('@/features/workspace/useWorkspace', () => ({
  useWorkspaceManagement: () => directory,
  useCurrentWorkspace: () => directory.current,
  registerWorkspaceGuard: vi.fn(() => () => {
  }),
}));
vi.mock('@/services/platform/workspace', async original => ({
  ...await original<typeof import('@/services/platform/workspace')>(),
  getWorkspace: vi.fn(), browseTenantWorkspaces: vi.fn(),
}));
vi.mock('@/services/platform/ai', async original => ({
  ...await original<typeof import('@/services/platform/ai')>(),
  getConfigurationCapabilities: vi.fn(), listWorkspaceModelOptions: vi.fn(),
}));
vi.mock('@/features/ainew/ModelManager', () => ({
  default: ({context, executionScope, initialModel, scopeFilter, onConfigureWorkspace, onUnsafeChange}: EditorProps) =>
    <>{scopeFilter}<Editor name="模型草稿" onUnsafeChange={onUnsafeChange}/>
      <span>管理：{context?.scope.workspaceId}</span>
      {executionScope && <span>使用：{executionScope.workspaceId}</span>}
      {initialModel && <span>定位：{initialModel.binding.version}</span>}
      {onConfigureWorkspace &&
        <button type="button" onClick={() => onConfigureWorkspace(exact)}>选择执行空间</button>}</>,
}));
vi.mock('@/features/ainew/ConnectionManager', () => ({
  default: ({onUnsafeChange}: EditorProps) => <Editor name="连接草稿" onUnsafeChange={onUnsafeChange}/>,
}));
vi.mock('@/features/ainew/ModelPlanManager', () => ({
  default: ({onUnsafeChange, onOpenTemplate}: EditorProps & { onOpenTemplate?: () => void }) =>
    <>{onOpenTemplate && <button type="button" onClick={onOpenTemplate}>平台默认 AI 模板</button>}
      <Editor name="费率草稿" onUnsafeChange={onUnsafeChange}/></>,
}));
vi.mock('@/features/ainew/DefaultAiTemplateEditor', () => ({
  default: ({onUnsafeChange}: EditorProps) => <Editor name="默认模板" onUnsafeChange={onUnsafeChange}/>,
}));

type EditorProps = {
  executionScope?: api.WorkspaceScope; context?: { scope: api.WorkspaceScope }; initialModel?: ModelSelection;
  scopeFilter?: React.ReactNode; onConfigureWorkspace?: (model: ModelSelection) => void;
  onUnsafeChange?: (value: boolean) => void;
};

function Editor({name, onUnsafeChange}: { name: string; onUnsafeChange?: (value: boolean) => void }) {
  const [value, setValue] = React.useState('');
  React.useEffect(() => {
    onUnsafeChange?.(!!value);
  }, [value, onUnsafeChange]);
  return <input aria-label={name} value={value} onChange={event => setValue(event.target.value)}/>;
}

const exact = {resourceId: 'model', binding: {type: 'binding', id: 'binding', version: 'v1'}};
const spaces: api.WorkspaceView[] = ['A', 'B', 'C'].map(id => ({
  scope: {tenantId: 'tenant', workspaceId: id}, name: `空间 ${id}`, state: 0, currentRole: 0, version: 0,
}));
const [a, b, c] = spaces;
const full = {available: true, manageConnections: true, managePlans: true, manageDefaultTemplate: true};
let user: { id: string; menus?: string[] } | null;
let directory: Pick<ReturnType<typeof coordination.useWorkspaceDirectory>, 'current' | 'records' | 'loading' | 'exhausted'
  | 'cursorExpired' | 'nextCursor' | 'error' | 'select' | 'load' | 'loadMore'>;
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  window.history.replaceState(null, '', '/Environment/Models');
  user = {id: 'operator', menus: ['EnvironmentWorkspaces']};
  directory = {
    current: a, records: spaces.map(space => ({...space, defaultWorkspace: false})),
    loading: false, exhausted: true, cursorExpired: false, nextCursor: null, error: null,
    select: vi.fn(), load: vi.fn(), loadMore: vi.fn()
  };
  vi.mocked(getConfigurationCapabilities).mockResolvedValue(full);
  vi.mocked(api.getWorkspace).mockImplementation(async scope =>
    ({body: {data: spaces.find(space => space.scope.workspaceId === scope.workspaceId)}}) as never);
  vi.mocked(api.browseTenantWorkspaces).mockResolvedValue({
    body: {
      data: {
        records: [{...c, defaultWorkspace: false}],
        nextCursor: null, exhausted: true
      }
    }
  } as never);
  vi.mocked(listWorkspaceModelOptions).mockResolvedValue({body: {data: {items: [exact], nextCursor: null}}} as never);
  vi.mocked(coordination.registerWorkspaceGuard).mockImplementation(() => () => {
  });
});
afterEach(cleanup);
const tab = async (name: string) => fireEvent.click(await screen.findByRole('tab', {name}));

async function choose(label: string, option: string) {
  fireEvent.mouseDown(screen.getByRole('combobox', {name: label}));
  fireEvent.click(await screen.findByText(option, {selector: '.ant-select-item-option-content'}));
}

it('orders tabs, starts with a local empty selection and keeps the platform independent of space', async () => {
  render(<ModelsPage/>);
  await screen.findByRole('tab', {name: '供应商连接'});
  expect(screen.getAllByRole('tab').map(item => item.textContent)).toEqual(['模型管理', '供应商连接', '模型方案与费率']);
  expect(screen.queryByLabelText('模型草稿')).not.toBeInTheDocument();
  expect(api.getWorkspace).not.toHaveBeenCalled();
  await tab('供应商连接');
  fireEvent.change(screen.getByLabelText('连接草稿'), {target: {value: '连接未保存'}});
  expect(screen.queryByRole('combobox', {name: '选择管理空间'})).not.toBeInTheDocument();
  await tab('模型管理');
  await choose('选择管理空间', '空间 B（tenant / B）');
  await screen.findByText('管理：B');
  await tab('供应商连接');
  expect(screen.getByLabelText('连接草稿')).toHaveValue('连接未保存');
  expect(directory.select).not.toHaveBeenCalled();
});

it('does not mount platform editors when the capability is denied or unavailable', async () => {
  vi.mocked(getConfigurationCapabilities).mockResolvedValue({
    ...full,
    manageConnections: false,
    managePlans: false,
    manageDefaultTemplate: false
  });
  const first = render(<ConnectionsPage/>);
  await screen.findByText('当前账号没有该平台配置权限。');
  expect(screen.getAllByRole('tab').map(item => item.textContent)).toEqual(['模型管理']);
  expect(screen.queryByLabelText('连接草稿')).not.toBeInTheDocument();
  first.unmount();
  vi.mocked(getConfigurationCapabilities).mockRejectedValue(new Error('offline'));
  render(<ModelPlansPage/>);
  await screen.findByText('平台配置资格暂不可核验，请重试。');
  expect(screen.queryByLabelText('费率草稿')).not.toBeInTheDocument();
});

it('guards leaving a dirty model when returning to the original chat', async () => {
  window.history.replaceState(null, '', aiLocation('models', 'operator', {
    scope: b.scope,
    chat: a.scope,
    returnTo: 'chat'
  }));
  render(<ModelsPage/>);
  fireEvent.change(await screen.findByLabelText('模型草稿'), {target: {value: '未保存'}});
  fireEvent.click(screen.getByRole('button', {name: '返回聊天'}));
  expect(screen.getByText('处理当前配置后继续')).toBeInTheDocument();
  expect(history.push).not.toHaveBeenCalled();
  expect(api.getWorkspace).not.toHaveBeenCalledWith(a.scope, expect.anything());
});

it('carries B management, C execution and A chat with the exact version', async () => {
  window.history.replaceState(null, '', aiLocation('models', 'operator', {
    scope: b.scope,
    chat: a.scope,
    returnTo: 'chat'
  }));
  render(<ModelsPage/>);
  fireEvent.click(await screen.findByRole('button', {name: '选择执行空间'}));
  await choose('配置目标空间', '空间 C · C');
  fireEvent.click(screen.getByRole('button', {name: '打开此空间 AI 配置'}));
  const path = vi.mocked(history.push).mock.calls[0][0] as string;
  expect(path).toContain('/Environment/Workspaces?');
  expect(parseAiIntent(path.split('?')[1], 'operator')).toMatchObject({
    scope: c.scope, execution: c.scope, management: b.scope, chat: a.scope, model: exact, returnTo: 'models',
  });
  expect(api.browseTenantWorkspaces).toHaveBeenCalledWith('tenant', null, '', expect.anything());
  expect(directory.current).toBe(a);
  expect(directory.select).not.toHaveBeenCalled();
});

it('rechecks execution and restores the exact model on return, leaving the original chat separate', async () => {
  window.history.replaceState(null, '', aiLocation('models', 'operator', {
    scope: b.scope, execution: c.scope, chat: a.scope, model: exact, returnTo: 'chat',
  }));
  render(<ModelsPage/>);
  await screen.findByText('管理：B');
  expect(screen.getByText('使用：C')).toBeInTheDocument();
  expect(screen.getByText('定位：v1')).toBeInTheDocument();
  expect(listWorkspaceModelOptions).toHaveBeenCalledWith(c.scope, undefined, expect.anything());
  fireEvent.click(screen.getByRole('button', {name: '返回聊天'}));
  await waitFor(() => expect(history.push).toHaveBeenCalledWith('/AI/ChatNew'));
  expect(api.getWorkspace).toHaveBeenCalledWith(a.scope, expect.anything());
  expect(directory.select).not.toHaveBeenCalled();
});

it('fails an obsolete execution version and does not restore a changed original chat', async () => {
  directory.current = c;
  vi.mocked(listWorkspaceModelOptions).mockResolvedValue({body: {data: {items: [], nextCursor: null}}} as never);
  window.history.replaceState(null, '', aiLocation('models', 'operator', {
    scope: b.scope,
    execution: c.scope,
    chat: a.scope,
    model: exact
  }));
  render(<ModelsPage/>);
  await screen.findByText(/指定模型版本不可读或已失效/);
  expect(screen.queryByLabelText('模型草稿')).not.toBeInTheDocument();
  expect(screen.getByRole('button', {name: '返回聊天'})).toBeDisabled();
  expect(directory.select).not.toHaveBeenCalled();
});

it('redirects the old workspace-ai deep link preserving full target', () => {
  window.history.replaceState(null, '', '/Environment/Models?tab=workspace-ai&tenantId=tenant&workspaceId=B');
  render(<ModelsPage/>);
  expect(history.replace).toHaveBeenCalledWith(expect.stringContaining('Workspaces?account=operator&tab=ai&tenantId=tenant&workspaceId=B'));
});

it.each([[ConnectionsPage, '供应商连接'], [ModelPlansPage, '模型方案与费率'], [ModelSharingPage, '模型管理']] as const)(
  'maps compatibility routes to the unified tab', async (Page, name) => {
    render(<Page/>);
    expect(await screen.findByRole('tab', {name})).toHaveAttribute('aria-selected', 'true');
  });

it('ignores a late authorized response after logout', async () => {
  let finish!: (value: Awaited<ReturnType<typeof api.getWorkspace>>) => void;
  window.history.replaceState(null, '', aiLocation('models', 'operator', {scope: b.scope}));
  vi.mocked(api.getWorkspace).mockImplementationOnce(() => new Promise(resolve => {
    finish = resolve;
  }));
  render(<ModelsPage/>);
  act(() => window.dispatchEvent(new CustomEvent('arte-account-session-cleared', {detail: 'operator'})));
  await act(async () => finish({body: {data: b}} as never));
  expect(screen.getByText(/账号会话已结束/)).toBeInTheDocument();
  expect(screen.queryByLabelText('模型草稿')).not.toBeInTheDocument();
});
