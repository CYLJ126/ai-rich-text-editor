import React from 'react';
import {cleanup, fireEvent, render, screen, waitFor,} from '@testing-library/react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import BindingEditor from './BindingEditor';
import ToolDetailPanel from './ToolDetailPanel';
import {detail, scope, tool, toolDetail} from './fixtures.testdata';
import {getTool, getToolBinding, previewToolUpgrade,} from '@/services/arte-ai/tool';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
vi.mock('@/services/arte-ai/tool', async (original) => ({
  ...(await original<typeof import('@/services/arte-ai/tool')>()),
  getTool: vi.fn(),
  getToolBinding: vi.fn(),
  previewToolUpgrade: vi.fn(),
}));
const response = <T, >(data: T) => ({body: {data}}) as never;
beforeEach(() => {
  vi.mocked(getTool).mockResolvedValue(response(toolDetail));
  vi.mocked(getToolBinding).mockResolvedValue(response(detail));
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});
describe('工具目录和个人配置交互', () => {
  it('默认入口不要求理解技术配置，高级内容折叠且准备不执行工具', async () => {
    const prepare = vi.fn();
    render(
      <ToolDetailPanel
        scope={scope}
        tool={tool}
        disabled={false}
        onPrepare={prepare}
        onDirty={vi.fn()}
      />,
    );
    await waitFor(() =>
      expect(
        screen.getByRole('button', {name: '准备默认配置'}),
      ).toBeEnabled(),
    );
    expect(screen.queryByLabelText('完整配置 JSON')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', {name: '准备默认配置'}));
    expect(prepare).toHaveBeenCalledWith('{}');
  });
  it('缺配置时展开业务字段，保留原参数草稿', async () => {
    render(
      <ToolDetailPanel
        scope={scope}
        tool={tool}
        disabled={false}
        configurationError
        onPrepare={vi.fn()}
        onDirty={vi.fn()}
      />,
    );
    expect(await screen.findByLabelText('统计说明')).toBeInTheDocument();
  });
  it('保存名称时保留精确配置数字和原版本字符串', async () => {
    const write = vi.fn();
    render(
      <BindingEditor
        scope={scope}
        binding={detail.binding}
        tools={[tool]}
        disabled={false}
        onDirty={vi.fn()}
        onWrite={write}
      />,
    );
    const name = await screen.findByLabelText('配置名称');
    await screen.findByLabelText('统计说明');
    fireEvent.change(name, {target: {value: '修改后的名称'}});
    fireEvent.click(screen.getByRole('button', {name: '保存配置'}));
    expect(write).toHaveBeenCalledOnce();
    expect(write.mock.calls[0][0]).toBe('saveBinding');
    expect(write.mock.calls[0][1]).toContain('0.123456789012345678901');
    expect(JSON.parse(write.mock.calls[0][1]).expectedVersion).toBe(
      '9007199254740993',
    );
  });
  it('启停使用原治理版本，未保存输入时不允许意外启停', async () => {
    const write = vi.fn();
    render(
      <BindingEditor
        scope={scope}
        binding={detail.binding}
        tools={[tool]}
        disabled={false}
        onDirty={vi.fn()}
        onWrite={write}
      />,
    );
    const name = await screen.findByLabelText('配置名称');
    fireEvent.click(screen.getByRole('button', {name: '停用配置'}));
    expect(write.mock.calls[0][0]).toBe('setBindingState');
    expect(JSON.parse(write.mock.calls[0][1]).enabled).toBe(false);
    fireEvent.change(name, {target: {value: 'draft'}});
    expect(screen.getByRole('button', {name: '停用配置'})).toBeDisabled();
  });
  it('预览不带保存字段，输入改变后旧预览不再可用', async () => {
    vi.mocked(previewToolUpgrade).mockResolvedValue(
      response({
        bindingId: detail.binding.bindingId,
        expectedVersion: detail.binding.version,
        currentBaseline: tool.tool,
        currentTool: tool.tool,
        valid: true,
        compatible: true,
        rejection: 'NONE',
        digest: 'a'.repeat(64),
        changes: [],
        proposed: {
          tool: tool.tool,
          policy: tool.defaultPolicy,
          configurationJson: '{}',
        },
      }),
    );
    render(
      <BindingEditor
        scope={scope}
        binding={detail.binding}
        tools={[tool]}
        disabled={false}
        onDirty={vi.fn()}
        onWrite={vi.fn()}
      />,
    );
    const name = await screen.findByLabelText('配置名称');
    await screen.findByLabelText('统计说明');
    fireEvent.click(screen.getByRole('button', {name: '校验并预览有效配置'}));
    expect(await screen.findByText(/配置校验通过/)).toBeInTheDocument();
    expect(
      JSON.parse(vi.mocked(previewToolUpgrade).mock.calls[0][0]),
    ).not.toHaveProperty('reason');
    fireEvent.change(name, {target: {value: 'changed'}});
    await waitFor(() =>
      expect(screen.queryByText(/配置校验通过/)).not.toBeInTheDocument(),
    );
  });
  it('非兼容升级须预览并确认，输入改变撤销旧确认', async () => {
    const v2 = {...tool, tool: {...tool.tool, version: 'v2'}};
    vi.mocked(previewToolUpgrade).mockResolvedValue(
      response({
        bindingId: detail.binding.bindingId,
        expectedVersion: detail.binding.version,
        currentBaseline: tool.tool,
        currentTool: tool.tool,
        valid: true,
        compatible: false,
        rejection: 'NONE',
        digest: 'b'.repeat(64),
        changes: ['TOOL_VERSION', 'RISK'],
        proposed: {
          tool: v2.tool,
          policy: tool.defaultPolicy,
          configurationJson: '{}',
        },
      }),
    );
    const write = vi.fn();
    render(
      <BindingEditor
        scope={scope}
        binding={detail.binding}
        tools={[tool, v2]}
        disabled={false}
        onDirty={vi.fn()}
        onWrite={write}
      />,
    );
    const name = await screen.findByLabelText('配置名称');
    await screen.findByLabelText('统计说明');
    fireEvent.click(screen.getByRole('button', {name: '高级：版本与限制'}));
    fireEvent.mouseDown(screen.getByRole('combobox', {name: '基准工具版本'}));
    fireEvent.click(
      await screen.findByText('text.count / v2', {exact: true}),
    );
    expect(screen.getByRole('button', {name: '确认升级'})).toBeDisabled();
    await waitFor(() =>
      expect(
        screen.getByRole('button', {name: '校验并预览有效配置'}),
      ).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole('button', {name: '校验并预览有效配置'}));
    const accept = await screen.findByRole('checkbox', {
      name: '我已核对配置及风险，确认此非兼容升级',
    });
    expect(screen.getByRole('button', {name: '确认升级'})).toBeDisabled();
    fireEvent.click(accept);
    fireEvent.click(screen.getByRole('button', {name: '确认升级'}));
    expect(write.mock.calls[0][0]).toBe('upgradeBinding');
    expect(JSON.parse(write.mock.calls[0][1])).toMatchObject({
      expectedPreviewDigest: 'b'.repeat(64),
      acceptIncompatible: true,
      settings: {tool: v2.tool},
    });
    fireEvent.change(name, {target: {value: 'new draft'}});
    await waitFor(() =>
      expect(screen.getByRole('button', {name: '确认升级'})).toBeDisabled(),
    );
  });
});
