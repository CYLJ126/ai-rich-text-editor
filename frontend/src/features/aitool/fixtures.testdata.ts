import type {ToolBindingDetail, ToolDetail, ToolSettings, ToolSummary,} from '@/services/arte-ai/tool';

export const scope = {tenantId: 'tenant', workspaceId: 'workspace'};
export const tool: ToolSummary = {
  tool: {type: 'tool', id: 'text.count', version: 'v1'},
  provider: {type: 'tool-provider', id: 'local', version: 'v1'},
  title: '文本统计',
  description: '统计文本长度，仅处理输入文本，不访问外部服务。',
  tags: ['文本'],
  features: [],
  outcomeQuerySupported: false,
  risk: {
    sideEffect: 'READ_ONLY',
    destructive: false,
    idempotency: 'NONE',
    requiredScopes: [],
    networkOrigins: [],
  },
  defaultPolicy: {
    timeoutMillis: 10000,
    maxAttempts: 1,
    retryBackoffMillis: 0,
    maxInputBytes: 10000,
    maxOutputBytes: 10000,
    approvalMode: 'NONE',
  },
  release: {
    state: 'PUBLISHED',
    version: '1',
    permitsNewBinding: true,
    existingBindingsUntil: null,
  },
};
export const settings: ToolSettings = {
  tool: tool.tool,
  name: '我的文本统计',
  enabled: true,
  versionPolicy: 'PINNED',
  configurationJson: '{"decimal":0.123456789012345678901}',
  secrets: {},
  policy: null,
  networkOrigins: null,
};
const document = {
  type: 'object',
  properties: {
    decimal: {type: 'number'},
    text: {type: 'string', title: '统计说明'},
  },
};
const schema = {
  definition: {type: 'schema', id: 'config', version: 'v1'},
  document,
  documentJson: JSON.stringify(document),
  checksum: 'a'.repeat(64),
};
export const toolDetail: ToolDetail = {
  tool,
  configurationSchema: schema,
  inputSchema: schema,
  outputSchema: schema,
};
export const detail: ToolBindingDetail = {
  binding: {
    bindingId: 'default-tool-one',
    toolId: tool.tool.id,
    version: '9007199254740993',
    name: settings.name,
    enabled: true,
    createdAt: '2026-10-19T00:00:00Z',
    updatedAt: '2026-10-19T00:00:00Z',
  },
  revision: {
    revision: {type: 'binding', id: 'default-tool-one', version: 'r1'},
    settings,
    createdAt: '2026-10-19T00:00:00Z',
  },
};
