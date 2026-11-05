import {z} from 'zod';
import {AiApiError, type AiRequestOptions, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';

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
const action = z.enum(['tenant:read', 'tenant:policy:write', 'tenant:workspace:create', 'tenant:update']);
const detail = identity.extend({
  viewLevel: z.enum(['BASIC', 'GOVERNANCE']),
  actions: z.array(action).max(4),
}).strict().refine(consistentIdentity).refine(tenant => {
  const governance = tenant.currentRole !== 2;
  return tenant.actions.includes('tenant:read')
    && new Set(tenant.actions).size === tenant.actions.length
    && (!tenant.actions.includes('tenant:update') || governance)
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

const version = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
export const tenantNameSchema = z.string().trim().min(1).max(100);
export const tenantCommandSchema = z.discriminatedUnion('kind', [
  z.object({kind: z.literal('create'), request: z.object({name: tenantNameSchema}).strict()}).strict(),
  z.object({
    kind: z.literal('update'), request: z.object({
      tenantId, name: tenantNameSchema, expectedTenantVersion: version
    }).strict()
  }).strict()
]);
export type TenantCommand = z.infer<typeof tenantCommandSchema>;
const tenantReceiptSchema = z.object({
  operationId: tenantId, status: z.literal(1), delivery: z.union([z.literal(0), z.literal(1)]),
  value: identity.omit({isPersonal: true}).strict(), resultVersion: version,
  acceptedAt: z.string(), completedAt: z.string().nullable()
});
export type TenantReceipt = z.infer<typeof tenantReceiptSchema>;

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

function tenantReceipt(data: unknown, status: number, command: TenantCommand, operationId?: string) {
  const parsed = tenantReceiptSchema.safeParse(data);
  if (parsed.success) {
    const receipt = parsed.data;
    const target = receipt.value;
    const match = command.kind === 'create' ? target.type === 1 && target.currentRole === 0 && target.version === 0
      : target.id === command.request.tenantId && target.version === command.request.expectedTenantVersion + 1
      && target.currentRole !== 2;
    if (match && target.name === command.request.name && receipt.resultVersion === target.version
      && (operationId === undefined || receipt.operationId === operationId && receipt.delivery === 1)) return receipt;
  }
  throw new AiApiError(status, {code: 'INVALID_TENANT_RECEIPT'}, '租户操作回执不匹配');
}

export async function executeTenantCommand(command: TenantCommand, key: string) {
  requireIdempotencyKey(key);
  const input = tenantCommandSchema.parse(command);
  const response = await postResult<unknown>(input.kind === 'create' ? 'createOrganization' : 'updateTenant',
    input.request, undefined, key, base);
  return tenantReceipt(response.body.data, response.httpStatus, input);
}

export async function getTenantCommandReceipt(operationId: string, command: TenantCommand, options?: AiRequestOptions) {
  const response = await postResult<unknown>('getWorkspaceCommandReceipt', {operationId: tenantId.parse(operationId)},
    options, undefined, base);
  return tenantReceipt(response.body.data, response.httpStatus, command, operationId);
}
