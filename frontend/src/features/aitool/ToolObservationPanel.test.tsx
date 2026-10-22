import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor,} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import ToolObservationPanel, {toolCostLabel} from './ToolObservationPanel';
import {
  getToolStatistics,
  listToolExecutions,
  type ToolExecutionItem,
  type ToolExecutionPage,
  type ToolStatistics,
} from '@/services/arte-ai/toolObservation';
import {currentAiCredential} from '@/services/arte-ai/request';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai/toolObservation', () => ({
  getToolStatistics: vi.fn(),
  listToolExecutions: vi.fn(),
}));
vi.mock('@/services/arte-ai/request', () => ({
  currentAiCredential: vi.fn(() => 'token'),
}));
const context = {
  userId: 'user',
  scope: {tenantId: 'tenant', workspaceId: 'space'},
};
const query = {
  from: '2026-10-21T00:00:00.000Z',
  until: '2026-10-22T00:00:00.000Z',
};
const item = (title = '文本工具'): ToolExecutionItem => ({
  invocationId: title,
  title,
  traceId: 'trace',
  parentExecutionId: null,
  source: 'INDEPENDENT',
  toolId: 'text',
  toolVersion: 'v1',
  bindingId: 'binding',
  providerId: 'local',
  state: 'SUCCEEDED',
  version: '9007199254740993',
  acceptedAt: query.from,
  updatedAt: query.until,
  attemptCount: 1,
  retryCount: 0,
  errorCode: null,
  billingMode: 'FREE',
  cost: {
    state: 'FREE',
    currency: null,
    settledAmount: null,
    reservedAmount: null,
    unknown: false,
  },
  timings: {
    admissionToAttemptMillis: 10,
    approvalWaitMillis: null,
    attemptSpanMillis: 20,
    terminalSpanMillis: 30,
    elapsedMillis: 30,
  },
  inputBytes: 2,
  outputBytes: null,
});
const page = (title = '文本工具'): ToolExecutionPage => ({
  records: [item(title)],
  nextCursor: null,
  exhausted: true,
  scanned: 1,
  scanLimit: 50,
  historicalIncomplete: false,
  observedAt: query.until,
  query,
  scope: 'CURRENT_SUBJECT',
});
const stats = (): ToolStatistics => ({
  query,
  scope: 'CURRENT_SUBJECT',
  observedAt: query.until,
  invocations: 1,
  attempts: 1,
  retries: 0,
  states: {SUCCEEDED: 1} as ToolStatistics['states'],
  determinedTerminals: 1,
  successRate: '100.00',
  freeCalls: 1,
  chargedCalls: 0,
  unknownCostCalls: 0,
  knownZeroCostCalls: 0,
  currencies: [],
  terminalSpanP50Millis: 30,
  terminalSpanP95Millis: 30,
  knownInputBytes: 2,
  unknownInputBytesCalls: 0,
  outputBytes: null,
  scanned: 1,
  scanLimit: 100,
  truncated: false,
  historicalIncomplete: false,
});
const response = <T, >(data: T) => ({body: {data}}) as never;
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(currentAiCredential).mockReturnValue('token');
  vi.mocked(listToolExecutions).mockResolvedValue(response(page()));
  vi.mocked(getToolStatistics).mockResolvedValue(response(stats()));
});
afterEach(cleanup);

it('loads the current workspace summaries and shows unknown metrics explicitly', async () => {
  render(<ToolObservationPanel context={context}/>);
  expect(await screen.findByText('文本工具')).toBeVisible();
  expect(screen.getByText('不计费')).toBeVisible();
  expect(listToolExecutions).toHaveBeenCalledWith(
    context.scope,
    expect.objectContaining({
      from: expect.any(String),
      until: expect.any(String),
    }),
    null,
    expect.objectContaining({signal: expect.any(AbortSignal)}),
  );
  expect(getToolStatistics).toHaveBeenCalledTimes(1);
  expect(screen.getByText('暂无可靠数据')).toBeVisible();
  fireEvent.click(screen.getByRole('button', {name: '查看执行详情'}));
  expect(await screen.findByText('调用编号')).toBeVisible();
  expect(screen.getByText(/不能等同于工具实际执行时间/)).toBeVisible();
});

