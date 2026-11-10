import {beforeEach, expect, it, vi} from 'vitest';
import {postResult} from '@/services/arte-ai/request';
import {
  executeMembership,
  getMyPendingInvitationCounts,
  listMyInvitations,
  membershipCommandSchema
} from './membership';

vi.mock('@/services/arte-ai/request', async original => ({
  ...await original<typeof import('@/services/arte-ai/request')>(), postResult: vi.fn()
}));
const scope = {tenantId: 'team', workspaceId: 'space'};
const target = {tenantId: 'team', scope};
const payload = {invitationId: 'invite', expectedInvitationVersion: 2};
const item = {
  invitationId: 'invite', scope, targetSubjectId: 'bob', role: 3, state: 4,
  expiresAt: '2026-12-01T00:00:00Z', version: 3
};
const response = (value: unknown) => ({httpStatus: 200, body: {data: value}});
beforeEach(() => vi.resetAllMocks());

it('declines using only the invitation and original key, without a claimed membership or parent version', async () => {
  vi.mocked(postResult).mockResolvedValue(response({
    operationId: 'op', status: 1, delivery: 0,
    resultVersion: 3, value: item, acceptedAt: '', completedAt: null
  }) as never);
  const command = {target, action: 'decline' as const, payload};
  expect((await executeMembership(command, 'decline-key')).value).toEqual(item);
  expect(postResult).toHaveBeenCalledWith('declineWorkspaceInvitation', payload, undefined, 'decline-key', '/arte/workspace');
  expect(membershipCommandSchema.safeParse({...command, expectedVersion: 1}).success).toBe(false);
});

it('rejects a decline receipt from another workspace or with an incorrect final state', async () => {
  for (const value of [{...item, scope: {...scope, workspaceId: 'other'}}, {...item, state: 2}]) {
    vi.mocked(postResult).mockResolvedValue(response({
      operationId: 'op', status: 1, delivery: 0,
      resultVersion: 3, value, acceptedAt: '', completedAt: null
    }) as never);
    await expect(executeMembership({target, action: 'decline', payload}, 'key')).rejects.toThrow('响应无法核验');
  }
});

it('sends the state filter to the server and validates pending counts', async () => {
  vi.mocked(postResult).mockResolvedValueOnce(response({records: [item], current: 1, size: 20, total: 1}) as never);
  expect((await listMyInvitations(true, 1, undefined, 4)).records[0]?.state).toBe(4);
  expect(postResult).toHaveBeenLastCalledWith('listMyWorkspaceInvitations', {
    current: 1,
    state: 4
  }, undefined, undefined, '/arte/workspace');
  vi.mocked(postResult).mockResolvedValueOnce(response({tenant: 1, workspace: 25}) as never);
  expect(await getMyPendingInvitationCounts()).toEqual({tenant: 1, workspace: 25});
});
