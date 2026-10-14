import {z} from 'zod';
import {AiApiError, type AiRequestOptions, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';
import type {
  CommandReceipt,
  CreateWorkspaceRequest,
  PersonalWorkspace,
  RenameWorkspaceRequest,
  UpdateWorkspacePreferenceRequest,
  WorkspaceAccessResponse,
  WorkspacePreference,
  WorkspaceScope,
  WorkspaceView
} from './workspaceTypes';

export type * from './workspaceTypes';

const base = '/arte/workspace';
const id = z.string().min(1).max(256);
export const workspaceScopeSchema = z.object({tenantId: id, workspaceId: id});
const version = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
const role = z.union([z.literal(0), z.literal(1), z.literal(2), z.literal(3)]);
export const workspaceViewSchema = z.object({
  scope: workspaceScopeSchema, name: z.string().min(1).max(100), description: z.string().max(1000).optional(),
  state: role, currentRole: role, version,
});
export const workspacePreferenceSchema = z.object({
  defaultScope: workspaceScopeSchema, startupScope: workspaceScopeSchema.nullable(),
  lastScope: workspaceScopeSchema.nullable(), favorites: z.array(workspaceScopeSchema).max(100), version,
});
export const personalWorkspaceSchema = z.object({
  tenant: z.object({
    id, name: z.string(), type: z.union([z.literal(0), z.literal(1)]),
    state: z.union([z.literal(0), z.literal(1), z.literal(2)]), currentRole: role, version
  }),
  workspace: workspaceViewSchema, preference: workspacePreferenceSchema,
}).refine(value => value.tenant.id === value.workspace.scope.tenantId
  && value.workspace.scope.tenantId === value.preference.defaultScope.tenantId
  && value.workspace.scope.workspaceId === value.preference.defaultScope.workspaceId, 'Invalid personal workspace association');
const receiptSchema = (value: z.ZodType) => z.object({
  operationId: id, status: z.literal(1), delivery: z.union([z.literal(0), z.literal(1)]), value,
  resultVersion: version, acceptedAt: z.string(), completedAt: z.string(),
});

function operationKey(key: string) {
  requireIdempotencyKey(key);
  if (key.trim() !== key || /[\x00-\x1f\x7f]/.test(key)) throw new TypeError('Invalid workspace Idempotency-Key');
  return key;
}

async function result<T>(path: string, data: unknown, schema: z.ZodType, options?: AiRequestOptions, key?: string) {
  const response = await postResult<T>(path, data, options, key, base);
  if (!schema.safeParse(response.body.data).success) throw new AiApiError(response.httpStatus, response.body, 'Invalid workspace API response');
  return response;
}

export const initializePersonalWorkspace = (key: string, options?: AiRequestOptions) => result<CommandReceipt<PersonalWorkspace>>('initializePersonalWorkspace', undefined, receiptSchema(personalWorkspaceSchema), options, operationKey(key));

const browseSchema = z.object({
  records: z.array(workspaceViewSchema.extend({defaultWorkspace: z.boolean()})).max(100),
  nextCursor: z.string().uuid().nullable(),
  exhausted: z.boolean(),
}).refine(page => page.exhausted === (page.nextCursor === null), 'Invalid directory continuation')
  .refine(page => new Set(page.records.map(record => JSON.stringify([record.scope.tenantId, record.scope.workspaceId]))).size
    === page.records.length, 'Duplicate directory scopes');

export const browseAccessibleWorkspaces = (cursor: string | null = null, search = '', options?: AiRequestOptions) =>
  result<import('./workspaceTypes').WorkspaceBrowsePage>('browseAccessibleWorkspaces', {
    cursor,
    search,
    size: 20
  }, browseSchema, options);

/** Explicit full count; browsing does not call this endpoint. */
export const countAccessibleWorkspaces = (options?: AiRequestOptions) =>
  result<number>('countAccessibleWorkspaces', undefined, z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER), options);

export const getWorkspace = (scope: WorkspaceScope, options?: AiRequestOptions) => result<WorkspaceView>('getWorkspace', {scope}, workspaceViewSchema, options);
export const createWorkspace = (data: CreateWorkspaceRequest, key: string, options?: AiRequestOptions) => result<CommandReceipt<WorkspaceView>>('createWorkspace', data, receiptSchema(workspaceViewSchema), options, operationKey(key));
export const renameWorkspace = (data: RenameWorkspaceRequest, key: string, options?: AiRequestOptions) => result<CommandReceipt<WorkspaceView>>('renameWorkspace', data, receiptSchema(workspaceViewSchema), options, operationKey(key));
export const getWorkspacePreference = (options?: AiRequestOptions) => result<WorkspacePreference>('getWorkspacePreference', undefined, workspacePreferenceSchema, options);
export const updateWorkspacePreference = (data: UpdateWorkspacePreferenceRequest, key: string, options?: AiRequestOptions) => result<CommandReceipt<WorkspacePreference>>('updateWorkspacePreference', data, receiptSchema(workspacePreferenceSchema), options, operationKey(key));
export const getWorkspaceCommandReceipt = <T, >(operationId: string, options?: AiRequestOptions) => result<CommandReceipt<T>>('getWorkspaceCommandReceipt', {operationId}, receiptSchema(z.unknown()), options);
export const checkWorkspaceAccess = (scope: {
  tenantId: string;
  workspaceId?: string
}, resourceType: 'tenant' | 'workspace', action: string, options?: AiRequestOptions) => result<WorkspaceAccessResponse>('access/checkWorkspaceAccess', {
  scope,
  resourceType,
  action
}, z.object({allowed: z.boolean(), reason: z.string(), checkedAt: z.string()}), options);
