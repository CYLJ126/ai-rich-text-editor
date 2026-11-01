import {beforeEach, expect, it, vi} from 'vitest';
import {readSharingOperation, saveSharingOperation, type SharingOperation} from './modelSharingRecovery';
import {sharingContext} from '@root/tests/fixtures/modelSharing';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
const operation: SharingOperation = {
  key: 'original',
  scope: sharingContext.scope,
  command: {
    kind: 'consumers',
    request: {
      scope: sharingContext.scope,
      resourceId: 'model',
      expectedVersion: 0,
      credentialVersion: 1,
      subjectIds: ['bob']
    }
  }
};
beforeEach(() => sessionStorage.clear());
it('isolates users and exact management scopes', () => {
  expect(saveSharingOperation(sharingContext, operation)).toBe(true);
  expect(readSharingOperation(sharingContext).operation).toEqual(operation);
  expect(readSharingOperation({...sharingContext, userId: 'bob'}).operation).toBeNull();
  expect(readSharingOperation({
    ...sharingContext,
    scope: {...sharingContext.scope, workspaceId: 'other'}
  }).operation).toBeNull();
});
it('permits explicit tenant ACL targets while rejecting another tenant', () => {
  const grant: SharingOperation = {
    key: 'acl',
    scope: sharingContext.scope,
    command: {
      kind: 'grant',
      request: {
        target: {
          scope: {tenantId: 'tenant', workspaceId: 'target'},
          resourceType: 'model',
          resourceId: 'model'
        }, expectedAclVersion: 0, recipient: {kind: 'SUBJECT', subjectId: 'bob'}, actions: ['model:use']
      }
    }
  };
  expect(saveSharingOperation(sharingContext, grant)).toBe(true);
  if (grant.command.kind !== 'grant') throw new Error('Expected grant command');
  grant.command = {
    ...grant.command,
    request: {
      ...grant.command.request,
      target: {...grant.command.request.target, scope: {tenantId: 'other', workspaceId: 'target'}}
    }
  };
  expect(saveSharingOperation(sharingContext, grant)).toBe(false);
});
it('retains malformed records and rejects injected credential data', () => {
  expect(saveSharingOperation(sharingContext, {...operation, key: 'invalid\n'})).toBe(false);
  saveSharingOperation(sharingContext, operation);
  const key = sessionStorage.key(0);
  if (!key) throw new Error('missing operation');
  sessionStorage.setItem(key, '{broken');
  expect(readSharingOperation(sharingContext)).toEqual({operation: null, blocked: true});
  expect(sessionStorage.getItem(key)).toBe('{broken');
});
it('does not allow a caller to submit when storage fails', () => {
  const write = vi.spyOn(sessionStorage, 'setItem').mockImplementation(() => {
    throw new Error('unavailable');
  });
  try {
    expect(saveSharingOperation(sharingContext, operation)).toBe(false);
  } finally {
    write.mockRestore();
  }
});
