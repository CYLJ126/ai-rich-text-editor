import {describe, expect, it, vi} from 'vitest';
import {createIdempotencyKey, shouldPollToolTask, taskFromToolResult, toolTaskProgressPercent,} from './invocation';

describe('AI tool invocation helpers', () => {
  it('classifies polling states without polling terminal tasks', () => {
    expect(shouldPollToolTask('RUNNING')).toBe(true);
    expect(shouldPollToolTask('WAITING_APPROVAL')).toBe(true);
    expect(shouldPollToolTask('SUCCEEDED')).toBe(false);
  });

  it('normalizes progress to a safe percentage', () => {
    expect(toolTaskProgressPercent(0.456)).toBe(46);
    expect(toolTaskProgressPercent(-1)).toBe(0);
    expect(toolTaskProgressPercent(2)).toBe(100);
    expect(toolTaskProgressPercent(undefined)).toBe(0);
  });

  it('extracts accepted task handles only', () => {
    const taskHandle = {
      taskId: 'task-1',
      callId: 'call-1',
      tool: {namespace: 'article', name: 'summarize', version: '1.0.0'},
      status: 'QUEUED' as const,
      createdAt: '2026-09-28T00:00:00Z',
      updatedAt: '2026-09-28T00:00:00Z',
      version: 0,
      metadata: {},
    };
    expect(
      taskFromToolResult({status: 'accepted', taskHandle, metadata: {}}),
    ).toBe(taskHandle);
    expect(taskFromToolResult(undefined)).toBeUndefined();
  });

  it('creates a non-empty idempotency key', () => {
    vi.stubGlobal('crypto', {randomUUID: () => 'stable-uuid'});
    expect(createIdempotencyKey()).toBe('stable-uuid');
    vi.unstubAllGlobals();
  });
});
