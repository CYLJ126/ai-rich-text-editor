import {beforeEach, expect, it, vi} from 'vitest';
import {request} from '@umijs/max';
import {AiApiError} from '@/services/arte-ai/request';
import {browseAccessibleTenants, getTenant, getTenantCreationOptions} from './tenant';

vi.mock('@umijs/max', () => ({request: vi.fn()}));
beforeEach(() => vi.resetAllMocks());

const org = {id: 'org', name: '组织', type: 1, state: 0, currentRole: 2, version: 0, isPersonal: false};
const nextCursor = '00000000-0000-4000-8000-000000000002';
const reply = (data: unknown) => vi.mocked(request).mockResolvedValue({
  status: 200, data: {success: true, code: '200', desc: 'OK', data},
});

it('passes the server filters and accepts an empty page that can continue', async () => {
  reply({records: [], exhausted: false, nextCursor});
  const {signal} = new AbortController();
  const response = await browseAccessibleTenants({search: '组织', type: 1}, {signal});
  expect(response.body.data.nextCursor).toBe(nextCursor);
  expect(request).toHaveBeenCalledExactlyOnceWith('/arte/workspace/browseAccessibleTenants', expect.objectContaining({
    signal, data: {cursor: null, size: 20, search: '组织', type: 1},
  }));
});

it('rejects a directory response for a different filter or a duplicate tenant', async () => {
  reply({records: [org], exhausted: true, nextCursor: null});
  await expect(browseAccessibleTenants({type: 0})).rejects.toBeInstanceOf(AiApiError);
  reply({records: [org, org], exhausted: true, nextCursor: null});
  await expect(browseAccessibleTenants()).rejects.toBeInstanceOf(AiApiError);
});

it('accepts the server basic view and rejects foreign detail or leaked governance data', async () => {
  const detail = {...org, viewLevel: 'BASIC', actions: ['tenant:read', 'tenant:workspace:create']};
  reply(detail);
  expect((await getTenant('org')).body.data).toEqual(detail);
  await expect(getTenant('other')).rejects.toBeInstanceOf(AiApiError);
  reply({...detail, policy: {}});
  await expect(getTenant('org')).rejects.toBeInstanceOf(AiApiError);
  reply({...detail, actions: ['tenant:read', 'tenant:policy:write']});
  await expect(getTenant('org')).rejects.toBeInstanceOf(AiApiError);
});

it('sends an empty creation-options request and keeps typed authority failures', async () => {
  const disabled = {available: false, organizationAllowed: false, reason: 'CAPABILITY_NOT_ENABLED'};
  reply(disabled);
  expect((await getTenantCreationOptions()).body.data).toEqual(disabled);
  expect(request).toHaveBeenLastCalledWith('/arte/workspace/getTenantCreationOptions', expect.objectContaining({data: {}}));
  vi.mocked(request).mockResolvedValue({
    status: 503,
    data: {success: false, code: 'PLATFORM_AUTHORITY_UNAVAILABLE', desc: '暂不可用'}
  });
  await expect(browseAccessibleTenants()).rejects.toMatchObject({
    httpStatus: 503,
    code: 'PLATFORM_AUTHORITY_UNAVAILABLE'
  });
});
