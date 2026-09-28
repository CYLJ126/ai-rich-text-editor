import {describe, expect, it} from 'vitest';
import {createWorkflowDraft, isActiveWorkflowRun, moveWorkflowNode, workflowNodePosition,} from './workflow';

describe('workflow helpers', () => {
  it('creates a connected start/end draft', () => {
    const draft = createWorkflowDraft();
    expect(draft.nodes.map((node) => node.type)).toEqual(['start', 'end']);
    expect(draft.edges[0]).toMatchObject({
      sourceNodeId: draft.nodes[0].nodeId,
      targetNodeId: draft.nodes[1].nodeId,
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
