import React from 'react';
import {cleanup, fireEvent, render, screen} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {history} from '@umijs/max';
import ModelsPage from './index';
import ConnectionsPage from '@/pages/AI/Connections';
import ModelPlansPage from '@/pages/AI/ModelPlans';
import ModelSharingPage from '@/pages/AI/ModelSharing';
import * as coordination from '@/features/workspace/useWorkspace';
import * as api from '@/services/platform/workspace';

vi.mock('@umijs/max', () => ({
  history: {
    push: vi.fn(), replace: vi.fn(), block: vi.fn(() => () => {
    })
  },
  useModel: () => ({initialState: {currentUser: user}})
}));
vi.mock('@/features/workspace/useWorkspace', () => ({
  useWorkspaceDirectory: () => directory,
  registerWorkspaceGuard: vi.fn(() => () => {
  })
}));
vi.mock('@/services/platform/workspace', async original => ({
  ...await original<typeof import('@/services/platform/workspace')>(),
  getWorkspace: vi.fn()
}));
vi.mock('@/features/ainew/ModelManager', () => ({
  default: ({context, executionScope, onUnsafeChange}: EditorProps) => <Editor name="模型草稿" context={context}
                                                                               executionScope={executionScope}
                                                                               onUnsafeChange={onUnsafeChange}/>
}));
vi.mock('@/features/ainew/ConnectionManager', () => ({
  default: ({onUnsafeChange}: EditorProps) => <Editor name="连接草稿" onUnsafeChange={onUnsafeChange}/>
}));
vi.mock('@/features/ainew/ModelPlanManager', () => ({
  default: ({onUnsafeChange, onOpenTemplate}: EditorProps & { onOpenTemplate: () => void }) => <>
    <button onClick={onOpenTemplate}>平台默认 AI 模板</button>
    <Editor name="费率草稿" onUnsafeChange={onUnsafeChange}/></>
}));
vi.mock('@/features/ainew/DefaultAiTemplateEditor', () => ({
  default: ({onUnsafeChange}: EditorProps) => <Editor name="默认模板" onUnsafeChange={onUnsafeChange}/>
}));
type EditorProps = {
  executionScope?: { workspaceId: string };
  context?: { scope: { workspaceId: string } };
  onUnsafeChange?: (value: boolean) => void
};

function Editor({name, context, executionScope, onUnsafeChange}: EditorProps & { name: string }) {
  const [value, setValue] = React.useState('');
  React.useEffect(() => {
    onUnsafeChange?.(!!value);
  }, [value, onUnsafeChange]);
  React.useEffect(() => () => onUnsafeChange?.(false), [onUnsafeChange]);
  return <div><input aria-label={name} value={value} onChange={event => setValue(event.target.value)}/>
    {context && <span>{name}空间：{context.scope.workspaceId}</span>}{executionScope &&
      <span>使用条件空间：{executionScope.workspaceId}</span>}</div>;
}

const spaces: api.WorkspaceView[] = [
  {scope: {tenantId: 'tenant', workspaceId: 'first'}, name: '空间一', state: 0, currentRole: 0, version: 0},
  {scope: {tenantId: 'tenant', workspaceId: 'second'}, name: '空间二', state: 0, currentRole: 0, version: 0},
];
let user: { id: string; menus?: string[] } | null;
let directory: Pick<ReturnType<typeof coordination.useWorkspaceDirectory>, 'current' | 'records' | 'loading' | 'exhausted'
  | 'cursorExpired' | 'nextCursor' | 'error' | 'select' | 'load' | 'loadMore'>;
