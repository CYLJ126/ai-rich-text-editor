import type {
  JsonObject,
  WorkflowDraftRequest,
  WorkflowNode,
  WorkflowNodeType,
  WorkflowRunStatus,
} from '@/types/ai.tool.type';

export const WORKFLOW_RUN_PRESENTATION: Record<
  WorkflowRunStatus,
  { label: string; color: string }
> = {
  CREATED: {label: '已创建', color: 'default'},
  RUNNING: {label: '运行中', color: 'processing'},
  WAITING_TOOL: {label: '等待工具', color: 'processing'},
  WAITING_APPROVAL: {label: '等待审批', color: 'warning'},
  PAUSED: {label: '已暂停', color: 'warning'},
  SUCCEEDED: {label: '成功', color: 'success'},
  FAILED: {label: '失败', color: 'error'},
  CANCELLED: {label: '已取消', color: 'default'},
  TIMED_OUT: {label: '已超时', color: 'error'},
};

export function createWorkflowId(prefix = 'workflow'): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

export function createWorkflowNode(
  type: WorkflowNodeType,
  index: number,
): WorkflowNode {
  const nodeId = createWorkflowId(type);
  return {
    nodeId,
    name: `${nodeTypeLabel(type)} ${index}`,
    type,
    inputBindings: {},
    outputNames: type === 'end' ? [] : ['result'],
    configuration: {
      _ui: {
        x: 80 + ((index - 1) % 3) * 240,
        y: 80 + Math.floor((index - 1) / 3) * 150,
      },
    } as JsonObject,
  };
}

export function createWorkflowDraft(): WorkflowDraftRequest {
  const start = createWorkflowNode('start', 1);
  const end = createWorkflowNode('end', 2);
  end.configuration = {_ui: {x: 560, y: 80}};
  return {
    workflowId: createWorkflowId(),
    version: '0.1.0',
    name: '未命名工作流',
    description: '',
    inputSchema: schemaEnvelope('工作流输入'),
    outputSchema: schemaEnvelope('工作流输出'),
    nodes: [start, end],
    edges: [
      {
        edgeId: createWorkflowId('edge'),
        sourceNodeId: start.nodeId,
        sourceOutput: 'result',
        targetNodeId: end.nodeId,
        targetInput: 'result',
      },
    ],
    tags: [],
    executionPolicy: {
      maximumSteps: 100,
      timeout: 'PT30M',
      maximumNodeRetries: 0,
      maximumParallelism: 8,
      checkpointAfterEachNode: true,
    },
  };
}

export function workflowNodePosition(node: WorkflowNode): {
  x: number;
  y: number;
} {
  const ui = node.configuration._ui;
  if (!ui || Array.isArray(ui) || typeof ui !== 'object')
    return {x: 40, y: 40};
  const position = ui as JsonObject;
  return {
    x: typeof position.x === 'number' ? position.x : 40,
    y: typeof position.y === 'number' ? position.y : 40,
  };
}

export function moveWorkflowNode(
  node: WorkflowNode,
  x: number,
  y: number,
): WorkflowNode {
  return {
    ...node,
    configuration: {
      ...node.configuration,
      _ui: {x: Math.max(0, x), y: Math.max(0, y)},
    } as JsonObject,
  };
}

export function isActiveWorkflowRun(status: WorkflowRunStatus): boolean {
  return [
    'CREATED',
    'RUNNING',
    'WAITING_TOOL',
    'WAITING_APPROVAL',
    'PAUSED',
  ].includes(status);
}

export function nodeTypeLabel(type: WorkflowNodeType): string {
  return {
    start: '开始',
    end: '结束',
    tool: '工具',
    router: '分支',
    parallel: '并行',
    join: '汇聚',
  }[type];
}

function schemaEnvelope(title: string) {
  return {
    dialect: 'https://json-schema.org/draft/2020-12/schema',
    schema: JSON.stringify(
      {type: 'object', title, additionalProperties: false, properties: {}},
      null,
      2,
    ),
  };
}
