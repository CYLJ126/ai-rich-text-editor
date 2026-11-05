import {z} from 'zod';
import {AiApiError, type AiRequestOptions, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';
import {workspaceScopeSchema} from './workspace';
import type {WorkspaceScope} from './workspaceTypes';

const id = z.string().min(1).max(256);
const version = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
const positive = version.min(1);
const ref = (type: string) => z.object({
  type: z.literal(type),
  id,
  version: id.refine(value => value.toLowerCase() !== 'latest')
});
export const modelRefSchema = ref('binding');
export const connectionRefSchema = ref('connection');
export const modelResourceSchema = z.object({
  resourceId: id, scope: workspaceScopeSchema, name: z.string().min(1).max(100), createdBy: id,
  state: z.enum(['DRAFT', 'PUBLISHED', 'DISABLED']), bindingId: id.nullable(), version, createdAt: z.string(),
  ownershipType: z.enum(['WORKSPACE', 'TENANT']).optional(),
});
export const modelFieldsSchema = z.object({
  name: z.string().trim().min(1).max(100), planRef: ref('model-plan'),
  contextWindowTokens: positive.max(10_000_000), maxOutputTokens: positive.max(1_000_000),
}).refine(value => value.maxOutputTokens < value.contextWindowTokens, '输出上限须小于上下文窗口');
const draftSchema = z.object({
  resource: z.object({tenantId: id, type: z.literal('model'), resourceId: id}), scope: workspaceScopeSchema,
  name: z.string(), settings: z.object({
    planRef: ref('model-plan'),
    contextWindowTokens: positive.max(10_000_000), maxOutputTokens: positive
  }), plan: z.object({
    planRef: ref('model-plan'),
    displayName: id,
    connection: connectionRefSchema,
    remoteModel: id,
    features: z.array(id),
    contextWindowTokens: positive,
    rate: ref('rate'),
    publishedAt: id,
    publishedBy: id,
    catalogRevision: positive
  }),
  version: positive, updatedAt: z.string(),
});
const credentialSchema = z.object({
  resourceId: id,
  state: z.enum(['MISSING', 'CONFIGURED', 'REVOKED']),
  version,
  mayMaintain: z.boolean(), kind: z.enum(['PERSONAL', 'TENANT']).optional(),
});
export const modelDraftSchema = z.object({draft: draftSchema.nullable(), credential: credentialSchema});
export const modelValidationSchema = z.object({
  validationId: id, resourceId: id, draftVersion: positive, credentialVersion: positive,
  state: z.enum(['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'STALE']), code: z.string().max(96),
  expiresAt: z.string(), checkedAt: z.string().nullable(),
});
export const modelVersionSchema = z.object({
  resourceId: id, binding: modelRefSchema, capability: ref('capability'), connection: connectionRefSchema,
  providerId: id, remoteOperation: id, contextWindowTokens: positive, rate: ref('rate'),
  source: z.enum(['RESOURCE', 'FIXED', 'DATABASE', 'PERSONAL']),
  catalogRevision: positive.nullable(),
  releaseRef: id,
  linkedBy: id,
  linkedAt: z.string(),
  planRef: ref('model-plan').nullable(),
  credentialKind: z.enum(['PERSONAL', 'TENANT']).nullable(),
});
const templateSchema = z.object({reference: connectionRefSchema, providerId: id, protocol: ref('protocol')});
const optionSchema = z.object({
  planRef: ref('model-plan'),
  rate: ref('rate'),
  catalogRevision: positive,
  contextWindowTokens: positive,
  displayName: id,
  connection: connectionRefSchema,
  remoteModel: id,
  features: z.array(id)
});
const statusSchema = z.object({
  binding: modelRefSchema,
  valid: z.boolean(),
  reason: z.string().max(96),
  maxOutputTokens: positive
});
const receipt = <T extends z.ZodType>(value: T) => z.object({
  operationId: id, status: z.literal(1), delivery: z.union([z.literal(0), z.literal(1)]), value,
  resultVersion: version, acceptedAt: z.string(), completedAt: z.string().nullable(),
});
const page = <T extends z.ZodType>(item: T) => z.object({
  records: z.array(item).max(100),
  current: positive,
  size: positive.max(100),
  total: version
});

/** 只输出校验及白名单化后的安全 DTO，未知字段不流入管理页面或恢复记录。 */
async function call<T extends z.ZodType>(base: string, path: string, data: unknown, schema: T, options?: AiRequestOptions, key?: string) {
  if (key !== undefined) requireIdempotencyKey(key);
  const result = await postResult<unknown>(path, data, options, key, `/arte/${base}`);
  const parsed = schema.safeParse(result.body.data);
  if (!parsed.success) throw new AiApiError(result.httpStatus, {code: 'INVALID_MODEL_RESPONSE'}, 'Invalid model management response');
  return {...result, body: {...result.body, data: parsed.data as z.output<T>}};
}

const target = (scope: WorkspaceScope, resourceId: string) => ({scope, resourceType: 'model', resourceId});
const sameScope = (left: WorkspaceScope, right: WorkspaceScope) => left.tenantId === right.tenantId && left.workspaceId === right.workspaceId;

function assertResponse(condition: boolean) {
  if (!condition) throw new AiApiError(200, {code: 'INVALID_MODEL_RESPONSE'}, 'Model response identity mismatch');
}

export type ManagedModel = z.infer<typeof modelResourceSchema>;
export type ModelDraft = z.infer<typeof modelDraftSchema>;
export type ModelFields = z.infer<typeof modelFieldsSchema>;
export type ModelVersion = z.infer<typeof modelVersionSchema>;
export type ModelValidation = z.infer<typeof modelValidationSchema>;
export type ModelTemplate = z.infer<typeof templateSchema>;
export type PublicationOption = z.infer<typeof optionSchema>;
export type PublicationStatus = z.infer<typeof statusSchema>;
export const MODEL_ACTIONS = ['model:edit', 'model:validate', 'model:publish', 'model:disable', 'model:use'] as const;
export type ModelAction = typeof MODEL_ACTIONS[number];

/** 当前归属创建资格快照；服务端提交仍重新核验。 */
export const getModelCreationOptions = (scope: WorkspaceScope, options?: AiRequestOptions) =>
  call('ai-new/model-resource', 'getModelCreationOptions', {scope}, z.array(z.enum(['PERSONAL', 'TENANT'])).max(2), options);

export type ModelListFilter = {
  search?: string;
  modelKind?: ManagedModel['ownershipType'];
  state?: ManagedModel['state']
};

export async function listManagedModels(scope: WorkspaceScope, cursor: string | null = null,
                                        options?: AiRequestOptions, filter: ModelListFilter = {}) {
  const {search, modelKind, state} = filter;
  const result = await call('ai-new/model-draft', 'listModelResources', {
      scope,
      cursor,
      size: 20, ...(search ? {search} : {}), ...(modelKind ? {modelKind} : {}), ...(state ? {state} : {})
    },
    z.object({records: z.array(modelResourceSchema).max(100), nextCursor: z.string().max(129).nullable()}), options);
  assertResponse(result.body.data.records.every(item => sameScope(item.scope, scope)
      && (!modelKind || item.ownershipType === modelKind) && (!state || item.state === state))
    && new Set(result.body.data.records.map(item => item.resourceId)).size === result.body.data.records.length);
  return result;
}

export const listModelTemplates = (scope: WorkspaceScope, options?: AiRequestOptions) => call('ai-new/model-draft', 'listConnectionTemplates', {scope}, z.array(templateSchema).max(256), options);

export async function getManagedModel(scope: WorkspaceScope, resourceId: string, options?: AiRequestOptions) {
  const result = await call('ai-new/model-resource', 'getModelResource', {
    scope,
    resourceId
  }, modelResourceSchema, options);
  assertResponse(result.body.data.resourceId === resourceId && sameScope(result.body.data.scope, scope));
  return result;
}

export async function getModelDraft(scope: WorkspaceScope, resourceId: string, options?: AiRequestOptions) {
  const result = await call('ai-new/model-draft', 'getModelDraft', {scope, resourceId}, modelDraftSchema, options);
  const value = result.body.data;
  assertResponse(value.credential.resourceId === resourceId && (!value.draft || sameScope(value.draft.scope, scope)
    && value.draft.resource.tenantId === scope.tenantId && value.draft.resource.resourceId === resourceId));
  return result;
}

export async function listModelVersions(scope: WorkspaceScope, resourceId: string, current = 1, options?: AiRequestOptions) {
  const result = await call('ai-new/model-resource', 'listModelResourceVersions', {
    scope,
    resourceId,
    current,
    size: 10
  }, page(modelVersionSchema), options);
  assertResponse(result.body.data.current === current && result.body.data.records.every(item => item.resourceId === resourceId));
  return result;
}

/** 有界分页取得可采用方案；草稿未保存时同样可查询，不推断连接或价格。 */
export async function listPublicationOptions(scope: WorkspaceScope, resourceId: string, options?: AiRequestOptions) {
  const records: PublicationOption[] = [];
  let cursor: string | null = null;
  let revision: number | null = null;
  const seen = new Set<string>();
  const seenCursors = new Set<string>();
  do {
    const response = await call('ai-new/model-draft', 'listReviewedModelPlans', {scope, resourceId, cursor, size: 100},
      z.object({
        records: z.array(draftSchema.shape.plan).max(100),
        catalogRevision: positive,
        nextCursor: z.string().max(129).nullable()
      }), options);
    const page: {
      records: z.infer<typeof draftSchema.shape.plan>[];
      catalogRevision: number;
      nextCursor: string | null
    } = response.body.data;
    if (revision !== null && revision !== page.catalogRevision) throw new AiApiError(409, {code: 'MODEL_PLAN_REVISION_CHANGED'});
    revision = page.catalogRevision;
    for (const item of page.records) {
      const key = JSON.stringify(item.planRef);
      assertResponse(!seen.has(key));
      seen.add(key);
      records.push({...item, catalogRevision: page.catalogRevision});
    }
    cursor = page.nextCursor;
    if (cursor) {
      assertResponse(!seenCursors.has(cursor) && seenCursors.size < 256);
      seenCursors.add(cursor);
    }
    assertResponse(records.length <= 256);
    if (!cursor) return {...response, body: {...response.body, data: records}};
  } while (seen.size < 256);
  throw new AiApiError(200, {code: 'INVALID_MODEL_RESPONSE'});
}

export async function getPublicationStatus(scope: WorkspaceScope, resourceId: string, binding: ModelVersion['binding'], options?: AiRequestOptions) {
  const result = await call('ai-new/model-resource', 'getModelPublicationStatus', {
    scope,
    resourceId,
    binding
  }, statusSchema, options);
  assertResponse(result.body.data.binding.type === binding.type && result.body.data.binding.id === binding.id
    && result.body.data.binding.version === binding.version);
  return result;
}

export async function getModelValidation(scope: WorkspaceScope, validationId: string, options?: AiRequestOptions) {
  const result = await call('ai-new/model-draft', 'getModelValidation', {
    scope,
    validationId
  }, modelValidationSchema, options);
  assertResponse(result.body.data.validationId === validationId);
  return result;
}

export async function checkModelActions(scope: WorkspaceScope, resourceId: string, options?: AiRequestOptions): Promise<Set<ModelAction>> {
  const requests = MODEL_ACTIONS.map(action => ({target: target(scope, resourceId), action}));
  const result = await call('resource/access', 'batchCheckResourceAccess', {requests}, z.array(z.object({
    target: z.object({scope: workspaceScopeSchema, resourceType: z.literal('model'), resourceId: id}),
    action: z.enum(MODEL_ACTIONS), allowed: z.boolean(), reason: z.string(), checkedAt: z.string(),
  })).length(requests.length), options);
  result.body.data.forEach((item, index) => assertResponse(sameScope(item.target.scope, scope) && item.target.resourceId === resourceId && item.action === requests[index].action));
  return new Set(result.body.data.filter(item => item.allowed).map(item => item.action));
}

const located = {scope: workspaceScopeSchema, resourceId: id};
/** 恢复记录的白名单请求。凭据命令刻意没有 token 字段，禁止将密钥持久化到浏览器。 */
export const modelCommandSchema = z.discriminatedUnion('kind', [
  z.object({
    kind: z.literal('create'),
    key: id,
    request: z.object({
      scope: workspaceScopeSchema,
      name: z.string().trim().min(1).max(100),
      modelKind: z.enum(['PERSONAL', 'TENANT'])
    }).strict()
  }).strict(),
  z.object({
    kind: z.literal('save'),
    key: id,
    request: z.object({
      ...located, expectedVersion: version, name: modelFieldsSchema.shape.name,
      settings: z.object({
        planRef: ref('model-plan'),
        contextWindowTokens: positive,
        maxOutputTokens: positive
      }).strict()
        .refine(value => value.maxOutputTokens < value.contextWindowTokens)
    }).strict()
  }).strict(),
  z.object({
    kind: z.literal('credential'),
    key: id,
    request: z.object({
      ...located,
      expectedVersion: version,
      credentialKind: z.enum(['PERSONAL', 'TENANT']).optional()
    }).strict()
  }).strict(),
  z.object({
    kind: z.literal('revoke'),
    key: id,
    request: z.object({...located, expectedVersion: positive}).strict()
  }).strict(),
  z.object({
    kind: z.literal('validate'),
    key: id,
    request: z.object({...located, draftVersion: positive, credentialVersion: positive}).strict()
  }).strict(),
  z.object({
    kind: z.literal('publish'), key: id, request: z.object({
      ...located, expectedVersion: version, draftVersion: positive, credentialVersion: positive,
      validationId: id, planRef: ref('model-plan'), catalogRevision: positive
    }).strict()
  }).strict(),
  z.object({
    kind: z.literal('disable'),
    key: id,
    request: z.object({...located, expectedVersion: version}).strict()
  }).strict(),
]);
export type ModelCommand = z.infer<typeof modelCommandSchema>;

export async function executeModelCommand(command: ModelCommand, token?: string) {
  const {kind, key, request} = modelCommandSchema.parse(command);
  switch (kind) {
    case 'create':
      return (await call('ai-new/model-resource', 'createModelResource', request, receipt(modelResourceSchema), undefined, key)).body.data;
    case 'save':
      return (await call('ai-new/model-draft', 'saveModelDraft', request, receipt(draftSchema), undefined, key)).body.data;
    case 'credential': {
      if (!token || token.length > 8192 || /[^\x21-\x7e]/.test(token)) throw new TypeError('请填写原操作使用的有效密钥');
      return (await call('ai-new/model-draft', 'replaceModelCredential', {
        ...request,
        token
      }, receipt(credentialSchema), undefined, key)).body.data;
    }
    case 'revoke':
      return (await call('ai-new/model-draft', 'revokeModelCredential', request, receipt(credentialSchema), undefined, key)).body.data;
    case 'validate':
      return (await call('ai-new/model-draft', 'validateModelDraft', request,
        z.object({receipt: receipt(modelValidationSchema), current: modelValidationSchema}), undefined, key)).body.data;
    case 'publish':
      return (await call('ai-new/model-resource', 'publishModel', request, receipt(modelVersionSchema), undefined, key)).body.data;
    case 'disable':
      return (await call('ai-new/model-resource', 'disableModelResource', request, receipt(modelResourceSchema), undefined, key)).body.data;
  }
}
