import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {AiApiError} from '@/services/arte-ai/request';
import * as api from '@/services/platform/modelPlan';
import * as links from '@/services/platform/connection';
import {managedPlan, planCommand, planConnections} from '@root/tests/fixtures/modelPlan';
import {readOperation, saveOperation} from './storage';
import ModelPlanManager from './ModelPlanManager';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/platform/modelPlan', async original => ({
  ...(await original<typeof import('@/services/platform/modelPlan')>()),
  getModelPlans: vi.fn(),
  publishModelPlan: vi.fn()
}));
vi.mock('@/services/platform/connection', async original => ({
  ...(await original<typeof import('@/services/platform/connection')>()),
  getConnections: vi.fn()
}));
let view: api.ModelPlanView;
let connections: links.ConnectionView;
const context = (userId: string) => ({userId, scope: {tenantId: 'control-admin', workspaceId: 'model-plans'}});
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  view = {version: 1, initialized: true, plans: []};
  connections = structuredClone(planConnections);
  vi.mocked(api.getModelPlans).mockImplementation(async () => structuredClone(view));
  vi.mocked(links.getConnections).mockImplementation(async () => structuredClone(connections));
  vi.mocked(api.publishModelPlan).mockImplementation(async command => {
    view = {...view, version: command.expectedVersion + 1, plans: [structuredClone(managedPlan)]};
    connections.version = view.version;
  });
});
afterEach(cleanup);

async function fill() {
  await screen.findByText('尚无审核模型方案，可以从已有审核连接独立创建。');
  fireEvent.click(screen.getByRole('button', {name: '创建模型与费率新版本'}));
  fireEvent.change(screen.getByRole('textbox', {name: '方案标识'}), {target: {value: planCommand.settings.id}});
  fireEvent.change(screen.getByRole('textbox', {name: '方案远端模型名'}), {target: {value: planCommand.settings.remoteModel}});
  fireEvent.mouseDown(screen.getByRole('combobox', {name: '精确审核连接'}));
  fireEvent.click(await screen.findByText('deepseek · reviewed@db-v1'));
  fireEvent.change(screen.getByRole('spinbutton', {name: '输入单价'}), {target: {value: planCommand.settings.inputPerMillion}});
  fireEvent.change(screen.getByRole('spinbutton', {name: '输出单价'}), {target: {value: planCommand.settings.outputPerMillion}});
}

async function create() {
  await fill();
  fireEvent.click(screen.getByRole('button', {name: '发布模型与费率版本'}));
}