beforeEach(() => {
  vi.clearAllMocks();
  window.history.replaceState(null, '', '/Environment/Models');
  user = {id: 'operator', menus: ['EnvironmentWorkspaces']};
  directory = {
    current: spaces[0],
    records: spaces.map(space => ({...space, defaultWorkspace: false})),
    loading: false,
    exhausted: true,
    cursorExpired: false,
    nextCursor: null,
    error: null,
    select: vi.fn(),
    load: vi.fn(),
    loadMore: vi.fn()
  };
  vi.mocked(api.getWorkspace).mockResolvedValue({body: {data: spaces[1]}} as never);
});
afterEach(cleanup);
it('has exactly three ordered tabs and defaults to model management', async () => {
  render(<ModelsPage/>);
  expect(screen.getAllByRole('tab').map(tab => tab.textContent)).toEqual(['审核连接', '审核模型与费率', '模型管理']);
  expect(screen.getByRole('tab', {name: '模型管理'})).toHaveAttribute('aria-selected', 'true');
  await screen.findByText('模型草稿空间：first');
  fireEvent.click(screen.getByRole('button', {name: '返回聊天'}));
  expect(history.push).toHaveBeenCalledWith('/AI/ChatNew');
});
it('keeps platform drafts while choosing a local management scope without switching chat', async () => {
  render(<ModelsPage/>);
  await screen.findByText('模型草稿空间：first');
  fireEvent.click(screen.getByRole('tab', {name: '审核连接'}));
  fireEvent.change(await screen.findByLabelText('连接草稿'), {target: {value: '连接未保存'}});
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '选择工作空间'}));
  fireEvent.click(await screen.findByText('空间二（tenant / second）', {selector: '.ant-select-item-option-content'}));
  expect(directory.select).not.toHaveBeenCalled();
  expect(directory.current).toEqual(spaces[0]);
  expect(screen.getByLabelText('连接草稿')).toHaveValue('连接未保存');
  fireEvent.click(screen.getByRole('tab', {name: '模型管理'}));
  await screen.findByText('模型草稿空间：second');
});
it('protects unsaved model state across tabs, local scope and external navigation', async () => {
  render(<ModelsPage/>);
  fireEvent.change(await screen.findByLabelText('模型草稿'), {target: {value: '未保存'}});
  fireEvent.click(screen.getByRole('tab', {name: '审核连接'}));
  await screen.findByLabelText('连接草稿');
  expect(screen.getByRole('combobox', {name: '选择工作空间'})).toBeDisabled();
  expect(screen.getByRole('button', {name: '返回聊天'})).toBeDisabled();
  const proceed = vi.fn();
  vi.mocked(coordination.registerWorkspaceGuard).mock.calls.at(-1)?.[1](spaces[1].scope, proceed);
  expect(proceed).not.toHaveBeenCalled();
  expect(history.block).toHaveBeenCalled();
  fireEvent.click(screen.getByRole('tab', {name: '模型管理'}));
  expect(screen.getByLabelText('模型草稿')).toHaveValue('未保存');
});
it('keeps default platform template only in the plans tab', async () => {
  render(<ModelsPage/>);
  expect(screen.queryByLabelText('默认模板')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('tab', {name: '审核模型与费率'}));
  fireEvent.click(await screen.findByRole('button', {name: '平台默认 AI 模板'}));
  fireEvent.change(await screen.findByLabelText('默认模板'), {target: {value: '未发布'}});
  expect(screen.getByRole('button', {name: '返回聊天'})).toBeDisabled();
});
it('does not require governance menu to manage models and explains a missing menu separately', async () => {
  user = {id: 'operator', menus: []};
  render(<ModelsPage/>);
  await screen.findByLabelText('模型草稿');
  expect(screen.getByRole('button', {name: '工作空间配置'})).toBeDisabled();
  expect(screen.getByText(/当前账号未分配工作空间配置菜单/)).toBeInTheDocument();
});
it('redirects the old workspace-ai deep link preserving full target, without substituting chat scope', () => {
  window.history.replaceState(null, '', '/Environment/Models?tab=workspace-ai&tenantId=tenant&workspaceId=second');
  render(<ModelsPage/>);
  expect(history.replace).toHaveBeenCalledWith(expect.stringContaining('Workspaces?account=operator&tab=ai&tenantId=tenant&workspaceId=second'));
  expect(directory.select).not.toHaveBeenCalled();
});
it('does not fall back to current space for an invalid explicit target', async () => {
  window.history.replaceState(null, '', '/Environment/Models?tenantId=tenant&workspaceId=second');
  vi.mocked(api.getWorkspace).mockRejectedValue(new Error('denied'));
  render(<ModelsPage/>);
  await screen.findByText('指定管理空间不可读或已失效，请重新选择。');
  expect(screen.queryByLabelText('模型草稿')).not.toBeInTheDocument();
});
it.each([[ConnectionsPage, '审核连接'], [ModelPlansPage, '审核模型与费率'], [ModelSharingPage, '模型管理']] as const)(
  'maps compatibility routes to the corresponding unified tab', (Page, name) => {
    render(<Page/>);
    expect(screen.getByRole('tab', {name})).toHaveAttribute('aria-selected', 'true');
  });
it('does not mount configuration before login', () => {
  user = null;
  render(<ModelsPage/>);
  expect(screen.getByText('请登录后管理模型。')).toBeInTheDocument();
});

it('keeps returned execution conditions separate from management and original chat scopes', async () => {
  window.history.replaceState(null, '', '/Environment/Models?tenantId=tenant&workspaceId=second&execution-tenantId=tenant&execution-workspaceId=first&chat-tenantId=tenant&chat-workspaceId=first');
  render(<ModelsPage/>);
  await screen.findByText('模型草稿空间：second');
  expect(screen.getByText('使用条件空间：first')).toBeInTheDocument();
  expect(directory.current?.scope.workspaceId).toBe('first');
  expect(directory.select).not.toHaveBeenCalled();
});

it('opens the default template in a modal and prevents closing an unsaved template', async () => {
  render(<ModelsPage initialTab="plans"/>);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  fireEvent.click(await screen.findByRole('button', {name: '平台默认 AI 模板'}));
  expect(await screen.findByRole('dialog')).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('默认模板'), {target: {value: '未保存'}});
  expect(screen.queryByRole('button', {name: 'Close'})).not.toBeInTheDocument();
  expect(screen.getByRole('button', {name: '返回聊天'})).toBeDisabled();
  fireEvent.change(screen.getByLabelText('默认模板'), {target: {value: ''}});
  fireEvent.click(screen.getByRole('button', {name: 'Close'}));
});
