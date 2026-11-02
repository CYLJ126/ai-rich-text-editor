import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {history} from '@umijs/max';
import ModelsPage from './index';
import ConnectionsPage from '@/pages/AI/Connections';
import ModelPlansPage from '@/pages/AI/ModelPlans';
import ModelSharingPage from '@/pages/AI/ModelSharing';
import * as coordination from '@/features/workspace/useWorkspace';
import type {WorkspaceView} from '@/services/platform/workspace';

vi.mock('@umijs/max', () => ({history: {push: vi.fn()}, useModel: () => ({initialState: {currentUser: user}})}));
vi.mock('@/features/workspace/useWorkspace', () => ({
  useWorkspaceDirectory: () => {
    React.useSyncExternalStore(subscribe, () => revision);
    return directory;
  },
  protectWorkspaceChange: vi.fn((_user, _scope, proceed) => proceed()),
  registerWorkspaceGuard: vi.fn(() => () => {
  }),
}));
vi.mock('@/features/ainew/PersonalModelManager', () => ({
  default: ({context, onUnsafeChange}: EditorProps) => <Editor key={JSON.stringify(context)} name="模型草稿"
                                                               context={context} onUnsafeChange={onUnsafeChange}/>,
}));
vi.mock('@/features/ainew/ModelSharingManager', () => ({
  default: ({context, onUnsafeChange}: EditorProps) => <Editor key={JSON.stringify(context)} name="模型授权"
                                                               context={context} onUnsafeChange={onUnsafeChange}/>,
}));
vi.mock('@/features/ainew/ConnectionManager', () => ({
  default: ({onUnsafeChange}: EditorProps) => <Editor name="连接草稿" onUnsafeChange={onUnsafeChange}/>,
}));
vi.mock('@/features/ainew/ModelPlanManager', () => ({
  default: ({onUnsafeChange}: EditorProps) => <Editor name="费率草稿" onUnsafeChange={onUnsafeChange}/>,
}));

type EditorProps = {
  context?: { scope: { tenantId: string; workspaceId: string } };
  onUnsafeChange?: (value: boolean) => void
};
const mounts = new Map<string, number>();

function Editor({name, context, onUnsafeChange}: EditorProps & { name: string }) {
  const [value, setValue] = React.useState('');
  const [pending, setPending] = React.useState(false);
  React.useEffect(() => {
    mounts.set(name, (mounts.get(name) ?? 0) + 1);
  }, [name]);
  React.useEffect(() => {
    onUnsafeChange?.(!!value || pending);
  }, [value, pending, onUnsafeChange]);
  React.useEffect(() => () => onUnsafeChange?.(false), [onUnsafeChange]);
  return <div>
    <input aria-label={name} value={value} onChange={event => setValue(event.target.value)}/>
    <button type="button" onClick={() => setPending(true)}>模拟{name}待确认</button>
    {context && <span>{name}空间：{context.scope.workspaceId}</span>}
  </div>;
}

const spaces: WorkspaceView[] = [
  {scope: {tenantId: 'tenant', workspaceId: 'first'}, name: '空间一', state: 0, currentRole: 0, version: 0},
  {scope: {tenantId: 'tenant', workspaceId: 'second'}, name: '空间二', state: 0, currentRole: 0, version: 0},
];
let user: { id: string } | null;
let directory: Pick<ReturnType<typeof coordination.useWorkspaceDirectory>, 'current' | 'records' | 'loading'
  | 'selecting' | 'saving' | 'pending' | 'recoveryBlocked' | 'unsafeToLeave' | 'search' | 'exhausted'
  | 'cursorExpired' | 'nextCursor' | 'error' | 'warning' | 'select' | 'setSearch' | 'load' | 'loadMore'>;
let revision = 0;
const listeners = new Set<() => void>();
const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};
beforeEach(() => {
  vi.resetAllMocks();
  mounts.clear();
  revision = 0;
  user = {id: 'operator'};
  directory = {
    current: spaces[0], records: spaces.map(space => ({...space, defaultWorkspace: false})),
    loading: false, selecting: false, saving: false, pending: null, recoveryBlocked: false, unsafeToLeave: false,
    search: '', exhausted: true, cursorExpired: false, nextCursor: null, error: null, warning: null,
    select: vi.fn(async scope => {
      directory.current = spaces.find(space => space.scope.workspaceId === scope.workspaceId) ?? null;
      revision++;
      listeners.forEach(listener => {
        listener();
      });
      return !!directory.current;
    }),
    setSearch: vi.fn(async () => {
    }), load: vi.fn(async () => {
    }), loadMore: vi.fn(async () => {
    }),
  };
  vi.mocked(coordination.protectWorkspaceChange).mockImplementation((_user, _scope, proceed) => proceed());
  vi.mocked(coordination.registerWorkspaceGuard).mockImplementation(() => () => {
  });
});
afterEach(cleanup);

