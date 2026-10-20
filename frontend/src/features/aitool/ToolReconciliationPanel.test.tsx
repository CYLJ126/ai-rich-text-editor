import React from 'react';
import {act, cleanup, fireEvent, render as baseRender, screen, waitFor,} from '@testing-library/react';
import {ConfigProvider} from 'antd';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import ToolReconciliationPanel from './ToolReconciliationPanel';
import {
  getToolOutcomeEvidence,
  getToolReconciliation,
  sendToolReview,
  type ToolReviewDetail,
} from '@/services/arte-ai/toolReconciliation';
import {AiApiError} from '@/services/arte-ai';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai/toolReconciliation', () => ({
  getToolOutcomeEvidence: vi.fn(),
  getToolReconciliation: vi.fn(),
  sendToolReview: vi.fn(),
}));
const context = {
  userId: 'account',
  scope: {tenantId: 'tenant', workspaceId: 'space'},
};
const detail: ToolReviewDetail = {
  invocationId: 'original',
  attemptId: 'attempt',
  operationKey: 'operation',
  version: '9007199254740993',
  invocationVersion: '9007199254740995',
  state: 'NEEDS_REVIEW',
  invocationState: 'UNKNOWN',
  title: '空间改名',
  argumentsJson: '{"name":"<script>unsafe()</script>"}',
  requesterSubjectId: 'other-subject',
  checks: 6,
  maxChecks: 6,
  createdAt: '2030-01-01T00:00:00Z',
  nextCheckAt: '2030-01-01T00:01:00Z',
  autoExpiresAt: '2030-01-01T00:30:00Z',
  lastCode: 'OUTCOME_LIMIT_REACHED',
  responsibleSubjectId: 'reviewer',
  nextStep: '请联系原业务责任方',
  dueAt: '2030-01-02T00:00:00Z',
  unassigned: false,
  overdue: true,
  mayConfirm: true,
  mayClaim: true,
  evidenceSupported: true,
  serverTime: '2030-01-03T00:00:00Z',
  history: [],
};
const response = <T, >(data: T) => ({body: {data}}) as never;
const render = (element: React.ReactElement) =>
  baseRender(
    <ConfigProvider theme={{token: {motion: false}}}>
      {element}
    </ConfigProvider>,
  );
beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  vi.clearAllMocks();
  vi.mocked(getToolReconciliation).mockResolvedValue(response(detail));
  vi.mocked(getToolOutcomeEvidence).mockResolvedValue(
    response({
      decision: 'CONFIRMED_SUCCEEDED',
      reference: 'original-receipt',
      digest: 'a'.repeat(64),
      sideEffect: 'CONFIRMED',
      outputJson: '{"version":"9007199254740993"}',
    }),
  );
  vi.mocked(sendToolReview).mockReset();
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

async function fillAndClaim() {
  await screen.findByText('空间改名');
  fireEvent.change(screen.getByLabelText('核对原因'), {
    target: {value: '核验原操作'},
  });
  fireEvent.change(screen.getByLabelText('核对下一步'), {
    target: {value: '联系领域责任方'},
  });
  fireEvent.click(screen.getByRole('button', {name: '认领给本人'}));
  fireEvent.click(await screen.findByRole('button', {name: '确认保存'}));
}

