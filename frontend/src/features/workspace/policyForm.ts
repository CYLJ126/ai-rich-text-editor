import {type PolicyRules, policyRulesSchema, type PolicySnapshot} from '@/services/platform/policy';

export type PolicyFormValues = Omit<PolicyRules, 'extraCreationRoles'> & {
  extraRoles: { resourceType: string; roles: (0 | 1 | 2 | 3)[] }[];
  membersMayCreateWorkspace?: boolean;
};
export const resourceOptions = [
  {value: 'article', label: '文章'}, {value: 'collection', label: '集合'},
  {value: 'model', label: '模型'}, {value: 'assistant', label: '助手'},
];
export const roleOptions = [
  {value: 0, label: '负责人'}, {value: 1, label: '管理员'}, {value: 2, label: '成员'}, {value: 3, label: '访客'},
];
export const targetOptions = [{value: 0, label: '具体成员'}, {value: 1, label: '空间角色'}, {value: 2, label: '空间'}];

export function toPolicyForm(snapshot: PolicySnapshot): PolicyFormValues {
  return policyRulesForm(snapshot.value.policy.rules,
    snapshot.kind === 'tenant' ? snapshot.value.policy.membersMayCreateWorkspace : undefined);
}

export function policyRulesForm(source: PolicyRules, membersMayCreateWorkspace?: boolean): PolicyFormValues {
  const {extraCreationRoles, ...rules} = source;
  return {
    ...rules, extraRoles: Object.entries(extraCreationRoles).map(([resourceType, roles]) => ({resourceType, roles})),
    ...(membersMayCreateWorkspace !== undefined ? {membersMayCreateWorkspace} : {})
  };
}

export function fromPolicyForm(values: PolicyFormValues): PolicyRules {
  const {extraRoles, membersMayCreateWorkspace: _members, ...rules} = values;
  if (!Array.isArray(extraRoles) || extraRoles.some(row => !row.resourceType || !Array.isArray(row.roles)))
    throw new Error('请完整填写额外创建角色的资源类型和角色列表。');
  if (new Set(extraRoles.map(row => row.resourceType)).size !== extraRoles.length)
    throw new Error('同一资源类型只能配置一组额外创建角色。');
  return policyRulesSchema.parse({
    ...rules,
    extraCreationRoles: Object.fromEntries(extraRoles.map(row => [row.resourceType, row.roles]))
  });
}

/** 示例只补充明确的 AI 字段，不改写创建、导出、分享等原规则；仍需用户保存。 */
export function deepSeekDraft(values: PolicyFormValues): PolicyFormValues {
  return {
    ...values, personalCredentialsAllowed: true,
    allowedProtocols: [...new Set([...values.allowedProtocols, 'chat-completions-sse'])],
    allowedProviders: [...new Set([...values.allowedProviders, 'deepseek'])],
    allowedDestinations: [...new Set([...values.allowedDestinations, 'api.deepseek.com'])]
  };
}

const names: Record<keyof PolicyRules | 'membersMayCreateWorkspace', string> = {
  personalCredentialsAllowed: '个人凭据',
  guestAiAllowed: '访客 AI',
  allowedProtocols: '协议',
  allowedProviders: '供应商',
  allowedDestinations: '目的地主机',
  allowedCreationTypes: '允许创建的资源',
  extraCreationRoles: '额外创建角色',
  exportTypes: '允许导出的资源',
  egressTypes: '允许外发的资源',
  shareTargetKinds: '允许分享的目标',
  membersMayCreateWorkspace: '成员创建空间',
};
const resources = (value: string) => resourceOptions.find(option => option.value === value)?.label ?? value;

function text(field: string, value: unknown): string {
  if (typeof value === 'boolean') return value ? '允许' : '禁止';
  if (Array.isArray(value)) return value.map(item => field === 'shareTargetKinds'
    ? targetOptions.find(option => option.value === item)?.label ?? String(item)
    : ['allowedCreationTypes', 'exportTypes', 'egressTypes'].includes(field) ? resources(String(item)) : String(item)).sort().join('、') || '无';
  if (value && typeof value === 'object') return Object.entries(value).sort(([a], [b]) => a.localeCompare(b))
    .map(([type, roles]) => `${resources(type)}：${(roles as number[]).map(role => roleOptions.find(option => option.value === role)?.label ?? role).sort().join('、') || '无'}`).join('；') || '无';
  return '无';
}

export function policyChanges(snapshot: PolicySnapshot, draft: PolicyFormValues | undefined) {
  if (!draft) return [];
  try {
    const proposed = fromPolicyForm(draft);
    const before: Record<string, unknown> = {...snapshot.value.policy.rules};
    const after: Record<string, unknown> = {...proposed};
    if (snapshot.kind === 'tenant') {
      before.membersMayCreateWorkspace = snapshot.value.policy.membersMayCreateWorkspace;
      after.membersMayCreateWorkspace = draft.membersMayCreateWorkspace;
    }
    return Object.entries(names).flatMap(([field, label]) => {
      const current = text(field, before[field]);
      const next = text(field, after[field]);
      return current === next ? [] : [{key: field, field: label, current, draft: next}];
    });
  } catch {
    return [];
  }
}
