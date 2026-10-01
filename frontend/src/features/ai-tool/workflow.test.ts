import {describe, expect, it} from 'vitest';
import {
  asControlFlowEdge,
  createWorkflowDraft,
  isActiveWorkflowRun,
  moveWorkflowNode,
  workflowNodePosition,
} from './workflow';

describe('workflow helpers', () => {
  it('creates a connected start/end draft', () => {
    const draft = createWorkflowDraft();
    expect(draft.nodes.map((node) => node.type)).toEqual(['start', 'end']);
    expect(draft.edges[0]).toMatchObject({
      sourceNodeId: draft.nodes[0].nodeId,
      targetNodeId: draft.nodes[1].nodeId,
    });
    expect(draft.edges[0].sourceOutput).toBeUndefined();
    expect(draft.edges[0].targetInput).toBeUndefined();
  });

  it('converts a data edge to a control-only edge', () => {
    const edge = asControlFlowEdge({
      edgeId: 'edge-1',
      sourceNodeId: 'source',
      sourceOutput: 'result',
      targetNodeId: 'target',
      targetInput: 'input',
      conditionExpression: `\${source.enabled}`,
    });

    expect(edge).toEqual({
      edgeId: 'edge-1',
      sourceNodeId: 'source',
      sourceOutput: undefined,
      targetNodeId: 'target',
      targetInput: undefined,
      conditionExpression: `\${source.enabled}`,
    });
  });

  it('keeps node coordinates in UI-only configuration', () => {
    const node = moveWorkflowNode(createWorkflowDraft().nodes[0], 120, 240);
    expect(workflowNodePosition(node)).toEqual({x: 120, y: 240});
  });

  it('distinguishes resumable and terminal runs', () => {
    expect(isActiveWorkflowRun('WAITING_APPROVAL')).toBe(true);
    expect(isActiveWorkflowRun('SUCCEEDED')).toBe(false);
  });
});