describe('人工业务结果核对', () => {
  it('展示 UNKNOWN、责任方与超期；普通读取不显示人工写入口', async () => {
    render(
      <ToolReconciliationPanel context={context} invocationId="original"/>,
    );
    await screen.findByText('空间改名');
    expect(
      screen.getByText('此项核对已超期，请联系当前责任方处理'),
    ).toBeTruthy();
    expect(screen.queryByRole('button', {name: '认领给本人'})).toBeNull();
    expect(sendToolReview).not.toHaveBeenCalled();
  });
  it('网络不明后刷新恢复同一命令及精确版本，不另发新决定', async () => {
    vi.mocked(sendToolReview).mockRejectedValue(new TypeError('network'));
    const view = render(
      <ToolReconciliationPanel
        context={context}
        invocationId="original"
        managed
      />,
    );
    await fillAndClaim();
    await screen.findByText('原核对命令尚待确认');
    const original = vi.mocked(sendToolReview).mock.calls[0][0];
    expect(JSON.parse(original.requestJson).expectedVersion).toBe(
      '9007199254740993',
    );
    expect(JSON.parse(original.requestJson).expectedInvocationVersion).toBe(
      '9007199254740995',
    );
    view.unmount();
    render(
      <ToolReconciliationPanel
        context={context}
        invocationId="original"
        managed
      />,
    );
    await screen.findByText('空间改名');
    fireEvent.click(screen.getByRole('button', {name: '确认原核对命令'}));
    await waitFor(() => expect(sendToolReview).toHaveBeenCalledTimes(2));
    expect(vi.mocked(sendToolReview).mock.calls[1][0]).toEqual(original);
  });
  it('没有权威回执时不提供成功确认，无法判定不会显示重执行', async () => {
    vi.mocked(getToolOutcomeEvidence).mockResolvedValue(
      response({
        decision: 'STILL_UNKNOWN',
        reference: null,
        digest: null,
        sideEffect: null,
        outputJson: null,
      }),
    );
    render(
      <ToolReconciliationPanel
        context={context}
        invocationId="original"
        managed
      />,
    );
    fireEvent.click(
      await screen.findByRole('button', {name: '核验原领域回执'}),
    );
    await screen.findByText('未找到可确认的原回执，不能据此声明未执行');
    expect(screen.queryByRole('button', {name: '确认原操作证据'})).toBeNull();
    expect(screen.queryByText('重执行')).toBeNull();
    expect(sendToolReview).not.toHaveBeenCalled();
  });
  it('存储失败时不会发送核对命令', async () => {
    render(
      <ToolReconciliationPanel
        context={context}
        invocationId="original"
        managed
      />,
    );
    const original = sessionStorage;
    vi.stubGlobal('sessionStorage', {
      getItem: original.getItem.bind(original),
      removeItem: original.removeItem.bind(original),
      setItem: () => {
        throw new Error('quota');
      },
    });
    await fillAndClaim();
    await screen.findByText('无法保存命令恢复记录，尚未发送。');
    expect(sendToolReview).not.toHaveBeenCalled();
  });
  it('拒绝自审的服务端能力不会被普通管理员界面推断覆盖', async () => {
    vi.mocked(getToolReconciliation).mockResolvedValue(
      response({...detail, mayConfirm: false, mayClaim: false}),
    );
    render(
      <ToolReconciliationPanel
        context={context}
        invocationId="original"
        managed
      />,
    );
    await screen.findByText('空间改名');
    expect(screen.queryByRole('button', {name: '认领给本人'})).toBeNull();
    expect(screen.queryByRole('button', {name: '核验原领域回执'})).toBeNull();
  });
  it('切换空间后忽略旧请求，旧参数作为文本且不会执行脚本', async () => {
    let resolve: (value: never) => void = () => {
    };
    vi.mocked(getToolReconciliation).mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
    const view = render(
      <ToolReconciliationPanel
        context={context}
        invocationId="original"
        managed
      />,
    );
    view.rerender(
      <ToolReconciliationPanel
        context={{
          ...context,
          scope: {...context.scope, workspaceId: 'other'},
        }}
        invocationId="new"
      />,
    );
    await screen.findByText('空间改名');
    await act(async () =>
      resolve(response({...detail, title: '旧空间信息'})),
    );
    expect(screen.queryByText('旧空间信息')).toBeNull();
    expect(view.container.querySelector('script')).toBeNull();
  });

  it('确认只引用本次权威材料，并保留服务端字符串版本与人工核验记录', async () => {
    vi.mocked(sendToolReview).mockResolvedValue(
      response({outcome: 'APPLIED', invocationState: 'SUCCEEDED'}),
    );
    render(
      <ToolReconciliationPanel
        context={context}
        invocationId="original"
        managed
      />,
    );
    fireEvent.click(
      await screen.findByRole('button', {name: '核验原领域回执'}),
    );
    await screen.findByText('已读取原领域回执，请人工核验后确认');
    fireEvent.change(screen.getByLabelText('核对原因'), {
      target: {value: '已核对原领域回执'},
    });
    fireEvent.change(screen.getByLabelText('核对下一步'), {
      target: {value: '通知发起者查看原结果'},
    });
    fireEvent.change(screen.getByLabelText('证据核验记录'), {
      target: {value: '原主体、Scope、参数与结果全部匹配'},
    });
    fireEvent.click(screen.getByRole('button', {name: '确认原操作证据'}));
    fireEvent.click(await screen.findByRole('button', {name: '确认保存'}));
    await screen.findByText('命令已保存；原调用状态：SUCCEEDED。');
    const op = vi.mocked(sendToolReview).mock.calls[0][0],
      body = JSON.parse(op.requestJson);
    expect(op.path).toBe('confirmOutcome');
    expect(body.expectedVersion).toBe(detail.version);
    expect(body.evidence.reference).toBe('original-receipt');
    expect(body.evidence.source).toBe('DOMAIN_RECEIPT');
    expect(body.evidence.outputJson).toBe('{"version":"9007199254740993"}');
  });
  it('明确的版本拒绝要求刷新后重核，不把已拒绝命令锁成永远不明', async () => {
    vi.mocked(sendToolReview).mockRejectedValue(
      new AiApiError(409, {code: '205030'}),
    );
    render(
      <ToolReconciliationPanel
        context={context}
        invocationId="original"
        managed
      />,
    );
    await fillAndClaim();
    await screen.findByText(
      '服务端已拒绝本次命令；请刷新原记录，重新核验证据。',
    );
    expect(screen.queryByRole('button', {name: '确认原核对命令'})).toBeNull();
    expect(screen.queryByRole('button', {name: '认领给本人'})).toBeNull();
    fireEvent.click(screen.getByRole('button', {name: '刷新核对进度'}));
    await screen.findByRole('button', {name: '认领给本人'});
    expect(sendToolReview).toHaveBeenCalledTimes(1);
  });
});
