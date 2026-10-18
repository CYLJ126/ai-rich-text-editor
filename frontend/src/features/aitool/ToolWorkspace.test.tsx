import React from 'react';
import {act, cleanup, fireEvent, render, screen, waitFor,} from '@testing-library/react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import ToolWorkspace from './ToolWorkspace';
import {detail, scope, tool, toolDetail} from './fixtures.testdata';
import {getTool, getToolBinding, listToolBindings, listTools, writeToolBinding,} from '@/services/arte-ai/tool';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai/tool', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai/tool')>()),
  getTool: vi.fn(),
  getToolBinding: vi.fn(),
  listToolBindings: vi.fn(),
  listTools: vi.fn(),
  writeToolBinding: vi.fn(),
}));
const response = <T, >(data: T) => ({body: {data}}) as never;
const page = <T, >(records: T[]) =>
  response({
    records,
    nextCursor: null,
    scanned: records.length,
    exhausted: true,
  });
const workspace = {
  scope,
  name: '空间',
  state: 0,
  currentRole: 0,
  version: 0,
} as const;
beforeEach(() => {
  sessionStorage.clear();
  vi.clearAllMocks();
  vi.mocked(listTools).mockResolvedValue(page([tool]));
  vi.mocked(listToolBindings).mockResolvedValue(page([detail.binding]));
  vi.mocked(getTool).mockResolvedValue(response(toolDetail));
  vi.mocked(getToolBinding).mockResolvedValue(response(detail));
});
afterEach(cleanup);
describe('工具工作区接线', () => {
  it('从目录准备默认配置后读取本人当前修订，不需要执行接口', async () => {
    vi.mocked(writeToolBinding).mockResolvedValue(
      response({
        delivery: 'APPLIED',
        outcome: 'APPLIED',
        rejection: 'NONE',
        bindingId: detail.binding.bindingId,
        binding: detail.binding,
      }),
    );
    render(<ToolWorkspace userId="user" workspace={workspace}/>);
    fireEvent.click(
      await screen.findByRole('button', {name: '查看并准备配置'}),
    );
    fireEvent.click(
      await screen.findByRole('button', {name: '准备默认配置'}),
    );
    expect(await screen.findByLabelText('配置名称')).toHaveValue(
      detail.binding.name,
    );
    expect(vi.mocked(writeToolBinding).mock.calls[0][0]).toBe(
      'prepareDefaultBinding',
    );
    expect(JSON.parse(vi.mocked(writeToolBinding).mock.calls[0][1])).toEqual({
      scope,
      tool: tool.tool,
      configuration: {},
    });
    expect(getToolBinding).toHaveBeenCalledWith(
      scope,
      detail.binding.bindingId,
      expect.anything(),
    );
  });
  it('同空间元数据刷新不重置未保存输入，账号清除立即隐藏旧内容', async () => {
    const view = render(<ToolWorkspace userId="user" workspace={workspace}/>);
    fireEvent.click(screen.getByRole('tab', {name: '我的工具配置'}));
    fireEvent.click(await screen.findByRole('button', {name: '管理配置'}));
    const input = await screen.findByLabelText('配置名称');
    fireEvent.change(input, {target: {value: '未保存的草稿'}});
    await act(async () =>
      view.rerender(
        <ToolWorkspace
          userId="user"
          workspace={{
            ...workspace,
            scope: {...scope},
            name: '新名称',
            version: 1,
          }}
        />,
      ),
    );
    expect(input).toHaveValue('未保存的草稿');
    expect(getToolBinding).toHaveBeenCalledTimes(1);
    act(() =>
      window.dispatchEvent(
        new CustomEvent('arte-account-session-cleared', {detail: 'user'}),
      ),
    );
    await waitFor(() =>
      expect(screen.queryByLabelText('配置名称')).not.toBeInTheDocument(),
    );
    expect(screen.getByText(/账号会话已变化/)).toBeInTheDocument();
  });
});
