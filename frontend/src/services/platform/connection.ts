import {z} from 'zod';
import {AiApiError, type AiRequestOptions, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';

const id = z.string().min(1).max(256);
const revision = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
const reference = (type: string) => z.object({type: z.literal(type), id, version: id});
export const connectionSettingsSchema = z.object({
  id: id.trim().min(1).refine(value => !/[\x00-\x1f\x7f]/.test(value)),
  providerId: z.literal('deepseek'),
  endpoint: z.string().trim().max(2048).refine(value => {
    try {
      const url = new URL(value);
      return ['https:', 'http:'].includes(url.protocol) && !!url.hostname && !url.username && !url.password && !url.search && !url.hash;
    } catch {
      return false;
    }
  }, '请填写不含凭据、查询参数和片段的 HTTP(S) 基础地址'),
  connectTimeoutSeconds: z.number().int().min(1).max(60),
  responseTimeoutSeconds: z.number().int().min(1).max(900),
  maxResponseBytes: z.number().int().min(1).max(32 * 1024 * 1024),
}).strict();
const connectionSchema = z.object({
  reference: reference('connection'), providerId: id, protocol: reference('protocol'), endpoint: z.string(),
  state: z.enum(['ENABLED', 'DISABLED']), connectTimeoutSeconds: z.number().positive(),
  responseTimeoutSeconds: z.number().positive(), maxResponseBytes: z.number().int().positive(),
});
const viewSchema = z.object({
  version: revision,
  initialized: z.boolean(),
  connections: z.array(connectionSchema).max(256),
  allowedOrigins: z.array(z.string()),
}).refine(value => value.initialized === (value.version > 0))
  .refine(value => new Set(value.connections.map(c => JSON.stringify(c.reference))).size === value.connections.length);

export const connectionCommandSchema = z.discriminatedUnion('kind', [
  z.object({
    kind: z.literal('publish'),
    request: z.object({expectedVersion: revision, settings: connectionSettingsSchema}).strict()
  }).strict(),
  z.object({
    kind: z.literal('state'),
    request: z.object({expectedVersion: revision, id, enabled: z.boolean()}).strict()
  }).strict(),
]);
export type ConnectionCommand = z.infer<typeof connectionCommandSchema>;
export type ConnectionSettings = z.infer<typeof connectionSettingsSchema>;
export type ConnectionView = z.infer<typeof viewSchema>;
export type ManagedConnection = z.infer<typeof connectionSchema>;
const base = '/arte/workspace/ai';

export async function getConnections(options?: AiRequestOptions): Promise<ConnectionView> {
  const result = await postResult<unknown>('getConnections', {}, options, undefined, base);
  const parsed = viewSchema.safeParse(result.body.data);
  if (!parsed.success) throw new AiApiError(result.httpStatus, {code: 'INVALID_CONNECTION_RESPONSE'});
  return parsed.data;
}

export async function executeConnectionCommand(command: ConnectionCommand, key: string): Promise<ConnectionView> {
  requireIdempotencyKey(key);
  const validated = connectionCommandSchema.parse(command);
  const result = await postResult<unknown>(validated.kind === 'publish' ? 'publishConnection' : 'setConnectionEnabled',
    validated.request, undefined, key, base);
  const parsed = z.object({
    operationId: id, status: z.literal(1), delivery: z.union([z.literal(0), z.literal(1)]), value: viewSchema,
    resultVersion: revision, acceptedAt: z.string(), completedAt: z.string().nullable(),
  }).safeParse(result.body.data);
  if (!parsed.success || parsed.data.value.version !== validated.request.expectedVersion + 1)
    throw new AiApiError(result.httpStatus, {code: 'INVALID_CONNECTION_RESPONSE'});
  return parsed.data.value;
}
