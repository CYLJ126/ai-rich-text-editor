import {z} from 'zod';
import {AiApiError, type AiRequestOptions, postResult, requireIdempotencyKey} from '@/services/arte-ai/request';
import {type WorkspaceScope, workspaceScopeSchema} from './workspace';

const id = z.string().min(1).max(256);
const version = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
const role = z.union([z.literal(0), z.literal(1), z.literal(2), z.literal(3)]);
const strings = z.array(id).max(256).refine(values => new Set(values).size === values.length, '不能填写重复项目');
const roles = z.array(role).max(4).refine(values => new Set(values).size === values.length);
const targets = z.array(z.union([z.literal(0), z.literal(1), z.literal(2)])).max(3)
  .refine(values => new Set(values).size === values.length);

/** 完整 v1 规则；拒绝未知字段／版本，避免页面保存时静默丢失未来政策内容。 */
export const policyRulesSchema = z.object({
  allowedCreationTypes: strings,
  extraCreationRoles: z.record(id, roles),
  guestAiAllowed: z.boolean(),
  personalCredentialsAllowed: z.boolean(),
  allowedProtocols: strings,
  allowedProviders: strings,
  allowedDestinations: strings,
  exportTypes: strings,
  egressTypes: strings,
  shareTargetKinds: targets,
}).strict().superRefine((rules, context) => {
  if (!Object.keys(rules.extraCreationRoles).every(type => rules.allowedCreationTypes.includes(type)))
    context.addIssue({
      code: 'custom',
      path: ['extraCreationRoles'],
      message: '额外创建角色只能配置给已允许创建的资源类型'
    });
});
const policyMeta = {policySchemaVersion: z.literal(1), rolePresetVersion: z.literal(1), version};
const tenantPolicySchema = z.object({
  tenantId: id, rules: policyRulesSchema, membersMayCreateWorkspace: z.boolean(),
  ...policyMeta, personalTemplateVersion: version.min(1).nullish()
});
const workspacePolicySchema = z.object({scope: workspaceScopeSchema, rules: policyRulesSchema, ...policyMeta});
const tenantInfo = {tenantName: z.string().min(1).max(100), tenantType: z.union([z.literal(0), z.literal(1)])};
const tenantViewSchema = z.object({
  policy: tenantPolicySchema,
  tenantVersion: version,
  tenantEpoch: version, ...tenantInfo
});
const workspaceViewSchema = z.object({
  policy: workspacePolicySchema, workspaceVersion: version, workspaceEpoch: version,
  tenantPolicyVersion: version, tenantEpoch: version, ...tenantInfo
});

export const policyCommandSchema = z.discriminatedUnion('kind', [
  z.object({
    kind: z.literal('tenant'), request: z.object({
      tenantId: id, expectedTenantVersion: version,
      expectedPolicyVersion: version, rules: policyRulesSchema, membersMayCreateWorkspace: z.boolean()
    }).strict()
  }).strict(),
  z.object({
    kind: z.literal('workspace'), request: z.object({
      scope: workspaceScopeSchema, expectedWorkspaceVersion: version,
      expectedPolicyVersion: version, rules: policyRulesSchema
    }).strict()
  }).strict(),
]);
export type PolicyKind = 'tenant' | 'workspace';
export type PolicyRules = z.infer<typeof policyRulesSchema>;
export type PolicyCommand = z.infer<typeof policyCommandSchema>;
export type TenantPolicyView = z.infer<typeof tenantViewSchema>;
export type WorkspacePolicyView = z.infer<typeof workspaceViewSchema>;
export type PolicySnapshot = { kind: 'tenant'; value: TenantPolicyView } | {
  kind: 'workspace';
  value: WorkspacePolicyView
};
const base = '/arte/workspace';
const sameScope = (left: WorkspaceScope, right: WorkspaceScope) => left.tenantId === right.tenantId && left.workspaceId === right.workspaceId;
const invalid = (status: number) => new AiApiError(status, {code: 'INVALID_POLICY_RESPONSE'}, '政策响应与当前范围或支持的结构不一致');

export async function getPolicy(kind: PolicyKind, scope: WorkspaceScope, options?: AiRequestOptions): Promise<PolicySnapshot> {
  const target = workspaceScopeSchema.parse(scope);
  const response = await postResult<unknown>(kind === 'tenant' ? 'getTenantPolicy' : 'getWorkspacePolicy',
    kind === 'tenant' ? {tenantId: target.tenantId} : {scope: target}, options, undefined, base);
  if (kind === 'tenant') {
    const parsed = tenantViewSchema.safeParse(response.body.data);
    if (!parsed.success || parsed.data.policy.tenantId !== target.tenantId) throw invalid(response.httpStatus);
    return {kind, value: parsed.data};
  }
  const parsed = workspaceViewSchema.safeParse(response.body.data);
  if (!parsed.success || !sameScope(parsed.data.policy.scope, target)) throw invalid(response.httpStatus);
  return {kind, value: parsed.data};
}

/** 请求只带规则及同次读取的两个 CAS 版本，不接受主体、角色预设或权限代次。 */
export async function executePolicyCommand(command: PolicyCommand, key: string): Promise<void> {
  requireIdempotencyKey(key);
  const parsed = policyCommandSchema.parse(command);
  const response = await postResult<unknown>(parsed.kind === 'tenant' ? 'updateTenantPolicy' : 'updateWorkspacePolicy',
    parsed.request, undefined, key, base);
  const receipt = z.object({
    operationId: id, status: z.literal(1), delivery: z.union([z.literal(0), z.literal(1)]),
    resultVersion: version, acceptedAt: z.string(), completedAt: z.string().nullable(), value: z.unknown()
  }).safeParse(response.body.data);
  if (!receipt.success || receipt.data.resultVersion !== parsed.request.expectedPolicyVersion + 1) throw invalid(response.httpStatus);
  if (parsed.kind === 'tenant') {
    const value = tenantPolicySchema.safeParse(receipt.data.value);
    if (!value.success || value.data.tenantId !== parsed.request.tenantId || value.data.version !== receipt.data.resultVersion)
      throw invalid(response.httpStatus);
  } else {
    const value = workspacePolicySchema.safeParse(receipt.data.value);
    if (!value.success || !sameScope(value.data.scope, parsed.request.scope) || value.data.version !== receipt.data.resultVersion)
      throw invalid(response.httpStatus);
  }
}
