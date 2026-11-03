import {z} from 'zod';
import {AiApiError, type AiRequestOptions, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';
import {modelResourceSchema} from './model';
import {type WorkspaceScope, workspaceScopeSchema} from './workspace';

const id = z.string().min(1).max(256);
const version = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
const ids = z.array(id).max(256).refine(values => new Set(values).size === values.length);
const located = {scope: workspaceScopeSchema, resourceId: id};
const target = z.object({...located, resourceType: z.literal('model')}).strict();
const role = z.enum(['OWNER', 'ADMIN', 'MEMBER', 'GUEST']);
export const modelGrantActions = ['model:read', 'model:edit', 'model:validate', 'model:publish', 'model:use', 'model:disable', 'model:share', 'resource:access:manage'] as const;
const actions = z.array(z.enum(modelGrantActions)).min(1).max(8).refine(values => new Set(values).size === values.length);
const recipient = z.discriminatedUnion('kind', [
  z.object({kind: z.literal('SUBJECT'), subjectId: id}).strict(),
  z.object({kind: z.literal('SPACE_ROLE'), role}).strict(),
  z.object({kind: z.literal('SPACE')}).strict(),
]);
const expiry = z.iso.datetime({offset: true}).optional();
export const sharingCommandSchema = z.discriminatedUnion('kind', [
  z.object({
    kind: z.literal('create'),
    request: z.object({scope: workspaceScopeSchema, name: z.string().trim().min(1).max(100)}).strict()
  }).strict(),
  z.object({
    kind: z.literal('scopes'),
    request: z.object({...located, expectedVersion: version, workspaceIds: ids}).strict()
  }).strict(),
  z.object({
    kind: z.literal('consumers'),
    request: z.object({
      ...located,
      expectedVersion: version,
      credentialVersion: version.min(1),
      subjectIds: ids
    }).strict()
  }).strict(),
  z.object({
    kind: z.literal('grant'),
    request: z.object({target, expectedAclVersion: version, recipient, actions, expiresAt: expiry}).strict()
  }).strict(),
  z.object({
    kind: z.literal('revoke'),
    request: z.object({target, grantId: id, expectedAclVersion: version, expectedGrantVersion: version}).strict()
  }).strict(),
  z.object({
    kind: z.literal('visibility'), request: z.object({
      target, expectedAclVersion: version, visibility: z.enum(['EXPLICIT', 'SPACE_SHARED']),
      sharedGrants: z.array(z.object({recipient, actions, expiresAt: expiry}).strict()).max(100)
    }).strict()
  }).strict(),
]);
export type SharingCommand = z.infer<typeof sharingCommandSchema>;
export const sharingPolicySchema = z.object({
  ...located,
  workspaceIds: ids,
  credentialVersion: version,
  subjectIds: ids,
  version
});
const viewSchema = z.object({
  policy: sharingPolicySchema, mayManageScopes: z.boolean(), credential: z.object({
    resourceId: id, state: z.enum(['MISSING', 'CONFIGURED', 'REVOKED']),
    version, mayMaintain: z.boolean(), kind: z.enum(['PERSONAL', 'TENANT'])
  })
});
export type SharingView = z.infer<typeof viewSchema>;
const resource = z.object({tenantId: id, type: z.literal('model'), resourceId: id});
const accessSchema = z.object({
  resource,
  ownership: z.object({tenantId: id, workspaceId: id.nullable(), type: z.union([z.literal(0), z.literal(1)])}),
  visibility: z.enum(['EXPLICIT', 'SPACE_SHARED']),
  aclVersion: version
});
const grantSchema = z.object({
  id, resource, recipient: z.object({
    kind: z.enum(['SUBJECT', 'SPACE_ROLE', 'SPACE']), subjectId: id.nullable(),
    scope: workspaceScopeSchema.nullable(), role: role.nullable()
  }), action: id, state: z.enum(['ACTIVE', 'REVOKED']), expiresAt: z.string().nullable(), source: id, version
});
export type ModelAccess = z.infer<typeof accessSchema>;
export type ModelGrant = z.infer<typeof grantSchema>;
const page = <T extends z.ZodType>(value: T) => z.object({
  current: version.min(1),
  size: version.min(1).max(100),
  total: version,
  records: z.array(value).max(100)
});
const invalid = (status = 200) => new AiApiError(status, {code: 'INVALID_MODEL_SHARING_RESPONSE'});
const same = (a: WorkspaceScope, b: WorkspaceScope) => a.tenantId === b.tenantId && a.workspaceId === b.workspaceId;
const refMatches = (value: {
  tenantId: string;
  resourceId: string
}, scope: WorkspaceScope, resourceId: string) => value.tenantId === scope.tenantId && value.resourceId === resourceId;

async function call<T extends z.ZodType>(base: string, path: string, request: unknown, schema: T, options?: AiRequestOptions, key?: string): Promise<z.infer<T>> {
  const response = await postResult<unknown>(path, request, options, key, `/arte/${base}`);
  const value = schema.safeParse(response.body.data);
  if (!value.success) throw invalid(response.httpStatus);
  return value.data;
}

export async function getModelSharing(scope: WorkspaceScope, resourceId: string, options?: AiRequestOptions) {
  const value = await call('ai-new/model-sharing', 'getModelSharing', {scope, resourceId}, viewSchema, options);
  if (!same(value.policy.scope, scope) || value.policy.resourceId !== resourceId || value.credential.resourceId !== resourceId) throw invalid();
  return value;
}

export async function getModelAccess(scope: WorkspaceScope, resourceId: string, options?: AiRequestOptions) {
  const value = await call('resource/access', 'getResourceAccess', {
    target: {
      scope,
      resourceType: 'model',
      resourceId
    }
  }, accessSchema, options);
  if (!refMatches(value.resource, scope, resourceId) || value.ownership.tenantId !== scope.tenantId
    || value.ownership.workspaceId !== null && value.ownership.workspaceId !== scope.workspaceId) throw invalid();
  return value;
}

export async function listModelGrants(scope: WorkspaceScope, resourceId: string, current = 1, options?: AiRequestOptions) {
  const value = await call('resource/access', 'listResourceGrants', {
    target: {scope, resourceType: 'model', resourceId},
    current,
    size: 20
  }, page(grantSchema), options);
  if (value.current !== current || value.records.some(g => !refMatches(g.resource, scope, resourceId))) throw invalid();
  return value;
}

export const listTenantModelMembers = (scope: WorkspaceScope, current = 1, options?: AiRequestOptions) =>
  call('ai-new/model-sharing', 'listTenantModelMembers', {scope, current}, page(z.object({
    subjectId: id,
    role: z.string(),
    state: z.string()
  })), options);

export async function executeSharingCommand(command: SharingCommand, key: string) {
  requireIdempotencyKey(key);
  const value = sharingCommandSchema.parse(command);
  const shared = ['create', 'scopes', 'consumers'].includes(value.kind);
  const path = {
    create: 'createModelResource', scopes: 'updateModelUseScopes', consumers: 'updateModelCredentialConsumers',
    grant: 'grantResourceAccess', revoke: 'revokeResourceGrant', visibility: 'changeResourceVisibility'
  }[value.kind];
  const envelope = z.object({
    operationId: id, status: z.literal(1), delivery: z.union([z.literal(0), z.literal(1)]), resultVersion: version,
    acceptedAt: z.string(), completedAt: z.string().nullable(), value: z.unknown().optional()
  });
  const result = await call(value.kind === 'create' ? 'ai-new/model-resource' : shared ? 'ai-new/model-sharing' : 'resource/access', path,
    value.kind === 'create' ? {...value.request, modelKind: 'TENANT'} : value.request, envelope, undefined, key);
  if (value.kind === 'create') {
    const model = modelResourceSchema.safeParse(result.value);
    if (!model.success || model.data.ownershipType !== 'TENANT' || !same(model.data.scope, value.request.scope) || model.data.name !== value.request.name || model.data.version !== 0 || result.resultVersion !== 0) throw invalid();
    return model.data;
  }
  const expected = 'expectedVersion' in value.request ? value.request.expectedVersion : value.request.expectedAclVersion;
  if (result.resultVersion !== expected + 1) throw invalid();
  if (value.kind === 'scopes' || value.kind === 'consumers') {
    const policy = sharingPolicySchema.safeParse(result.value);
    if (!policy.success || policy.data.resourceId !== value.request.resourceId || !same(policy.data.scope, value.request.scope) || policy.data.version !== result.resultVersion) throw invalid();
    const requested = value.kind === 'scopes' ? value.request.workspaceIds : value.request.subjectIds;
    const returned = value.kind === 'scopes' ? policy.data.workspaceIds : policy.data.subjectIds;
    if (requested.length !== returned.length || requested.some(id => !returned.includes(id))
      || value.kind === 'consumers' && policy.data.credentialVersion !== value.request.credentialVersion) throw invalid();
  } else if (value.kind === 'grant') {
    const grants = z.object({grants: z.array(grantSchema).min(1), aclVersion: version}).safeParse(result.value);
    if (!grants.success || grants.data.aclVersion !== result.resultVersion || grants.data.grants.some(g => !refMatches(g.resource, value.request.target.scope, value.request.target.resourceId))) throw invalid();
  } else if (value.kind === 'visibility') {
    const access = accessSchema.safeParse(result.value);
    if (!access.success || access.data.aclVersion !== result.resultVersion || access.data.visibility !== value.request.visibility || !refMatches(access.data.resource, value.request.target.scope, value.request.target.resourceId)) throw invalid();
  } else if (result.value != null) throw invalid();
  return null;
}
