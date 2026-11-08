import {AiApiError, type AiRequestOptions, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';
import type {AiScope, BudgetAccountRef} from '@/services/arte-ai';
import {z} from 'zod';

const version = z.number().int().nonnegative();
const target = (scope: AiScope, ref: BudgetAccountRef) => ({scope, resourceType: ref.type, resourceId: ref.resourceId});
const ref = z.object({tenantId: z.string().min(1), type: z.literal('budget-account'), resourceId: z.string().min(1)});
const grant = z.object({
  resource: ref, id: z.string(), recipient: z.object({kind: z.literal('SUBJECT'), subjectId: z.string()}),
  action: z.string(), state: z.enum(['ACTIVE', 'REVOKED']), version
});
export type AccountGrant = z.infer<typeof grant>;
const base = '/arte/resource/access';

export async function accountResponsibility(scope: AiScope, accountRef: BudgetAccountRef, options?: AiRequestOptions) {
  const query = {target: target(scope, accountRef)};
  const access = await postResult<{ aclVersion: number }>('getResourceAccess', query, options, undefined, base);
  if (!version.safeParse(access.body.data.aclVersion).success) throw new AiApiError(200, access.body, '账户授权版本无效。');
  const values: AccountGrant[] = [];
  const seen = new Set<string>();
  let total: number | undefined;
  for (let current = 1; current <= 100; current++) {
    const response = await postResult<{
      current: number;
      size: number;
      total: number;
      records: unknown[]
    }>('listResourceGrants', {...query, current, size: 100}, options, undefined, base);
    const page = response.body.data;
    if (page.current !== current || page.size !== 100 || !Number.isSafeInteger(page.total) || page.total < 0 || page.total > 10000
      || (total !== undefined && total !== page.total) || !Array.isArray(page.records) || page.records.length > 100) throw new AiApiError(200, response.body, '账户授权分页无效。');
    total = page.total;
    for (const value of page.records) {
      const parsed = grant.safeParse(value);
      if (!parsed.success || seen.has(parsed.data.id) || parsed.data.resource.tenantId !== accountRef.tenantId || parsed.data.resource.type !== accountRef.type || parsed.data.resource.resourceId !== accountRef.resourceId) throw new AiApiError(200, response.body, '账户授权记录无效。');
      seen.add(parsed.data.id);
      values.push(parsed.data);
    }
    if (current * 100 >= page.total) return {aclVersion: access.body.data.aclVersion, grants: values};
  }
  throw new Error('账户授权记录超过读取上限。');
}

export function assignAccountReconciler(scope: AiScope, accountRef: BudgetAccountRef, subjectId: string, expectedAclVersion: number, key: string, options?: AiRequestOptions) {
  return postResult('grantResourceAccess', {
    target: target(scope, accountRef), expectedAclVersion,
    recipient: {kind: 'SUBJECT', subjectId}, actions: ['budget-account:reconcile']
  }, options, requireIdempotencyKey(key), base);
}

export function revokeAccountReconciler(scope: AiScope, accountRef: BudgetAccountRef, value: Pick<AccountGrant, 'id' | 'version'>, expectedAclVersion: number, key: string, options?: AiRequestOptions) {
  return postResult('revokeResourceGrant', {
    target: target(scope, accountRef),
    grantId: value.id,
    expectedAclVersion,
    expectedGrantVersion: value.version
  }, options, requireIdempotencyKey(key), base);
}
