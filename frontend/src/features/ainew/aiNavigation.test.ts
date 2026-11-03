import {beforeEach, expect, it, vi} from 'vitest';
import {aiLocation, parseAiIntent, verifyAiModel} from './aiNavigation';
import {listWorkspaceModelOptions} from '@/services/platform/ai';

vi.mock('@umijs/max', () => ({history: {}}));
vi.mock('@/services/platform/ai', () => ({listWorkspaceModelOptions: vi.fn()}));
const scope = {tenantId: '租户', workspaceId: 'B'};
const model = {resourceId: 'resource-1', binding: {type: 'binding', id: 'published-1', version: 'v1'}};
beforeEach(() => vi.resetAllMocks());
it('round-trips exact model and distinct management, target and chat scopes', () => {
  const intent = {
    scope,
    model,
    management: {...scope, workspaceId: 'M'},
    execution: scope,
    chat: {...scope, workspaceId: 'A'},
    returnTo: 'models' as const
  };
  const url = aiLocation('workspaces', 'alice', intent);
  expect(parseAiIntent(url.slice(url.indexOf('?')), 'alice')).toMatchObject(intent);
});
it.each(['?tenantId=T', '?workspaceId=B', '?resourceId=r', '?resourceId=r&bindingId=b', '?returnTo=https://evil.example',
  '?account=bob', '?tenantId=T&workspaceId=B&management-tenantId=X&management-workspaceId=M', '?tenantId=T&tenantId=X&workspaceId=B',
  '?tenantId=%0A&workspaceId=B', '?view=unknown', '?tenantId=T&workspaceId=M&execution-tenantId=X&execution-workspaceId=B'])('rejects invalid or cross-account intent %s', search => {
  expect(() => parseAiIntent(search, 'alice')).toThrow();
});
it('missing target stays missing and never borrows a current space', () => {
  expect(parseAiIntent('?tab=ai', 'alice').scope).toBeUndefined();
});
it('verifies a precise readable model across an authorization-filtered empty page', async () => {
  vi.mocked(listWorkspaceModelOptions).mockResolvedValueOnce({body: {data: {items: [], nextCursor: 'next'}}} as never)
    .mockResolvedValueOnce({body: {data: {items: [{...model}], nextCursor: null}}} as never);
  await verifyAiModel(scope, model, new AbortController().signal);
  expect(listWorkspaceModelOptions).toHaveBeenLastCalledWith(scope, 'next', expect.anything());
});
it('does not substitute a new version or another resource and detects repeated cursors', async () => {
  vi.mocked(listWorkspaceModelOptions).mockResolvedValue({
    body: {
      data: {
        items: [{...model, resourceId: 'other'}],
        nextCursor: null
      }
    }
  } as never);
  await expect(verifyAiModel(scope, model, new AbortController().signal)).rejects.toThrow('不可读');
  vi.mocked(listWorkspaceModelOptions).mockResolvedValue({body: {data: {items: [], nextCursor: 'repeat'}}} as never);
  await expect(verifyAiModel(scope, model, new AbortController().signal)).rejects.toThrow('分页失效');
});
