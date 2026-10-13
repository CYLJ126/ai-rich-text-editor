import {beforeEach, expect, it, vi} from 'vitest';
import {request} from '@umijs/max';
import * as api from './workspace';
import {AiApiError} from '@/services/arte-ai';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
const scope = {tenantId: 'tenant', workspaceId: 'space'};
const view = {scope, name: '空间', description: '', state: 0, currentRole: 0, version: 7};
const preference = {defaultScope: scope, startupScope: null, lastScope: null, favorites: [], version: 3};
const receipt = (value: unknown) => ({
  operationId: 'server-operation', status: 1, delivery: 0, value,
  resultVersion: 7, acceptedAt: '2026-10-11T00:00:00Z', completedAt: '2026-10-11T00:00:00Z'
});

function response(data: unknown) {
  vi.mocked(request).mockResolvedValue({status: 200, data: {success: true, code: '200', desc: 'OK', data}});
}

beforeEach(() => vi.clearAllMocks());
it('sends an initialization without client identity, scope or roles and retains its key', async () => {
  response(receipt({
    tenant: {id: 'tenant', name: '个人', type: 0, state: 0, currentRole: 0, version: 0},
    workspace: view,
    preference
  }));
  await api.initializePersonalWorkspace('original-key');
  expect(request).toHaveBeenCalledWith('/arte/workspace/initializePersonalWorkspace', expect.objectContaining({
    data: undefined, headers: {'Content-Type': 'application/json', 'Idempotency-Key': 'original-key'},
  }));
});
it('sends rename metadata with its original scope and server version', async () => {
  response(receipt(view));
  const data = {scope, expectedVersion: 7, name: '新名', description: '说明'};
  await api.renameWorkspace(data, 'rename-key');
  expect(request).toHaveBeenCalledWith('/arte/workspace/renameWorkspace', expect.objectContaining({
    data,
    headers: expect.objectContaining({'Idempotency-Key': 'rename-key'}), skipErrorHandler: true
  }));
});
it('updates the complete preference value, including explicit null and empty favorites', async () => {
  response(receipt(preference));
  const data = {expectedPreferenceVersion: 3, startupScope: null, lastScope: null, favorites: []};
  await api.updateWorkspacePreference(data, 'preference-key');
  expect(request).toHaveBeenCalledWith('/arte/workspace/updateWorkspacePreference', expect.objectContaining({data}));
});
it('queries the server operation ID without sending a new write key', async () => {
  response(receipt(view));
  await api.getWorkspaceCommandReceipt('server-operation');
  expect(request).toHaveBeenCalledWith('/arte/workspace/getWorkspaceCommandReceipt', expect.objectContaining({
    data: {operationId: 'server-operation'}, headers: {'Content-Type': 'application/json'},
  }));
});
it('preserves public business codes and HTTP status for conflict recovery', async () => {
  vi.mocked(request).mockResolvedValue({
    status: 409,
    data: {success: false, code: 'PLATFORM_VERSION_CONFLICT', desc: '版本冲突'}
  });
  await expect(api.renameWorkspace({scope, expectedVersion: 7, name: '新名', description: ''}, 'key'))
    .rejects.toMatchObject({httpStatus: 409, code: 'PLATFORM_VERSION_CONFLICT'});
});
it.each([' key', 'key ', 'key\u0000value', 'key\nvalue'])('rejects an invalid public operation key before issuing a request: %j', key => {
  expect(() => api.initializePersonalWorkspace(key)).toThrow(TypeError);
  expect(request).not.toHaveBeenCalled();
});
it('rejects pending or malformed success receipts so callers retain the original operation', async () => {
  response({...receipt(view), status: 0, completedAt: null});
  await expect(api.createWorkspace({
    tenantId: 'tenant',
    name: '空间',
    description: ''
  }, 'key')).rejects.toBeInstanceOf(AiApiError);
  response(receipt({...view, scope: {tenantId: 'tenant'}, state: 999}));
  await expect(api.renameWorkspace({
    scope,
    expectedVersion: 7,
    name: '新名',
    description: ''
  }, 'key')).rejects.toBeInstanceOf(AiApiError);
});
it('rejects a mismatched personal initialization association', async () => {
  response(receipt({
    tenant: {id: 'other', name: '个人', type: 0, state: 0, currentRole: 0, version: 0},
    workspace: view,
    preference
  }));
  await expect(api.initializePersonalWorkspace('key')).rejects.toBeInstanceOf(AiApiError);
});
it('keeps server directory totals and numeric roles without assuming ownership metadata', async () => {
  vi.mocked(request).mockResolvedValue({
    status: 200,
    data: {
      success: true,
      code: '200',
      desc: 'OK',
      records: [{...view, defaultWorkspace: true}],
      current: 2,
      size: 20,
      total: 42
    }
  });
  const response = await api.listAccessibleWorkspaces(2);
  expect(response.body.total).toBe(42);
  expect(request).toHaveBeenCalledWith('/arte/workspace/listAccessibleWorkspaces', expect.objectContaining({
    data: {
      current: 2,
      size: 20
    }
  }));
});
