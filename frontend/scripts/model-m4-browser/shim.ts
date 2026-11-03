/** 内存契约夹具，无业务后端、数据库、真实密钥或供应商访问。 */
import {createBrowserHistory} from 'history';

export const history = createBrowserHistory();
export const useModel = () => ({
  initialState: {
    currentUser: {
      id: 'm4-fixture',
      menus: ['EnvironmentModels', 'EnvironmentWorkspaces']
    }
  }
});
export const getLocale = () => 'zh-CN';
const scope = (workspaceId: string) => ({tenantId: 'fixture', workspaceId});
const workspaces = ['A', 'B'].map(workspaceId => ({
  scope: scope(workspaceId),
  name: `空间 ${workspaceId}`,
  state: 0,
  currentRole: 0,
  version: 1,
  defaultWorkspace: workspaceId === 'A'
}));
let preference = {defaultScope: scope('A'), lastScope: null, startupScope: null, favorites: [], version: 0};
const ref = (type: string, id: string) => ({type, id, version: 'v1'});
const model = {
  resourceId: 'actual-1',
  binding: ref('binding', 'actual-1'),
  name: 'DeepSeek 测试实际模型',
  kind: 'TENANT',
  enabled: true,
  connection: ref('connection', 'connection-1'),
  rate: ref('rate', 'rate-1')
};
const configurations = new Map<string, {
  scope: ReturnType<typeof scope>;
  enabled: boolean;
  models: unknown[];
  templateVersion: number;
  version: number
}>();
const rules = new Map<string, { scope: ReturnType<typeof scope>; rules: unknown[]; version: number }>();
const versions = new Map([['A', 1], ['B', 1]]);
const receipts = new Map<string, unknown>();
let resources: unknown[] = [];
const view = (id: string) => ({
  mode: 'DATABASE',
  configuration: configurations.get(id) ?? null,
  workspaceVersion: versions.get(id),
  canManage: true,
  needsImport: false,
  templateVersion: 1,
  models: [model],
  defaults: null
});
const ruleView = (id: string) => ({
  scope: scope(id),
  configuration: rules.get(id) ?? null,
  workspaceVersion: versions.get(id),
  canManage: true,
  canRaiseBudget: true,
  approvedCaps: [{rate: model.rate, limit: {amount: '100', currency: 'CNY'}}]
});
export const fixtureFacts = () => ({
  configurations: Object.fromEntries(configurations),
  rules: Object.fromEntries(rules),
  preference,
  resources
});

export async function request(url: string, options: {
  data: Record<string, any>;
  headers: Record<string, string>;
  signal?: AbortSignal
}) {
  if (options.signal?.aborted) throw new DOMException('Aborted', 'AbortError');
  const request = options.data ?? {}, id = request.scope?.workspaceId ?? 'A', method = url.split('/').at(-1);
  let data: unknown;
  const key = options.headers['Idempotency-Key'];
  const receipt = (value: unknown, resultVersion: number) => ({
    operationId: key,
    value,
    resultVersion,
    status: 1,
    delivery: 0,
    acceptedAt: 'now',
    completedAt: 'now'
  });
  if (key && receipts.has(key)) data = receipts.get(key);
  else switch (method) {
    case 'getWorkspacePreference':
      data = preference;
      break;
    case 'browseAccessibleWorkspaces':
      data = {records: workspaces, nextCursor: null, exhausted: true};
      break;
    case 'getWorkspace':
      data = workspaces.find(item => item.scope.workspaceId === id);
      break;
    case 'checkWorkspaceAccess':
      data = {allowed: true, reason: 'ALLOWED', checkedAt: 'now'};
      break;
    case 'updateWorkspacePreference':
      preference = {...preference, ...request, version: preference.version + 1};
      data = receipt(preference, preference.version);
      break;
    case 'getConfiguration':
      data = view(id);
      break;
    case 'listModelOptions':
      data = {items: [model], nextCursor: null};
      break;
    case 'getBudgetRules':
      data = ruleView(id);
      break;
    case 'listBudgetRateOptions':
      data = {items: [{rate: model.rate, kind: 'MODEL', currency: 'CNY', configured: true}], nextCursor: null};
      break;
    case 'prepareConfiguration':
      configurations.set(id, {scope: scope(id), enabled: true, models: [], templateVersion: 1, version: 0});
      rules.set(id, {scope: scope(id), rules: [], version: 0});
      versions.set(id, (versions.get(id) ?? 1) + 1);
      data = receipt(view(id), 0);
      break;
    case 'updateConfiguration':
      configurations.set(id, {
        ...configurations.get(id)!,
        enabled: request.enabled,
        models: request.models,
        version: request.expectedVersion + 1
      });
      versions.set(id, (versions.get(id) ?? 1) + 1);
      data = receipt(view(id), request.expectedVersion + 1);
      break;
    case 'updateBudgetRules':
      rules.set(id, {scope: scope(id), rules: request.rules, version: request.expectedRuleVersion + 1});
      versions.set(id, (versions.get(id) ?? 1) + 1);
      data = receipt(ruleView(id), request.expectedRuleVersion + 1);
      break;
    case 'getModelCreationOptions':
      data = ['PERSONAL', 'TENANT'];
      break;
    case 'listModelResources':
      data = {records: resources, nextCursor: null};
      break;
    case 'createModelResource': {
      const created = {
        resourceId: `created-${resources.length + 1}`,
        scope: request.scope,
        name: request.name,
        createdBy: 'm4-fixture',
        ownershipType: request.modelKind === 'TENANT' ? 'TENANT' : 'WORKSPACE',
        state: 'DRAFT',
        bindingId: null,
        version: 0,
        createdAt: 'now'
      };
      resources = [...resources, created];
      data = receipt(created, 0);
      break;
    }
    case 'getModelResource':
      data = resources.find((item: any) => item.resourceId === request.resourceId);
      break;
    case 'getModelDraft':
      data = {
        draft: null,
        credential: {resourceId: request.resourceId, state: 'MISSING', version: 0, mayMaintain: true}
      };
      break;
    case 'listConnectionTemplates':
    case 'batchCheckResourceAccess':
      data = [];
      break;
    case 'listModelResourceVersions':
      data = {current: request.current, size: 10, total: 0, records: []};
      break;
    case 'listTenantMembers':
      data = {current: 1, size: 100, total: 0, records: []};
      break;
    case 'getModelPlans':
      data = {version: 1, plans: []};
      break;
    case 'getConnections':
      data = {version: 1, connections: []};
      break;
    case 'getCatalog':
      data = {
        version: 1,
        catalog: {reviewedPlans: [], connections: [], rates: [], defaults: {enabled: true, rateCaps: []}}
      };
      break;
    default:
      return {status: 403, data: {success: false, code: 'FIXTURE_UNSUPPORTED', desc: `夹具不模拟 ${method}`}};
  }
  if (key) receipts.set(key, data);
  return {status: 200, data: {success: true, code: '200', desc: 'OK', data: structuredClone(data)}};
}