it('organizes four tabs and keeps return-to-chat navigation inside the application', () => {
  render(<ModelsPage/>);
  expect(screen.getAllByRole('tab')).toHaveLength(4);
  expect(screen.getByRole('tab', {name: '模型管理'})).toHaveAttribute('aria-selected', 'true');
  expect(screen.getByRole('tab', {name: '租户模型与授权'})).toBeInTheDocument();
  expect(screen.getByRole('tab', {name: '审核连接'})).toBeInTheDocument();
  expect(screen.getByRole('tab', {name: '审核模型与费率'})).toBeInTheDocument();
  expect(screen.getByRole('combobox', {name: '选择工作空间'})).toBeInTheDocument();
  expect(screen.queryByRole('button', {name: '管理审核连接'})).not.toBeInTheDocument();
  expect(mounts.get('连接草稿')).toBeUndefined();
  fireEvent.click(screen.getByRole('button', {name: '返回聊天'}));
  expect(history.push).toHaveBeenCalledWith('/AI/ChatNew');
});

it('keeps drafts mounted when switching tabs and aggregates their leave protection', async () => {
  render(<ModelsPage/>);
  fireEvent.change(screen.getByLabelText('模型草稿'), {target: {value: '模型未保存'}});
  fireEvent.click(screen.getByRole('tab', {name: '审核连接'}));
  fireEvent.change(await screen.findByLabelText('连接草稿'), {target: {value: '连接未保存'}});
  fireEvent.click(screen.getByRole('tab', {name: '模型管理'}));
  expect(screen.getByLabelText('模型草稿')).toHaveValue('模型未保存');
  expect(screen.getByRole('combobox', {name: '选择工作空间'})).toBeDisabled();
  expect(screen.getByRole('button', {name: '返回聊天'})).toBeDisabled();
  expect(mounts.get('模型草稿')).toBe(1);
  fireEvent.click(screen.getByRole('tab', {name: '审核连接'}));
  expect(screen.getByLabelText('连接草稿')).toHaveValue('连接未保存');
});

it('preserves platform drafts across workspace changes while refreshing workspace-scoped managers', async () => {
  render(<ModelsPage/>);
  fireEvent.click(screen.getByRole('tab', {name: '租户模型与授权'}));
  await screen.findByLabelText('模型授权');
  fireEvent.click(screen.getByRole('tab', {name: '审核连接'}));
  fireEvent.change(await screen.findByLabelText('连接草稿'), {target: {value: '连接未保存'}});
  fireEvent.click(screen.getByRole('tab', {name: '审核模型与费率'}));
  fireEvent.change(await screen.findByLabelText('费率草稿'), {target: {value: '费率未保存'}});
  expect(screen.getByRole('combobox', {name: '选择工作空间'})).toBeEnabled();
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '选择工作空间'}));
  fireEvent.click(await screen.findByText('空间二（tenant / second）', {selector: '.ant-select-item-option-content'}));
  await waitFor(() => expect(directory.select).toHaveBeenCalledWith(spaces[1].scope));
  expect(screen.getByLabelText('费率草稿')).toHaveValue('费率未保存');
  fireEvent.click(screen.getByRole('tab', {name: '审核连接'}));
  expect(screen.getByLabelText('连接草稿')).toHaveValue('连接未保存');
  expect(mounts.get('连接草稿')).toBe(1);
  expect(mounts.get('费率草稿')).toBe(1);
  fireEvent.click(screen.getByRole('tab', {name: '模型管理'}));
  expect(screen.getByText('模型草稿空间：second')).toBeInTheDocument();
  expect(mounts.get('模型草稿')).toBe(2);
  expect(mounts.get('模型授权')).toBe(2);
});

it.each([['models', '模型草稿'], ['sharing', '模型授权']] as const)('blocks space changes for a pending %s operation, including external selectors', async (initialTab, name) => {
  render(<ModelsPage initialTab={initialTab}/>);
  fireEvent.click(await screen.findByRole('button', {name: `模拟${name}待确认`}));
  await waitFor(() => expect(screen.getByRole('combobox', {name: '选择工作空间'})).toBeDisabled());
  const proceed = vi.fn();
  vi.mocked(coordination.registerWorkspaceGuard).mock.calls.at(-1)?.[1](spaces[1].scope, proceed);
  expect(proceed).not.toHaveBeenCalled();
  expect(screen.getByRole('button', {name: '返回聊天'})).toBeDisabled();
});

it('opens platform configuration even when no usable workspace is available', async () => {
  directory.current = null;
  directory.records = [];
  directory.error = new Error('空间不可用');
  render(<ModelsPage initialTab="connections"/>);
  expect(await screen.findByLabelText('连接草稿')).toBeInTheDocument();
  expect(mounts.get('模型草稿')).toBeUndefined();
});

it.each([
  [ConnectionsPage, '审核连接'], [ModelPlansPage, '审核模型与费率'], [ModelSharingPage, '租户模型与授权'],
] as const)('keeps the old page route as a direct entry into its configuration tab', (Page, label) => {
  render(<Page/>);
  expect(screen.getByRole('tab', {name: label})).toHaveAttribute('aria-selected', 'true');
  expect(history.push).not.toHaveBeenCalled();
});

it('does not mount any model configuration before login', () => {
  user = null;
  render(<ModelsPage/>);
  expect(screen.getByText('请登录后管理模型。')).toBeInTheDocument();
  expect(mounts.size).toBe(0);
});
