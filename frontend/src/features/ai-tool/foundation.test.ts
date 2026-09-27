import {describe, expect, it} from 'vitest';
import access from '@/access';
import {
  createToolApiError,
  isRetryableToolError,
  isTerminalToolStatus,
  normalizePageQuery,
  normalizePageResult,
  toProTableResult,
} from '.';

describe('AI tool front-end foundation', () => {
  it('normalizes unsafe pagination input', () => {
    expect(normalizePageQuery({current: -1, pageSize: 999})).toEqual({
      current: 1,
      pageSize: 200,
    });
    const page = normalizePageResult({
      success: true,
      records: undefined,
      current: 2,
      size: 20,
      total: 0,
    });
    expect(page.records).toEqual([]);
    expect(toProTableResult(page)).toEqual({
      data: [],
      total: 0,
      success: true,
    });
  });

  it('classifies retryable and terminal states', () => {
    expect(isRetryableToolError('TOOL_REMOTE_UNAVAILABLE')).toBe(true);
    expect(isRetryableToolError('TOOL_INVALID_ARGUMENT')).toBe(false);
    expect(isTerminalToolStatus('SUCCEEDED')).toBe(true);
    expect(isTerminalToolStatus('requires-approval')).toBe(false);
  });

  it('converts failed envelopes to typed tool errors', () => {
    const error = createToolApiError({
      code: 'TOOL_TIMEOUT',
      desc: '调用超时',
    });
    expect(error).toMatchObject({
      name: 'ToolApiError',
      code: 'TOOL_TIMEOUT',
      category: 'TIMEOUT',
      retryable: true,
    });
  });

  it('derives granular access from RBAC operations', () => {
    const permissions = access({
      currentUser: {
        roles: [],
        menus: ['AITool'],
        menuOperations: ['aiTool:list', 'aiTool:invoke'],
      },
    });
    expect(permissions.canViewAiTools).toBe(true);
    expect(permissions.canInvokeAiTools).toBe(true);
    expect(permissions.canManageAiTools).toBe(false);
  });
});