it('does not turn unknown cost into zero and preserves exact decimal strings', () => {
  const paid = {
    ...item(),
    billingMode: 'CHARGE' as const,
    cost: {
      state: 'PENDING_RECONCILIATION',
      currency: 'CNY',
      settledAmount: null,
      reservedAmount: '0.5',
      unknown: true,
    },
  };
  expect(toolCostLabel(paid)).toBe('费用待确定 · 预留 0.5 CNY');
  expect(
    toolCostLabel({
      ...paid,
      cost: {
        ...paid.cost,
        settledAmount: '9007199254740993.000000000000000001',
        unknown: false,
      },
    }),
  ).toBe('已确定 9007199254740993.000000000000000001 CNY');
  expect(
    toolCostLabel({
      ...paid,
      cost: {...paid.cost, settledAmount: '0', unknown: false},
    }),
  ).toBe('已确定 0 CNY');
  expect(toolCostLabel({
    ...paid,
    cost: {...paid.cost, state: 'SETTLED', reservedAmount: null}
  })).toBe('费用事实不完整，需核对');
});

it('shows truncated samples and incomplete historical coverage', async () => {
  vi.mocked(getToolStatistics).mockResolvedValue(
    response({...stats(), truncated: true, historicalIncomplete: true}),
  );
  render(<ToolObservationPanel context={context}/>);
  expect(await screen.findByText(/统计已截断/)).toBeVisible();
  expect(screen.getByText(/历史记录尚未完整回填/)).toBeVisible();
});

it('continues a filtered empty page using the committed query without replacing statistics', async () => {
  vi.mocked(listToolExecutions)
    .mockResolvedValueOnce(
      response({
        ...page(),
        records: [],
        nextCursor: 'cursor-one',
        exhausted: false,
      }),
    )
    .mockResolvedValueOnce(response(page('下一页')));
  render(<ToolObservationPanel context={context}/>);
  const next = await screen.findByRole('button', {name: '下一页记录'});
  await waitFor(() => expect(next).toBeEnabled());
  const originalQuery = vi.mocked(listToolExecutions).mock.calls[0][1];
  fireEvent.click(next);
  expect(await screen.findByText('下一页')).toBeVisible();
  expect(vi.mocked(listToolExecutions).mock.calls[1][1]).toEqual(originalQuery);
  expect(vi.mocked(listToolExecutions).mock.calls[1][2]).toBe('cursor-one');
  expect(getToolStatistics).toHaveBeenCalledTimes(1);
});

it('aborts the old scope and never paints its delayed response in the new workspace', async () => {
  let resolveOld!: (value: never) => void;
  vi.mocked(listToolExecutions)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
    )
    .mockResolvedValueOnce(response(page('新空间工具')));
  const view = render(<ToolObservationPanel context={context}/>);
  await waitFor(() => expect(listToolExecutions).toHaveBeenCalledTimes(1));
  const oldSignal = vi.mocked(listToolExecutions).mock.calls[0][3]?.signal;
  view.rerender(
    <ToolObservationPanel
      context={{
        ...context,
        scope: {...context.scope, workspaceId: 'new-space'},
      }}
    />,
  );
  expect(await screen.findByText('新空间工具')).toBeVisible();
  expect(oldSignal?.aborted).toBe(true);
  await act(async () => resolveOld(response(page('旧空间秘密'))));
  expect(screen.queryByText('旧空间秘密')).not.toBeInTheDocument();
});

it('clears records and remains refreshable when credentials change during a request', async () => {
  let finish!: (value: never) => void;
  vi.mocked(listToolExecutions).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  render(<ToolObservationPanel context={context}/>);
  await waitFor(() => expect(listToolExecutions).toHaveBeenCalledTimes(1));
  vi.mocked(currentAiCredential).mockReturnValue('rotated');
  await act(async () => finish(response(page('旧凭据内容'))));
  expect(await screen.findByText('账号凭据已变化，请重新查询。')).toBeVisible();
  expect(screen.queryByText('旧凭据内容')).not.toBeInTheDocument();
  expect(screen.getByRole('button', {name: '最近 24 小时'})).toBeEnabled();
});

it('does not present API failure as an empty successful report', async () => {
  vi.mocked(getToolStatistics).mockRejectedValue(new Error('denied'));
  render(<ToolObservationPanel context={context}/>);
  expect(await screen.findByText(/无法读取执行记录/)).toBeVisible();
  expect(screen.queryByText('当前窗口样本统计')).not.toBeInTheDocument();
});