it('publishes a new remote model and exact prices independently of existing models', async () => {
  render(<ModelPlanManager userId="alice"/>);
  await create();
  await screen.findByText('模型与费率已发布到数据库，旧版本和预算保持原值。');
  expect(api.publishModelPlan).toHaveBeenCalledWith(planCommand, expect.any(String));
  expect(screen.getByText('new-remote-model · reviewed-new-model@v1')).toBeInTheDocument();
});
it('restores and retries only the original command and key without automatic submission', async () => {
  vi.mocked(api.publishModelPlan).mockRejectedValueOnce(new TypeError('network'));
  render(<ModelPlanManager userId="alice"/>);
  await create();
  await screen.findByRole('button', {name: '重试确认原方案'});
  const original = vi.mocked(api.publishModelPlan).mock.calls[0];
  cleanup();
  render(<ModelPlanManager userId="alice"/>);
  fireEvent.click(await screen.findByRole('button', {name: '重试确认原方案'}));
  await screen.findByText('模型与费率已发布到数据库，旧版本和预算保持原值。');
  expect(vi.mocked(api.publishModelPlan).mock.calls[1]).toEqual(original);
});
it('requires an explicit fresh read after a version conflict and keeps all draft fields', async () => {
  vi.mocked(api.publishModelPlan).mockRejectedValueOnce(new AiApiError(409, {code: 'PLATFORM_VERSION_CONFLICT'}));
  render(<ModelPlanManager userId="alice"/>);
  await create();
  await screen.findByText('目录已变化，草稿已保留。请重新读取并核对，再明确发布。');
  expect(screen.getByRole('button', {name: '发布模型与费率版本'})).toBeDisabled();
  view.version = 7;
  connections.version = 7;
  fireEvent.click(screen.getByRole('button', {name: '重新读取方案与连接'}));
  await screen.findByText(/目录版本 7/);
  expect(screen.getByRole('textbox', {name: '方案远端模型名'})).toHaveValue(planCommand.settings.remoteModel);
  fireEvent.click(screen.getByRole('button', {name: '发布模型与费率版本'}));
  await waitFor(() => expect(api.publishModelPlan).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.publishModelPlan).mock.calls[1][0]).toEqual({...planCommand, expectedVersion: 7});
});
it('blocks new writes before transport when recovery storage fails', async () => {
  render(<ModelPlanManager userId="alice"/>);
  await fill();
  const storage = vi.spyOn(sessionStorage, 'setItem').mockImplementation(() => {
    throw new Error('unavailable');
  });
  try {
    fireEvent.click(screen.getByRole('button', {name: '发布模型与费率版本'}));
    await screen.findByText('恢复记录无法可靠保存或读取，已阻止新操作；请保留本页并检查浏览器存储。');
    expect(api.publishModelPlan).not.toHaveBeenCalled();
  } finally {
    storage.mockRestore();
  }
});
it('does not offer editing on permission denial or a mixed catalog snapshot', async () => {
  vi.mocked(api.getModelPlans).mockRejectedValueOnce(new AiApiError(403, {}));
  render(<ModelPlanManager userId="alice"/>);
  await screen.findByText('只有平台配置管理员可以审核模型与费率；租户和空间角色不代替此资格。');
  expect(screen.queryByRole('button', {name: '发布模型与费率版本'})).not.toBeInTheDocument();
  cleanup();
  connections.version = 2;
  render(<ModelPlanManager userId="alice"/>);
  await screen.findByText('目录已变化，草稿已保留。请重新读取并核对，再明确发布。');
  expect(screen.queryByRole('button', {name: '发布模型与费率版本'})).not.toBeInTheDocument();
});
it('does not allow publishing with an empty or disabled connection catalog', async () => {
  connections.connections[0].state = 'DISABLED';
  render(<ModelPlanManager userId="alice"/>);
  await screen.findByText('请先在审核连接管理发布并启用一个受支持连接，然后重新读取。');
  fireEvent.click(screen.getByRole('button', {name: '创建模型与费率新版本'}));
  expect(screen.getByRole('button', {name: '发布模型与费率版本'})).toBeDisabled();
});
it('does not resubmit an acknowledged save when only the subsequent read fails', async () => {
  vi.mocked(api.getModelPlans).mockResolvedValueOnce(view).mockRejectedValueOnce(new TypeError('network'));
  render(<ModelPlanManager userId="alice"/>);
  await create();
  await screen.findByText('模型与费率已确认发布，目录重读失败；请重新读取，不要重复发布。');
  expect(screen.queryByRole('button', {name: '重试确认原方案'})).not.toBeInTheDocument();
  expect(api.publishModelPlan).toHaveBeenCalledTimes(1);
});
it('isolates account recovery and blocks malformed records rather than dropping them', async () => {
  const original = {key: 'original', request: {scope: context('alice').scope, command: planCommand}};
  saveOperation(context('alice'), 'model-plan-configuration', '', original);
  expect(readOperation(context('bob'), 'model-plan-configuration', '').value).toBeNull();
  const key = sessionStorage.key(0);
  if (!key) throw new Error('Expected saved recovery operation');
  sessionStorage.setItem(key, '{broken');
  render(<ModelPlanManager userId="alice"/>);
  await screen.findByText('恢复记录无法可靠保存或读取，已阻止新操作；请保留本页并检查浏览器存储。');
  expect(sessionStorage.getItem(key)).toBe('{broken');
  expect(api.publishModelPlan).not.toHaveBeenCalled();
});
it('ignores late read responses from an account that has been unmounted', async () => {
  let finish!: (value: api.ModelPlanView) => void;
  vi.mocked(api.getModelPlans).mockImplementationOnce(() => new Promise(resolve => {
    finish = resolve;
  }));
  const {rerender} = render(<ModelPlanManager userId="alice"/>);
  rerender(<ModelPlanManager userId="bob"/>);
  await screen.findByText('尚无审核模型方案，可以从已有审核连接独立创建。');
  await act(async () => finish({version: 99, initialized: true, plans: [managedPlan]}));
  expect(screen.queryByText(/目录版本 99/)).not.toBeInTheDocument();
});
it('keeps a restored operation as an unsafe draft after a definite version conflict', async () => {
  saveOperation(context('alice'), 'model-plan-configuration', '', {
    key: 'original',
    request: {scope: context('alice').scope, command: planCommand}
  });
  vi.mocked(api.publishModelPlan).mockRejectedValueOnce(new AiApiError(409, {code: 'PLATFORM_VERSION_CONFLICT'}));
  const unsafe = vi.fn();
  render(<ModelPlanManager userId="alice" onUnsafeChange={unsafe}/>);
  await screen.findByRole('textbox', {name: '方案远端模型名'});
  fireEvent.click(screen.getByRole('button', {name: '重试确认原方案'}));
  await screen.findByText('目录已变化，草稿已保留。请重新读取并核对，再明确发布。');
  expect(screen.getByRole('textbox', {name: '方案远端模型名'})).toHaveValue(planCommand.settings.remoteModel);
  expect(screen.getByRole('button', {name: '发布模型与费率版本'})).toBeDisabled();
  expect(unsafe).toHaveBeenLastCalledWith(true);
});
it('edits a historical plan into a fresh revision without modifying its connection or other prices', async () => {
  view = {version: 2, initialized: true, plans: [managedPlan]};
  connections.version = 2;
  render(<ModelPlanManager userId="alice"/>);
  fireEvent.click(await screen.findByRole('button', {name: /参考此版本编辑方案/}));
  fireEvent.change(screen.getByRole('spinbutton', {name: '输入单价'}), {target: {value: '3.2'}});
  fireEvent.click(screen.getByRole('button', {name: '发布模型与费率版本'}));
  await waitFor(() => expect(api.publishModelPlan).toHaveBeenCalled());
  expect(api.publishModelPlan).toHaveBeenCalledWith({
    expectedVersion: 2,
    settings: {...planCommand.settings, inputPerMillion: '3.2'}
  }, expect.any(String));
});
