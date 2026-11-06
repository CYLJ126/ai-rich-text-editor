import {z} from 'zod';
import {AiApiError, type AiRequestOptions, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';

const base = '/arte/workspace/ai';
const version = z.number().int().nonnegative();
const origins = z.array(z.string().min(1).max(2048)).max(64);
const view = z.object({version, origins});
export const egressPolicyUpdate = z.object({expectedVersion: version, origins}).strict();
export type EgressPolicyUpdate = z.infer<typeof egressPolicyUpdate>;
export type EgressPolicy = z.infer<typeof view>;

export async function getEgressPolicy(options?: AiRequestOptions): Promise<EgressPolicy> {
  const response = await postResult<unknown>('getEgressPolicy', {}, options, undefined, base);
  const parsed = view.safeParse(response.body.data);
  if (!parsed.success) throw new AiApiError(response.httpStatus, {code: 'INVALID_EGRESS_POLICY_RESPONSE'});
  return parsed.data;
}

export async function updateEgressPolicy(request: EgressPolicyUpdate, key: string): Promise<EgressPolicy> {
  requireIdempotencyKey(key);
  const response = await postResult<unknown>('updateEgressPolicy', egressPolicyUpdate.parse(request), undefined, key, base);
  const parsed = z.object({value: view, resultVersion: version, status: z.literal(1)}).safeParse(response.body.data);
  if (!parsed.success || parsed.data.resultVersion !== request.expectedVersion + 1
    || parsed.data.value.version !== parsed.data.resultVersion)
    throw new AiApiError(response.httpStatus, {code: 'INVALID_EGRESS_POLICY_RESPONSE'});
  return parsed.data.value;
}
