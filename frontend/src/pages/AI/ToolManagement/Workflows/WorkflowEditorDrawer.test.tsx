import {fireEvent, render, screen, waitFor} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';
import type {WorkflowVersionView} from '@/types/ai.tool.type';
import WorkflowEditorDrawer from './WorkflowEditorDrawer';

vi.mock('@/components', () => ({
  JsonEditor: () => <div data-testid="json-editor"/>,
}));

vi.mock('@/services/ant-design-pro/ai.tool', () => ({
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
});
