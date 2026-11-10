import {z} from 'zod';
import {AiApiError, type AiRequestOptions, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';
import {workspaceScopeSchema, workspaceViewSchema} from './workspace';

const id = z.string().min(1).max(256);
const version = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
const base = '/arte/workspace';
const blocker = z.object({domain: id, workKey: id, code: id, retryable: z.boolean()});
export const closureSchema = z.object({
  operationId: id,
  scope: workspaceScopeSchema,
  state: z.union([z.literal(0), z.literal(1), z.literal(2), z.literal(3), z.literal(4)]),
  blockers: z.array(blocker).max(1000),
  version
});
const preview = z.object({
  scope: workspaceScopeSchema, blockers: z.array(blocker).max(1000),
  retainedResources: z.record(z.string(), version), workspaceVersion: version
});
const capabilities = z.object({
  archive: z.boolean(),
  restore: z.boolean(),
  close: z.boolean(),
  closureInstalled: z.boolean(),
  defaultWorkspace: z.boolean()
});
export const lifecycleCommandSchema = z.object({
  action: z.enum(['archive', 'restore', 'close']), scope: workspaceScopeSchema,
  expectedWorkspaceVersion: version, reason: z.string().max(1000), name: z.string().min(1).max(100)
}).strict();
export type LifecycleCommand = z.infer<typeof lifecycleCommandSchema>;
export type ClosureView = z.infer<typeof closureSchema>;
export type ClosurePreview = z.infer<typeof preview>;
export type LifecycleCapabilities = z.infer<typeof capabilities>;
const receipt = z.object({
  operationId: id, status: z.literal(1), delivery: z.union([z.literal(0), z.literal(1)]),
  resultVersion: version, value: z.unknown(), acceptedAt: z.string(), completedAt: z.string().nullish()
});
export type LifecycleReceipt = z.infer<typeof receipt>;
const sameScope = (a: { tenantId: string; workspaceId: string }, b: {
  tenantId: string;
  workspaceId: string
}) => a.tenantId === b.tenantId && a.workspaceId === b.workspaceId;

async function read<T extends z.ZodType>(path: string, body: unknown, schema: T, options?: AiRequestOptions, key?: string) {
  const response = await postResult<unknown>(path, body, options, key, base);
  const parsed = schema.safeParse(response.body.data);
  if (!parsed.success) throw new AiApiError(response.httpStatus, {code: 'INVALID_LIFECYCLE_RESPONSE'}, '生命周期响应无法核验');
  return parsed.data as z.output<T>;
}

export const getLifecycleCapabilities = (scope: LifecycleCommand['scope'], options?: AiRequestOptions) => read('getWorkspaceLifecycleCapabilities', {scope}, capabilities, options);
export const previewClosure = (scope: LifecycleCommand['scope'], options?: AiRequestOptions) => read('previewWorkspaceClosure', {scope}, preview.refine(value => sameScope(value.scope, scope)), options);
export const getClosureProgress = (scope: LifecycleCommand['scope'], operationId: string, options?: AiRequestOptions) =>
  read('getWorkspaceClosureProgress', {
    scope,
    operationId
  }, closureSchema.refine(value => sameScope(value.scope, scope) && value.operationId === operationId), options);
export const listClosures = (current: number, options?: AiRequestOptions) => read('listWorkspaceClosures', {
    current,
    size: 20
  },
  z.object({
    records: z.array(z.object({workspaceName: z.string().min(1).max(100), closure: closureSchema})).max(100),
    current: version.min(1),
    size: version.min(1),
    total: version
  }), options);

export async function executeLifecycle(command: LifecycleCommand, key: string) {
  const value = lifecycleCommandSchema.parse(command);
  requireIdempotencyKey(key);
  const {action, scope, expectedWorkspaceVersion, reason} = value;
  const result = await read({
      archive: 'archiveWorkspace',
      restore: 'unarchiveWorkspace',
      close: 'closeWorkspace'
    }[action],
    {scope, expectedWorkspaceVersion, reason}, receipt, undefined, key);
  const data = action === 'close' ? closureSchema.safeParse(result.value) : workspaceViewSchema.safeParse(result.value);
  const valid = data.success && sameScope(data.data.scope, scope) && data.data.version === result.resultVersion
    && (action === 'close' ? 'operationId' in data.data && data.data.operationId === result.operationId && result.resultVersion === 0
      : data.data.state === (action === 'archive' ? 1 : 0) && result.resultVersion === expectedWorkspaceVersion + 1);
  if (!valid) throw new AiApiError(200, {code: 'INVALID_LIFECYCLE_RESPONSE'}, '生命周期回执范围或结果无法核验');
  return result;
}
