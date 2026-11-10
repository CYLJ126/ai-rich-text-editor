import {beforeEach, expect, it, vi} from 'vitest';
import {postResult} from '@/services/arte-ai/request';
import {executeLifecycle, getClosureProgress} from './workspaceLifecycle';

vi.mock('@/services/arte-ai/request', async original => ({
  ...await original<typeof import('@/services/arte-ai/request')>(),
  postResult: vi.fn()
}));
beforeEach(() => vi.resetAllMocks());
const scope = {tenantId: 'team', workspaceId: 'space'};
const command = {scope, action: 'archive' as const, expectedWorkspaceVersion: 4, reason: '', name: '项目空间'};
it('rejects a state-change receipt for another workspace or the wrong resulting state', async () => {
  for (const value of [{scope: {...scope, workspaceId: 'other'}, name: 'x', state: 1, currentRole: 0, version: 5},
    {scope, name: 'x', state: 0, currentRole: 0, version: 5}]) {
    vi.mocked(postResult).mockResolvedValue({
      httpStatus: 200, body: {
        data: {
          operationId: 'op', status: 1, delivery: 0,
          resultVersion: 5, value, acceptedAt: '', completedAt: ''
        }
      }
    } as never);
    await expect(executeLifecycle(command, 'key')).rejects.toThrow('无法核验');
  }
});
it('rejects mismatched closure operations and keeps progress scoped to its original workspace', async () => {
  vi.mocked(postResult).mockResolvedValue({
    httpStatus: 200,
    body: {data: {operationId: 'other', scope, state: 2, version: 1, blockers: []}}
  } as never);
  await expect(getClosureProgress(scope, 'operation')).rejects.toThrow('无法核验');
});
