import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {AiApiError} from '@/services/arte-ai/request';
import * as api from '@/services/platform/modelUsage';
import ModelUsagePreparationPanel from './ModelUsagePreparationPanel';
import {readModelChatHandoff, readModelUsageOperation, saveModelUsageOperation} from './modelUsageRecovery';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/platform/modelUsage', async original => ({
  ...(await original<typeof import('@/services/platform/modelUsage')>()),
  getModelUsagePreparation: vi.fn(), prepareModelUsage: vi.fn()
}));
const scope = {tenantId: 'tenant', workspaceId: 'space'};
const context = {userId: 'user', scope};
const target = {resourceId: 'model', binding: {type: 'binding' as const, id: 'personal-model', version: 'v1'}};
const request = {scope, ...target};
const available: api.ModelUsagePreparation = {
  allowedActions: ['PREPARE_OWN_BUDGET'],
  scope, resource: {tenantId: 'tenant', type: 'model', resourceId: 'model'},
  binding: target.binding, rate: {type: 'rate', id: 'rate', version: 'v1'},
  state: 'PREPARABLE', reason: 'BUDGET_NOT_PREPARED', budgetRefs: [], approvedLimit: {amount: '10', currency: 'CNY'}
};
const ready: api.ModelUsagePreparation = {...available, state: 'READY', reason: 'READY', budgetRefs: ['budget']};
const done = {
  operationId: 'op',
  status: 1 as const,
  delivery: 0 as const,
  value: ready,
  resultVersion: 0,
  acceptedAt: 'now',
  completedAt: 'now'
};
const updated = vi.fn(), unsafe = vi.fn(), navigate = vi.fn();

function panel(value = context) {
  return <ModelUsagePreparationPanel key={JSON.stringify(value)} context={value} target={target}
                                     onUnsafeChange={unsafe} onPrepared={updated} onUseInChat={navigate}/>;
}

beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
  vi.mocked(api.getModelUsagePreparation).mockResolvedValue(available);
  vi.mocked(api.prepareModelUsage).mockResolvedValue(done);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it('only reads on entry, then explicitly prepares and rereads before handing off to chat', async () => {
  vi.mocked(api.getModelUsagePreparation).mockResolvedValueOnce(available).mockResolvedValue(ready);
  render(panel());
  const prepare = await screen.findByRole('button', {name: '准备此模型用于聊天'});
  await waitFor(() => expect(prepare).toBeEnabled());
  expect(api.prepareModelUsage).not.toHaveBeenCalled();
  fireEvent.click(prepare);
  await waitFor(() => expect(updated).toHaveBeenCalledTimes(1));
  expect(navigate).not.toHaveBeenCalled();
  expect(readModelUsageOperation(context).operation).toBeNull();
  fireEvent.click(screen.getByRole('button', {name: '返回聊天并选择此版本'}));
  await waitFor(() => expect(navigate).toHaveBeenCalledTimes(1));
  expect(readModelChatHandoff(context).handoff?.request).toEqual(request);
  expect(api.prepareModelUsage).toHaveBeenCalledTimes(1);
});

