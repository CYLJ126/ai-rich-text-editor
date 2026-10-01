import {fireEvent, render, screen, waitFor} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';
import {listToolBindings} from '@/services/ant-design-pro/ai.tool';
import {validateWorkflow} from '@/services/ant-design-pro/ai.tool.workflow';
import type {WorkflowVersionView} from '@/types/ai.tool.type';
import WorkflowEditorDrawer from './WorkflowEditorDrawer';

vi.mock('@/components', () => ({
  JsonEditor: () => <div data-testid="json-editor"/>,
}));

vi.mock('@/services/ant-design-pro/ai.tool', () => ({
  listToolBindings: vi.fn().mockResolvedValue([]),
  listToolCatalog: vi.fn().mockResolvedValue({records: []}),
  listToolVersions: vi.fn().mockResolvedValue([]),
}));

vi.mock('@/services/ant-design-pro/ai.tool.workflow', () => ({
  publishWorkflowVersion: vi.fn(),
  saveWorkflowDraft: vi.fn(),
  validateWorkflow: vi.fn().mockResolvedValue({
    valid: false,
    issues: [
      {
        code: 'TOOL_REQUIRED_INPUT_MISSING',
        severity: 'ERROR',
        nodeId: 'tool-node',
        message: 'required tool inputs are not bound',
      },
    ],
  }),
}));

vi.mock('./WorkflowCanvas', () => ({
  default: ({selectedNodeId}: { selectedNodeId?: string }) => (
    <div data-testid="selected-node">{selectedNodeId}</div>
  ),
}));

const initial: WorkflowVersionView = {
  workflowId: 'workflow-test',
  version: '1.0.0',
  name: '测试工作流',
  description: '',
  inputSchema: {dialect: 'test', schema: '{}'},
  outputSchema: {dialect: 'test', schema: '{}'},
  nodes: [
    {
      nodeId: 'start-node',
      name: '开始',
      type: 'start',
      inputBindings: {},
      outputNames: ['result'],
      configuration: {},
    },
    {
      nodeId: 'tool-node',
      name: '工具',
      type: 'tool',
      inputBindings: {},
      outputNames: ['result'],
      configuration: {},
      tool: {namespace: 'test', name: 'lookup', version: '1.0.0'},
    },
  ],
  edges: [],
  tags: [],
  executionPolicy: {
    maximumSteps: 10,
    timeout: 'PT1M',
    maximumNodeRetries: 0,
    maximumParallelism: 1,
    checkpointAfterEachNode: true,
  },
  compiledPlan: {},
  pinnedTools: {},
  lifecycleState: 'draft',
  rowVersion: 1,
  createTime: '2026-10-01T00:00:00Z',
  updateTime: '2026-10-01T00:00:00Z',
};

describe('WorkflowEditorDrawer validation navigation', () => {
  it('locates a tool node before its versions finish loading', async () => {
    render(
      <WorkflowEditorDrawer
        open
        initial={initial}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', {name: /校验$/}));

    fireEvent.click(screen.getByRole('tab', {name: /校验结果/}));
    await screen.findByText('TOOL_REQUIRED_INPUT_MISSING');
    fireEvent.click(screen.getByRole('button', {name: '定位节点'}));

    await waitFor(() => {
      expect(screen.getByTestId('selected-node')).toHaveTextContent(
        'tool-node',
      );
      expect(screen.getByRole('tab', {name: '可视化编排'})).toHaveAttribute(
        'aria-selected',
        'true',
      );
    });
  });

  it('clears legacy edge data ports while preserving the connection', async () => {
    render(
      <WorkflowEditorDrawer
        open
        initial={{
          ...initial,
          edges: [
            {
              edgeId: 'edge-1',
              sourceNodeId: 'start-node',
              sourceOutput: 'result',
              targetNodeId: 'tool-node',
              targetInput: 'result',
            },
          ],
        }}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    const controlOnly = await screen.findByRole('button', {
      name: '仅控制顺序',
    });
    expect(controlOnly).toBeEnabled();
    expect(screen.getByLabelText('开始 连线目标输入')).toHaveValue('result');

    fireEvent.click(controlOnly);

    await waitFor(() => {
      expect(controlOnly).toBeDisabled();
      expect(screen.getByLabelText('开始 连线目标输入')).toHaveValue('');
    });
  });

  it('shows a successful validation state when the server returns no issues', async () => {
    vi.mocked(validateWorkflow).mockResolvedValueOnce({
      issues: [],
    } as unknown as Awaited<ReturnType<typeof validateWorkflow>>);
    render(
      <WorkflowEditorDrawer
        open
        initial={initial}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', {name: /校验$/}));
    fireEvent.click(screen.getByRole('tab', {name: /校验结果/}));

    await screen.findByText('校验通过');
    expect(screen.queryByText('尚未校验')).not.toBeInTheDocument();
  });

  it('stores the selected tool binding in the tool node configuration', async () => {
    vi.mocked(listToolBindings).mockResolvedValueOnce([{
      bindingId: 'binding-1',
      tool: {namespace: 'test', name: 'lookup', version: '1.0.0'},
      configuration: {},
      enabled: true,
      available: true,
      rowVersion: 0,
    }]);
    render(
      <WorkflowEditorDrawer
        open
        initial={{...initial, nodes: [initial.nodes[1], initial.nodes[0]]}}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    const bindingSelect = await screen.findByRole('combobox', {name: '工具绑定'});
    fireEvent.mouseDown(bindingSelect);
    fireEvent.click(await screen.findByText('binding-1 · 个人'));
    fireEvent.click(screen.getByRole('button', {name: /校验$/}));

    await waitFor(() => {
      expect(validateWorkflow).toHaveBeenCalledWith(expect.objectContaining({
        nodes: expect.arrayContaining([
          expect.objectContaining({
            nodeId: 'tool-node',
            configuration: {bindingId: 'binding-1'},
          }),
        ]),
      }));
    });
  });
});
