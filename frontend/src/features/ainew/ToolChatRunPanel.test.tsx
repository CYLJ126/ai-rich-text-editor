import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor,} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import ToolChatRunPanel from './ToolChatRunPanel';
import {useToolChatRun} from './useToolChatRun';
import {getRunActions} from '@/services/arte-ai/toolChat';
import {
  toolChatAnswer as answer,
  toolChatContext as context,
  toolChatReply as reply,
  toolChatStatus as status,
} from './toolChat.testdata';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('./useToolChatRun', () => ({useToolChatRun: vi.fn()}));
vi.mock('@/services/arte-ai/toolChat', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai/toolChat')>()),
  getRunActions: vi.fn(),
}));
vi.mock('@/features/aitool/ToolApprovalPanel', () => ({
  default: ({invocationId}: { invocationId: string }) => (
    <div>审批原子调用：{invocationId}</div>
  ),
}));
vi.mock('@/features/aitool/ToolReconciliationPanel', () => ({
  default: ({invocationId}: { invocationId: string }) => (
    <div>核对原子调用：{invocationId}</div>
  ),
}));
const renderPanel = (onPrepare = vi.fn()) =>
  render(
    <ToolChatRunPanel
      context={context}
      runId="run"
      onStatus={vi.fn()}
      onSettled={vi.fn()}
      onStop={vi.fn()}
      stopPending={false}
      disabled={false}
      onPrepare={onPrepare}
    />,
  );
const view = () => ({
  status: {...status, state: 'SUCCEEDED' as const, resultAvailable: true},
  answer,
  budgets: [
    {
      runId: 'run',
      currency: 'CNY',
      limit: '1.00',
      charged: '0.100000000000000001',
      held: '0.2',
      available: '0.699999999999999999',
      blocked: false,
      version: 1,
    },
  ],
  events: [],
  text: '',
  error: null,
  paused: false,
  refresh: vi.fn(),
});
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(useToolChatRun).mockReturnValue(view());
  vi.mocked(getRunActions).mockResolvedValue(
    reply({
      runId: 'run',
      text: '原问题',
      answeredAt: status.updatedAt,
      readOnlyBindingIds: ['count'],
      writeBindingIds: ['write'],
    }),
  );
});
afterEach(cleanup);
it('shows authoritative final text and exact per-currency remaining and held amounts', async () => {
  renderPanel();
  expect(screen.getByText('这段文字共 7 个字符。')).toBeVisible();
  fireEvent.click(screen.getByText('过程与本次预算'));
  await waitFor(() =>
    expect(screen.getByText('0.699999999999999999')).toBeVisible(),
  );
  expect(screen.getByText('预留／待核对占用')).toBeVisible();
  expect(screen.getByText('0.2')).toBeVisible();
});
it('rewriting explicitly confirms a historical final-answer snapshot and only prepares a draft', async () => {
  const prepared = vi.fn();
  renderPanel(prepared);
  fireEvent.click(screen.getByRole('button', {name: '只重写回答'}));
  expect(
    (await screen.findAllByText('使用旧回答快照准备改写？')).length,
  ).toBeGreaterThan(0);
  expect(screen.getByText(/未声明资料观测时间/)).toBeInTheDocument();
  expect(prepared).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', {name: '准备草稿'}));
  await waitFor(() =>
    expect(prepared).toHaveBeenCalledWith(
      expect.objectContaining({
        kind: 'rewrite',
        answer: '这段文字共 7 个字符。',
      }),
    ),
  );
});
it('refresh and write reexecution are separate actions and writes go through a new preview flow', async () => {
  const prepared = vi.fn();
  renderPanel(prepared);
  fireEvent.click(screen.getByRole('button', {name: '重新读取最新数据'}));
  expect(
    (await screen.findAllByText('准备重新读取最新资料？')).length,
  ).toBeGreaterThan(0);
  fireEvent.click(screen.getByRole('button', {name: '准备草稿'}));
  await waitFor(() =>
    expect(prepared).toHaveBeenCalledWith(
      expect.objectContaining({kind: 'refresh'}),
    ),
  );
  fireEvent.click(screen.getByRole('button', {name: '重新执行操作…'}));
  expect(
    (await screen.findAllByText('重新执行操作需要重新准备')).length,
  ).toBeGreaterThan(0);
  expect(
    screen.getByRole('link', {name: '前往工具页准备新操作'}),
  ).toHaveAttribute('href', '/AI/Tools');
  expect(prepared).toHaveBeenCalledTimes(1);
});
it('unknown outcomes never expose regeneration and route reconciliation to the actual child', async () => {
  vi.mocked(useToolChatRun).mockReturnValue({
    ...view(),
    status: {
      ...status,
      state: 'PENDING_RECONCILIATION',
      checkpoint: {
        roundIndex: 0,
        phase: 'RECONCILING',
        children: ['write-child'],
      },
    },
    answer: null,
  });
  renderPanel();
  expect(
    screen.queryByRole('button', {name: '重新读取最新数据'}),
  ).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', {name: '查看原子调用核对'}));
  expect(await screen.findByText('核对原子调用：write-child')).toBeVisible();
});
it('waiting approval and durable stop remain visible and use the original child identity', async () => {
  vi.mocked(useToolChatRun).mockReturnValue({
    ...view(),
    status: {
      ...status,
      state: 'WAITING_APPROVAL',
      cancellationRequested: true,
      checkpoint: {
        roundIndex: 0,
        phase: 'WAITING_APPROVAL',
        children: ['approval-child'],
      },
    },
    answer: null,
  });
  renderPanel();
  expect(
    screen.getByRole('button', {name: '停止请求已保存／待确认'}),
  ).toBeDisabled();
  fireEvent.click(
    screen.getByRole('button', {name: '查看此子调用确认／审批'}),
  );
  expect(await screen.findByText('审批原子调用：approval-child')).toBeVisible();
});
