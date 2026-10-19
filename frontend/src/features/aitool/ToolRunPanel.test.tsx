import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor,} from '@testing-library/react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import ToolRunPanel from './ToolRunPanel';
import {detail, scope, toolDetail} from './fixtures.testdata';
import {getTool, getToolBinding} from '@/services/arte-ai/tool';
import {invokeTool, previewToolInvocation,} from '@/services/arte-ai/toolInvocation';
import {getInvocationResult, getInvocationStatus} from '@/services/arte-ai';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai/tool', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai/tool')>()),
  getTool: vi.fn(),
  getToolBinding: vi.fn(),
}));
vi.mock('@/services/arte-ai/toolInvocation', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai/toolInvocation')>()),
  previewToolInvocation: vi.fn(),
  invokeTool: vi.fn(),
}));
vi.mock('@/services/arte-ai', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai')>()),
  getInvocationStatus: vi.fn(),
  getInvocationResult: vi.fn(),
}));
const response = <T, >(data: T) => ({body: {data}}) as never;
const plan = {
  planDigest: 'a'.repeat(64),
  title: '验收工具',
  tool: toolDetail.tool.tool,
  revision: {version: 'r1'},
  billingMode: 'FREE',
  approvalMode: 'NONE',
  argumentsJson: '{}',
  configurationJson: '{}',
  timeoutMillis: 30000,
  maxAttempts: 1,
  maxOutputBytes: 4096,
  costDescription: '免费，不扣减聊天预算',
};
beforeEach(() => {
  sessionStorage.clear();
  vi.clearAllMocks();
  vi.mocked(getToolBinding).mockResolvedValue(response(detail));
  vi.mocked(getTool).mockResolvedValue(response(toolDetail));
  vi.mocked(previewToolInvocation).mockResolvedValue(response(plan));
  vi.mocked(invokeTool).mockResolvedValue(
    response({
      executionId: 'original',
      kind: 'INVOCATION',
      acceptedAt: 'now',
    }),
  );
  vi.mocked(getInvocationStatus).mockResolvedValue(
    response({
      invocationId: 'original',
      kind: 'TOOL',
      state: 'SUCCEEDED',
      resultAvailable: true,
      budgetState: 'NOT_RESERVED',
    }),
  );
  vi.mocked(getInvocationResult).mockResolvedValue(
    response({
      invocationId: 'original',
      kind: 'TOOL',
      result: {
        value: {
          callId: 'call',
          outcome: 'SUCCEEDED',
          sideEffect: 'NONE',
          sources: [],
          artifacts: [],
        },
      },
      outputJson:
        '{"integer":9007199254740993,"html":"<script>alert(1)</script>"}',
    }),
  );
});
afterEach(cleanup);
const show = () =>
  render(
    <ToolRunPanel
      context={{userId: 'user', scope}}
      bindings={[detail.binding]}
      selectedBindingId={detail.binding.bindingId}
      onDirty={vi.fn()}
    />,
  );
describe('工具调用预览和结果页面', () => {
  it('预览不提交；修改期限撤销确认方案', async () => {
    show();
    await waitFor(() => expect(getTool).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', {name: '预览调用'}));
    expect(
      await screen.findByRole('button', {name: '确认并执行工具'}),
    ).toBeEnabled();
    expect(invokeTool).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('调用总期限'), {
      target: {value: '20'},
    });
    expect(screen.queryByRole('button', {name: '确认并执行工具'})).toBeNull();
  });
  it('确认后跟踪同一个调用，结果保留精度且按纯文本显示', async () => {
    const view = show();
    await waitFor(() => expect(getTool).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', {name: '预览调用'}));
    fireEvent.click(
      await screen.findByRole('button', {name: '确认并执行工具'}),
    );
    const result = await screen.findByLabelText('工具结构化结果');
    expect(result).toHaveTextContent('9007199254740993');
    expect(result).toHaveTextContent('<script>alert(1)</script>');
    expect(view.container.querySelector('script')).toBeNull();
    expect(screen.getByText('执行成功')).toBeTruthy();
    expect(vi.mocked(invokeTool).mock.calls[0][0]).toContain(
      '"expectedPlanDigest"',
    );
  });
});
