import {z} from 'zod';
import {AiApiError, type AiRequestOptions, postResult} from '@/services/arte-ai/request';

const base = '/arte/workspace';
const tenantId = z.string().min(1).max(256);
const tenantType = z.union([z.literal(0), z.literal(1)]);
const tenantRole = z.union([z.literal(0), z.literal(1), z.literal(2)]);
const cursor = z.string().uuid();
const identity = z.object({
  id: tenantId,
  name: z.string().min(1).max(100),
  type: tenantType,
  state: z.literal(0),
  currentRole: tenantRole,
  version: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
  isPersonal: z.boolean(),
});
const consistentIdentity = (tenant: z.infer<typeof identity>) => !tenant.isPersonal || tenant.type === 0;
const summary = identity.strict().refine(consistentIdentity);
const action = z.enum(['tenant:read', 'tenant:policy:write', 'tenant:workspace:create']);
const detail = identity.extend({
  viewLevel: z.enum(['BASIC', 'GOVERNANCE']),
  actions: z.array(action).max(3),
}).strict().refine(consistentIdentity).refine(tenant => {
  const governance = tenant.currentRole !== 2;
  return tenant.actions.includes('tenant:read')
    && new Set(tenant.actions).size === tenant.actions.length
    && tenant.actions.includes('tenant:policy:write') === governance
    && tenant.viewLevel === (governance ? 'GOVERNANCE' : 'BASIC');
}, 'Invalid tenant detail eligibility');
const page = z.object({
  records: z.array(summary).max(100),
  nextCursor: cursor.nullable(),
  exhausted: z.boolean(),
}).strict().refine(result => result.exhausted === (result.nextCursor === null)
  && new Set(result.records.map(tenant => tenant.id)).size === result.records.length, 'Invalid tenant directory continuation');
const creation = z.object({
  available: z.boolean(),
  organizationAllowed: z.boolean(),
  reason: z.string().min(1).max(96),
}).strict().refine(value => !value.organizationAllowed || value.available, 'Invalid tenant creation eligibility');

export type TenantType = z.infer<typeof tenantType>;
export type TenantRole = z.infer<typeof tenantRole>;
export type TenantSummary = z.infer<typeof summary>;
export type TenantDetail = z.infer<typeof detail>;
export type TenantAction = z.infer<typeof action>;
export type TenantBrowsePage = z.infer<typeof page>;
export type TenantCreationOptions = z.infer<typeof creation>;

export interface TenantBrowseQuery {
  cursor?: string | null;
  size?: number;
  search?: string;
  type?: TenantType;
}

async function result<T>(path: string, data: unknown, schema: z.ZodType<T>, options?: AiRequestOptions) {
  const response = await postResult<T>(path, data, options, undefined, base);
  if (!schema.safeParse(response.body.data).success)
    throw new AiApiError(response.httpStatus, response.body, 'Invalid tenant API response');
  return response;
}

export function browseAccessibleTenants(query: TenantBrowseQuery = {}, options?: AiRequestOptions) {
  const {cursor: next = null, size = 20, search = '', type} = query;
  return result('browseAccessibleTenants', {cursor: next, size, search, ...(type === undefined ? {} : {type})},
    page.refine(value => value.records.length <= size && (value.nextCursor === null || value.nextCursor !== next)
      && (type === undefined || value.records.every(tenant => tenant.type === type))), options);
}

export const getTenant = (id: string, options?: AiRequestOptions) =>
  result('getTenant', {tenantId: id}, detail.refine(tenant => tenant.id === id), options);

export const getTenantCreationOptions = (options?: AiRequestOptions) =>
  result('getTenantCreationOptions', {}, creation, options);
