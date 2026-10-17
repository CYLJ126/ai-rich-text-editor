// 验收专用：仅空间发现／模型选项使用固定数据。会话、搜索、轮次和结果一律真实 HTTP。
import messages from '../../src/locales/zh-CN/aiChat';

const scope = {tenantId: 'search-tenant', workspaceId: 'search-space'};
const model = {
  displayName: '验收不调用模型',
  binding: {type: 'binding', id: 'binding', version: 'v1'},
  capability: {type: 'capability', id: 'text', version: 'v1'},
  contextWindowTokens: 65536,
  limits: {
    maxInputTokens: 60000,
    maxOutputTokens: 4096,
    maxInputBytes: 65536,
    maxOutputBytes: 1000000,
    maxTimeoutSeconds: 120
  },
  defaults: {maxInputTokens: 32768, maxOutputTokens: 512, timeoutSeconds: 60},
  budgetRefs: ['budget']
};
export const metrics: Record<string, number> = {};

export async function request(url: string, options: any) {
  const path = url.split('/').pop()!;
  metrics[path] = (metrics[path] ?? 0) + 1;
  if (url.includes('/ai-new/conversation/') || path === 'getInvocationSnapshots') {
    const response = await fetch(url, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(options.data),
      signal: options.signal
    });
    return {status: response.status, data: await response.json()};
  }
  let data: unknown;
  if (path === 'getWorkspacePreference') data = {
    defaultScope: scope,
    startupScope: null,
    lastScope: null,
    favorites: [],
    version: 0
  };
  else if (path === 'browseAccessibleWorkspaces') data = {
    records: [{
      scope,
      name: 'S5 隔离验收空间',
      state: 0,
      currentRole: 0,
      version: 0,
      defaultWorkspace: true
    }], nextCursor: null, exhausted: true
  };
  else if (path === 'getWorkspace') data = {
    scope,
    name: 'S5 隔离验收空间',
    description: '固定测试身份／真实检索依赖',
    state: 0,
    currentRole: 0,
    version: 0
  };
  else if (path === 'checkWorkspaceAccess') data = {
    allowed: true,
    reason: 'ALLOWED',
    checkedAt: '2026-10-16T12:00:00Z'
  };
  else if (path === 'ensure') data = {needsImport: false};
  else if (path === 'discoverChatOptions') data = {options: [model]};
  else if (path === 'getBudget') data = {
    budgetRef: 'budget',
    currency: 'CNY',
    limit: '100',
    held: '0',
    charged: '0',
    available: '100',
    rateVersion: {type: 'rate', id: 'rate', version: 'v1'},
    version: 0
  };
  else return {status: 404, data: {success: false, code: '205018', desc: '此验收环境未开放该操作'}};
  return {status: 200, data: {success: true, code: '000000', desc: '成功', data}};
}

export const getLocale = () => 'zh-CN';
export const useModel = () => ({initialState: {currentUser: {id: 'search-subject'}}});
export const useIntl = () => ({
  formatMessage: ({
                    id,
                    defaultMessage
                  }: any) => messages[id as keyof typeof messages] ?? defaultMessage ?? id
});
