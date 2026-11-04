import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import * as api from '@/services/platform/ai';
import DefaultAiTemplateEditor from './DefaultAiTemplateEditor';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/platform/ai', async original => ({
  ...(await original<typeof import('@/services/platform/ai')>()),
  getAiCatalog: vi.fn(), initializeAiCatalog: vi.fn(), updateDefaultAiPolicy: vi.fn(),
  getWorkspaceAi: vi.fn(), updateWorkspaceAi: vi.fn()
}));
const scope = {tenantId: 'tenant', workspaceId: 'space'};
const context = {userId: 'user', scope};
const rate = {type: 'rate', id: 'price', version: 'v1'};
const response = <T, >(data: T) => ({httpStatus: 200, body: {success: true as const, code: '200', desc: 'OK', data}});
beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
});
afterEach(cleanup);

const originalCatalog: api.AiCatalog = {
  reviewedPlans: [], connections: [],
  rates: [{
    definition: rate,
    inputPerMillion: {amount: '1', currency: 'CNY'},
    outputPerMillion: {amount: '2', currency: 'CNY'}
  }],
  defaults: {enabled: true, rateCaps: [{rate, limit: {amount: '100', currency: 'CNY'}}]}
};

it('exposes explicit initial import to a platform administrator without requiring a public space', async () => {
  vi.mocked(api.getAiCatalog).mockResolvedValue(response({version: 0, catalog: null}));
  vi.mocked(api.initializeAiCatalog).mockResolvedValue(response({
    operationId: 'import', status: 1, delivery: 0,
    value: {version: 1, catalog: null}, resultVersion: 1, acceptedAt: 'now', completedAt: 'now'
  }));
  const updated = vi.fn();
  render(<DefaultAiTemplateEditor context={context} onUpdated={updated}/>);
  expect(api.getWorkspaceAi).not.toHaveBeenCalled();
  fireEvent.click(await screen.findByRole('button', {name: '初始化空目录'}));
  await waitFor(() => expect(api.initializeAiCatalog).toHaveBeenCalledTimes(1));
  expect(updated).toHaveBeenCalled();
});

it('publishes only default switch and rate caps and retries an uncertain operation with the same request and key', async () => {
  vi.mocked(api.getAiCatalog).mockResolvedValue(response({version: 1, catalog: structuredClone(originalCatalog)}));
  vi.mocked(api.updateDefaultAiPolicy).mockRejectedValueOnce(new TypeError('uncertain'));
  render(<DefaultAiTemplateEditor context={context} onUpdated={vi.fn()}/>);
  expect(await screen.findByRole('button', {name: '发布默认 AI 模板'})).toBeInTheDocument();
  expect(screen.queryByRole('button', {name: '发布新模型'})).not.toBeInTheDocument();
  expect(screen.queryByLabelText('新空间默认模型')).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('默认预算额度 price'), {target: {value: '80'}});
  fireEvent.click(screen.getByRole('button', {name: '发布默认 AI 模板'}));
  await waitFor(() => expect(api.updateDefaultAiPolicy).toHaveBeenCalledTimes(1));
  const original = vi.mocked(api.updateDefaultAiPolicy).mock.calls[0];
  expect(original[0]).toEqual({
    expectedCatalogVersion: 1,
    enabled: true,
    rateCaps: [{rate, limit: {amount: '80', currency: 'CNY'}}]
  });
  expect(api.updateWorkspaceAi).not.toHaveBeenCalled();
  await screen.findByText('保存结果待确认，请保留本页并按原操作重试。');
  cleanup();
  render(<DefaultAiTemplateEditor context={context} onUpdated={vi.fn()}/>);
  fireEvent.click(await screen.findByRole('button', {name: '重试确认原配置操作'}));
  await waitFor(() => expect(api.updateDefaultAiPolicy).toHaveBeenCalledTimes(2));
  expect(vi.mocked(api.updateDefaultAiPolicy).mock.calls[1]).toEqual(original);
});

it('does not publish when the original command cannot be stored', async () => {
  vi.mocked(api.getAiCatalog).mockResolvedValue(response({version: 1, catalog: structuredClone(originalCatalog)}));
  render(<DefaultAiTemplateEditor context={context} onUpdated={vi.fn()}/>);
  await screen.findByRole('button', {name: '发布默认 AI 模板'});
  const storage = vi.spyOn(sessionStorage, 'setItem').mockImplementation(() => {
    throw new Error('storage unavailable');
  });
  try {
    fireEvent.click(screen.getByRole('button', {name: '发布默认 AI 模板'}));
    await screen.findByText('无法保存原请求和操作键，本次未提交。恢复浏览器存储后确认原操作。');
    expect(api.updateDefaultAiPolicy).not.toHaveBeenCalled();
  } finally {
    storage.mockRestore();
  }
});

it('after confirmed policy success and a failed reread, remount confirmation only rereads', async () => {
  vi.mocked(api.getAiCatalog).mockResolvedValueOnce(response({version: 1, catalog: structuredClone(originalCatalog)}))
    .mockRejectedValueOnce(new TypeError('read unavailable'));
  vi.mocked(api.updateDefaultAiPolicy).mockResolvedValue(response({
    operationId: 'policy',
    status: 1,
    delivery: 0,
    value: {version: 2, catalog: structuredClone(originalCatalog)},
    resultVersion: 2,
    acceptedAt: 'now',
    completedAt: 'now'
  }));
  render(<DefaultAiTemplateEditor context={context} onUpdated={vi.fn()}/>);
  fireEvent.click(await screen.findByRole('button', {name: '发布默认 AI 模板'}));
  await screen.findByText('默认模板已确认，但当前目录读取失败。确认原操作只会重新读取，不再次发布。');
  cleanup();
  vi.mocked(api.getAiCatalog).mockResolvedValue(response({version: 2, catalog: structuredClone(originalCatalog)}));
  render(<DefaultAiTemplateEditor context={context} onUpdated={vi.fn()}/>);
  fireEvent.click(await screen.findByRole('button', {name: '重试确认原配置操作'}));
  await waitFor(() => expect(screen.queryByRole('button', {name: '重试确认原配置操作'})).not.toBeInTheDocument());
  expect(api.updateDefaultAiPolicy).toHaveBeenCalledTimes(1);
});
