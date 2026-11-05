import React from 'react';
import {cleanup, render, screen} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {history} from '@umijs/max';
import Page from './index';

vi.mock('@umijs/max', () => ({
  useModel: () => ({initialState: {currentUser: {id: 'alice'}}}),
  history: {replace: vi.fn(), push: vi.fn()},
}));
vi.mock('@/pages/Environment/Tenants', () => ({
  TenantSettings: ({initialTenantId, initialTab}: { initialTenantId?: string; initialTab: string }) =>
    <div>{initialTenantId ?? '未选择租户'} · {initialTab}</div>,
}));
beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

it.each([
  ['', '未选择租户 · policy'],
  ['?tenantId=org', 'org · policy'],
  ['?tab=tenant&tenantId=org&workspaceId=old', 'org · policy'],
])('preserves legacy tenant policy location %s', (query, expected) => {
  window.history.replaceState(null, '', `/Workspace/Policies${query}`);
  render(<Page/>);
  expect(screen.getByText(expected)).toBeInTheDocument();
  expect(history.replace).not.toHaveBeenCalled();
});

it('forwards an explicit legacy workspace to the workspace policy target', () => {
  window.history.replaceState(null, '', '/Workspace/Policies?tenantId=org&workspaceId=a&tab=workspace');
  render(<Page/>);
  expect(history.replace).toHaveBeenCalledWith('/Environment/Workspaces?account=alice&tenantId=org&workspaceId=a&tab=policy');
});

it.each(['?workspaceId=a', '?account=bob&tenantId=org', '?tenantId=org&tenantId=other'])(
  'rejects incomplete, foreign or ambiguous legacy locations %s', query => {
    window.history.replaceState(null, '', `/Workspace/Policies${query}`);
    render(<Page/>);
    expect(screen.getByText('政策定位无效，请重新选择配置对象。')).toBeInTheDocument();
    expect(history.replace).not.toHaveBeenCalled();
  });
