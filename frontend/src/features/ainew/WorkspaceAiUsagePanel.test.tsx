import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {history} from '@umijs/max';
import {getWorkspaceAi} from '@/services/platform/ai';
import {getModelUsagePreparation, prepareModelUsage} from '@/services/platform/modelUsage';
import WorkspaceAiUsagePanel from './WorkspaceAiUsagePanel';
import {notifyAiConfiguration} from '@/features/workspace/aiConfigurationEvents';

vi.mock('@umijs/max', () => ({history: {push: vi.fn()}, useModel: () => ({initialState: {currentUser: {menus}}})}));
vi.mock('@/services/platform/ai', async original => ({
  ...await original<typeof import('@/services/platform/ai')>(),
  getWorkspaceAi: vi.fn()
}));
vi.mock('@/services/platform/modelUsage', async original => ({
  ...await original<typeof import('@/services/platform/modelUsage')>(),
  getModelUsagePreparation: vi.fn(),
  prepareModelUsage: vi.fn()
}));
const scope = {tenantId: 't', workspaceId: 'A'}, context = {userId: 'alice', scope};
const model = {resourceId: 'r', binding: {type: 'binding', id: 'b', version: 'v1'}, name: 'My model', kind: 'PERSONAL'};
const state = {
  scope, resource: {tenantId: 't', type: 'model', resourceId: 'r'}, binding: model.binding,
  state: 'PREPARABLE', reason: 'BUDGET_NOT_PREPARED', allowedActions: ['PREPARE_OWN_BUDGET'], budgetRefs: []
};
let menus: string[], canManage: boolean;
const prepared = vi.fn();
beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.clear();
  menus = [];
  canManage = false;
  vi.mocked(getWorkspaceAi).mockImplementation(async () => ({
    body: {
      data: {
        models: [model],
        canManage,
        configuration: null
      }
    }
  }) as never);
  vi.mocked(getModelUsagePreparation).mockResolvedValue(state as never);
  vi.mocked(prepareModelUsage).mockResolvedValue({value: {...state, state: 'READY', reason: 'READY'}} as never);
});
afterEach(cleanup);

function panel() {
  return <WorkspaceAiUsagePanel context={context} disabled={false} onUnsafeChange={vi.fn()} onPrepared={prepared}
                                onNavigate={fn => fn()}/>;
}

async function choose() {
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '检查模型使用条件'}));
  fireEvent.click(await screen.findByText('My model · 个人 · b@v1'));
}

it('lets an ordinary user explicitly prepare own budget without menu or governance forms', async () => {
  render(panel());
  await waitFor(() => expect(getWorkspaceAi).toHaveBeenCalled());
  await choose();
  const button = await screen.findByRole('button', {name: '准备此模型用于聊天'});
  await waitFor(() => expect(button).toBeEnabled());
  expect(prepareModelUsage).not.toHaveBeenCalled();
  vi.mocked(getModelUsagePreparation).mockResolvedValue({...state, state: 'READY', reason: 'READY'} as never);
  fireEvent.click(button);
  await waitFor(() => expect(prepared).toHaveBeenCalled());
  expect(prepareModelUsage).toHaveBeenCalledWith({scope, resourceId: 'r', binding: model.binding}, expect.any(String));
  expect(screen.queryByRole('button', {name: '保存空间 AI 配置'})).not.toBeInTheDocument();
  expect(screen.queryByRole('button', {name: '配置此空间 AI'})).not.toBeInTheDocument();
  expect(history.push).not.toHaveBeenCalled();
});
it('distinguishes missing menu from governance denial and allows qualified scoped jump', async () => {
  canManage = true;
  const view = render(panel());
  await screen.findByText(/尚未分配工作空间配置菜单/);
  view.unmount();
  menus = ['EnvironmentWorkspaces'];
  render(panel());
  fireEvent.click(await screen.findByRole('button', {name: '配置此空间 AI'}));
  expect(history.push).toHaveBeenCalledWith(expect.stringContaining('workspaceId=A'));
  expect(prepareModelUsage).not.toHaveBeenCalled();
});
it('reacts only to current account and full scope notifications with reads, never writes', async () => {
  render(panel());
  await waitFor(() => expect(getWorkspaceAi).toHaveBeenCalledTimes(1));
  act(() => notifyAiConfiguration('bob', scope));
  act(() => notifyAiConfiguration('alice', {...scope, workspaceId: 'B'}));
  expect(getWorkspaceAi).toHaveBeenCalledTimes(1);
  act(() => notifyAiConfiguration('alice', scope));
  await waitFor(() => expect(getWorkspaceAi).toHaveBeenCalledTimes(2));
  expect(prepareModelUsage).not.toHaveBeenCalled();
});