it('restores an uncertain write without automatically resending and retries the exact original key', async () => {
  vi.mocked(api.prepareModelUsage).mockRejectedValueOnce(new TypeError('network')).mockResolvedValue(done);
  const view = render(panel());
  await waitFor(() => expect(screen.getByRole('button', {name: '准备此模型用于聊天'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '准备此模型用于聊天'}));
  await screen.findByText(/请求结果暂时无法确认/);
  const saved = readModelUsageOperation(context).operation!;
  view.unmount();
  vi.mocked(api.getModelUsagePreparation).mockResolvedValue(ready);
  render(panel());
  await waitFor(() => expect(screen.getByRole('button', {name: '重试确认原准备操作'})).toBeEnabled());
  expect(api.prepareModelUsage).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', {name: '重试确认原准备操作'}));
  await waitFor(() => expect(updated).toHaveBeenCalled());
  expect(api.prepareModelUsage).toHaveBeenLastCalledWith(saved.request, saved.key);
});

it('persists known success when the reread fails and refresh only reads, without repeating the write', async () => {
  vi.mocked(api.getModelUsagePreparation).mockResolvedValueOnce(available).mockRejectedValueOnce(new TypeError('read'));
  const view = render(panel());
  await waitFor(() => expect(screen.getByRole('button', {name: '准备此模型用于聊天'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '准备此模型用于聊天'}));
  await screen.findByText(/准备已确认，但当前状态读取失败/);
  expect(readModelUsageOperation(context).operation?.phase).toBe('confirmed');
  view.unmount();
  vi.mocked(api.getModelUsagePreparation).mockResolvedValue(ready);
  render(panel());
  await waitFor(() => expect(updated).toHaveBeenCalledTimes(1));
  expect(api.prepareModelUsage).toHaveBeenCalledTimes(1);
});

it('does not submit if storage fails and keeps one original operation for retry', async () => {
  render(panel());
  await waitFor(() => expect(screen.getByRole('button', {name: '准备此模型用于聊天'})).toBeEnabled());
  const storage = vi.spyOn(sessionStorage, 'setItem').mockImplementation(() => {
    throw new Error('quota');
  });
  fireEvent.click(screen.getByRole('button', {name: '准备此模型用于聊天'}));
  await screen.findByText(/无法保存原请求和操作键/);
  expect(api.prepareModelUsage).not.toHaveBeenCalled();
  storage.mockRestore();
  vi.mocked(api.getModelUsagePreparation).mockResolvedValue(ready);
  fireEvent.click(screen.getByRole('button', {name: '重试确认原准备操作'}));
  await waitFor(() => expect(updated).toHaveBeenCalledTimes(1));
});

it('explains missing approved rules and does not offer a funded fallback', async () => {
  vi.mocked(api.getModelUsagePreparation).mockResolvedValue({
    ...available,
    state: 'BLOCKED',
    reason: 'BUDGET_RULE_NOT_CONFIGURED',
    approvedLimit: null
  });
  render(panel());
  await screen.findByText(/当前空间尚未批准此精确费率的额度/);
  expect(screen.getByRole('button', {name: '准备此模型用于聊天'})).toBeDisabled();
  expect(api.prepareModelUsage).not.toHaveBeenCalled();
});

it('rechecks before returning to chat and refuses revoked conditions after an earlier READY', async () => {
  vi.mocked(api.getModelUsagePreparation).mockResolvedValueOnce(ready).mockResolvedValue({
    ...ready,
    state: 'BLOCKED',
    reason: 'CREDENTIAL_STALE',
    budgetRefs: []
  });
  render(panel());
  await waitFor(() => expect(screen.getByRole('button', {name: '返回聊天并选择此版本'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '返回聊天并选择此版本'}));
  await screen.findByText(/凭据已撤销或轮换/);
  expect(navigate).not.toHaveBeenCalled();
  expect(readModelChatHandoff(context).handoff).toBeNull();
});

it('rejects forbidden writes definitively and clears the original pending operation', async () => {
  vi.mocked(api.prepareModelUsage).mockRejectedValue(new AiApiError(403, {code: 'PLATFORM_ACCESS_DENIED'}));
  render(panel());
  await waitFor(() => expect(screen.getByRole('button', {name: '准备此模型用于聊天'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '准备此模型用于聊天'}));
  await screen.findByText(/当前没有此项操作权限/);
  expect(readModelUsageOperation(context).operation).toBeNull();
});

it('ignores late preparation results after account replacement, preserving only the original account recovery', async () => {
  let finish!: (value: typeof done) => void;
  vi.mocked(api.prepareModelUsage).mockReturnValue(new Promise(resolve => {
    finish = resolve;
  }));
  const view = render(panel());
  await waitFor(() => expect(screen.getByRole('button', {name: '准备此模型用于聊天'})).toBeEnabled());
  fireEvent.click(screen.getByRole('button', {name: '准备此模型用于聊天'}));
  await waitFor(() => expect(api.prepareModelUsage).toHaveBeenCalledTimes(1));
  view.rerender(panel({...context, userId: 'other'}));
  await act(async () => finish(done));
  expect(updated).not.toHaveBeenCalled();
  expect(readModelUsageOperation(context).operation).not.toBeNull();
  expect(readModelUsageOperation({...context, userId: 'other'}).operation).toBeNull();
});

it('surfaces corrupted recovery even with no selected model and never resends a confirmed request', async () => {
  saveModelUsageOperation(context, {request, key: 'original', phase: 'confirmed'});
  vi.mocked(api.getModelUsagePreparation).mockResolvedValue(ready);
  render(<ModelUsagePreparationPanel context={context} target={null} onPrepared={updated} onUnsafeChange={unsafe}/>);
  await waitFor(() => expect(updated).toHaveBeenCalled());
  expect(api.prepareModelUsage).not.toHaveBeenCalled();
});
