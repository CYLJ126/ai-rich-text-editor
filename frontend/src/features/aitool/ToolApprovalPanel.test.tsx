import React from 'react';
import {act, cleanup, fireEvent, render as baseRender, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {decideToolApproval, getToolApproval, type ToolApprovalDetail} from '@/services/arte-ai/toolApproval';
import {readValue, storageKey, writeValue} from '@/features/ainew/storage';
import ToolApprovalPanel from './ToolApprovalPanel';
import {ConfigProvider} from 'antd';

const render = (element: React.ReactElement) => baseRender(<ConfigProvider
  theme={{token: {motion: false}}}>{element}</ConfigProvider>);

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai/toolApproval', () => ({getToolApproval: vi.fn(), decideToolApproval: vi.fn()}));
const context = {userId: 'user', scope: {tenantId: 'tenant', workspaceId: 'workspace'}};
const detail: ToolApprovalDetail = {
  approvalId: 'approval',
  invocationId: 'invocation',
  version: '0',
  subjectDigest: 'a'.repeat(64),
  mode: 'REQUESTER_CONFIRMATION',
  state: 'PENDING',
  requesterSubjectId: 'subject',
  approverSubjectIds: [],
  title: '文本字符计数',
  toolVersion: 'v1',
  bindingVersion: '1',
  argumentsJson: '{"precise":0.1234567890123456789,"text":"<script>alert(1)</script>"}',
  configurationJson: '{}',
  sideEffect: 'READ_ONLY',
  expiresAt: '2030-01-01T00:01:30Z',
  executeNotAfter: '2030-01-01T00:02:00Z',
  decidedBy: null,
  decidedAt: null,
  invocationState: 'WAITING_APPROVAL',
  mayDecide: true,
  requester: true,
  serverTime: '2030-01-01T00:00:00Z'
};
const response = <T, >(data: T) => ({body: {data}}) as never;
beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  vi.clearAllMocks();
  vi.mocked(getToolApproval).mockReset().mockResolvedValue(response(detail));
  vi.mocked(decideToolApproval).mockReset();
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

async function approve() {
  await waitFor(() => expect(screen.queryAllByRole('dialog')).toHaveLength(0));
  fireEvent.click(await screen.findByRole('button', {name: '核对并批准'}));
  fireEvent.click(await screen.findByRole('button', {name: /^批\s*准$/}));
  await waitFor(() => expect(screen.queryAllByRole('dialog')).toHaveLength(0));
}

describe('固定操作人工决定', () => {
  it('展示服务端窗口与精确 JSON 原文，参数只作为文本显示', async () => {
    const view = render(<ToolApprovalPanel context={context} approvalId="approval"/>);
    await screen.findByText(detail.argumentsJson);
    expect(screen.getByText('90 秒（以服务端决定为准）')).toBeTruthy();
    expect(view.container.querySelector('script')).toBeNull();
    expect(decideToolApproval).not.toHaveBeenCalled();
  });
  it('业务写入明确展示风险和预期版本，批准前不发送命令', async () => {
    vi.mocked(getToolApproval).mockResolvedValue(response({
      ...detail,
      sideEffect: 'RESOURCE_WRITE',
      title: '更新当前工作空间名称与说明',
      argumentsJson: '{"expectedVersion":"9007199254740993","name":"新名称","description":"说明"}'
    }));
    render(<ToolApprovalPanel context={context} approvalId="approval"/>);
    await screen.findByText('将修改业务资源，请核对目标、全部参数与预期版本');
    expect(screen.getByText(/9007199254740993/)).toBeTruthy();
    expect(screen.queryByText('只读，无业务写入')).toBeNull();
    expect(decideToolApproval).not.toHaveBeenCalled();
  });
  it('决定超时后刷新仍沿用同一决定、版本、摘要和幂等键', async () => {
    vi.mocked(decideToolApproval).mockRejectedValue(new TypeError('network'));
    const first = render(<ToolApprovalPanel context={context} approvalId="approval"/>);
    await approve();
    await screen.findByText(/决定结果尚待确认/);
    const original = vi.mocked(decideToolApproval).mock.calls[0][0];
    expect(original.body.expectedVersion).toBe('0');
    expect(original.body.subjectDigest).toBe(detail.subjectDigest);
    first.unmount();
    render(<ToolApprovalPanel context={context} approvalId="approval"/>);
    fireEvent.click(await screen.findByRole('button', {name: '继续确认原决定'}));
    await waitFor(() => expect(decideToolApproval).toHaveBeenCalledTimes(2));
    expect(vi.mocked(decideToolApproval).mock.calls[1][0]).toEqual(original);
    expect(screen.queryByRole('button', {name: '拒绝本次操作'})).toBeNull();
  });
  it('成功回执只说明批准，随后读取权威状态并清理恢复命令', async () => {
    vi.mocked(getToolApproval).mockResolvedValueOnce(response(detail)).mockResolvedValue(response({
      ...detail,
      state: 'APPROVED',
      version: '1',
      mayDecide: false,
      invocationState: 'QUEUED'
    }));
    vi.mocked(decideToolApproval).mockResolvedValue(response({
      approvalId: 'approval',
      invocationId: 'invocation',
      version: '1',
      state: 'APPROVED',
      invocationState: 'QUEUED',
      decidedAt: '2030-01-01T00:00:01Z'
    }));
    render(<ToolApprovalPanel context={context} approvalId="approval"/>);
    await approve();
    await screen.findByText(/批准决定已提交/);
    expect(screen.getByText(/批准不代表执行成功/)).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('button', {name: '核对并批准'})).toBeNull());
    expect(readValue(storageKey(context, 'tool-approval-approval')).value).toBeNull();
  });
  it('恢复记录损坏时不生成新决定；存储写失败也不发送', async () => {
    writeValue(storageKey(context, 'tool-approval-approval'), {key: 'unknown', body: {scope: context.scope}});
    const bad = render(<ToolApprovalPanel context={context} approvalId="approval"/>);
    await screen.findByText(/无法读取决定恢复记录/);
    expect((screen.getByRole('button', {name: '核对并批准'}) as HTMLButtonElement).disabled).toBe(true);
    bad.unmount();
    sessionStorage.clear();
    render(<ToolApprovalPanel context={context} approvalId="approval"/>);
    await screen.findByRole('button', {name: '核对并批准'});
    const original = sessionStorage;
    vi.stubGlobal('sessionStorage', {
      getItem: original.getItem.bind(original),
      removeItem: original.removeItem.bind(original),
      setItem: () => {
        throw new Error('quota');
      }
    });
    await approve();
    await screen.findByText(/无法保存决定恢复记录/);
    expect(decideToolApproval).not.toHaveBeenCalled();
  });
  it('独立审批的发起者不能批准自己的操作', async () => {
    vi.mocked(getToolApproval).mockResolvedValue(response({
      ...detail,
      mode: 'INDEPENDENT_APPROVAL',
      approverSubjectIds: ['other'],
      mayDecide: false
    }));
    render(<ToolApprovalPanel context={context} approvalId="approval"/>);
    await screen.findByText('等待指定审批主体处理；可在原调用中取消。');
    expect(screen.queryByRole('button', {name: '核对并批准'})).toBeNull();
    expect(decideToolApproval).not.toHaveBeenCalled();
  });
  it('服务端窗口到期后隐藏新决定，保留权威状态刷新入口', async () => {
    vi.mocked(getToolApproval).mockResolvedValue(response({...detail, expiresAt: detail.serverTime}));
    render(<ToolApprovalPanel context={context} approvalId="approval"/>);
    await screen.findByText('决定窗口已结束，请重新读取服务端状态。');
    expect(screen.queryByRole('button', {name: '核对并批准'})).toBeNull();
    expect(screen.queryByRole('button', {name: '拒绝本次操作'})).toBeNull();
    expect(screen.getByRole('button', {name: '重新读取审批'})).toBeTruthy();
    expect(decideToolApproval).not.toHaveBeenCalled();
  });
  it('切换范围后忽略迟到详情，不显示上个空间的参数', async () => {
    let resolve!: (value: never) => void;
    vi.mocked(getToolApproval).mockImplementationOnce(() => new Promise(r => {
      resolve = r;
    }));
    const first = render(<ToolApprovalPanel context={context} approvalId="old"/>);
    first.rerender(<ToolApprovalPanel context={{...context, scope: {...context.scope, workspaceId: 'other'}}}
                                      approvalId="new"/>);
    await screen.findByText(/文本字符计数 \/ v1/);
    await act(async () => resolve(response({...detail, title: '不应显示的旧空间参数'})));
    expect(screen.queryByText('不应显示的旧空间参数')).toBeNull();
  });
});
