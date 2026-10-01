import {request} from '@umijs/max';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {
  getToolUpgradePreview,
  getToolVersionDetail,
  listAssistantToolOptions,
  listToolCatalog,
  listToolProviders,
  publishToolVersion,
  saveToolBinding,
} from './ai.tool';

vi.mock('@umijs/max', () => ({
  request: vi.fn(),
}));

describe('AI tool management service', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(request).mockResolvedValue({success: true, data: null});
  });

  it('maps ProTable pageSize to the backend size parameter', async () => {
    await listToolCatalog({
      current: 3,
      pageSize: 50,
      keyword: 'summary',
      lifecycleState: 'draft',
    });

    expect(request).toHaveBeenCalledWith('/arte/ai/tools/catalog', {
      method: 'GET',
      params: {
        current: 3,
        keyword: 'summary',
        lifecycleState: 'draft',
        size: 50,
      },
      headers: {},
    });
  });

  it('encodes every part of an exact tool version identity', async () => {
    await getToolVersionDetail({
      namespace: 'article/internal',
      name: 'summary tool',
      version: '1.0.0+draft',
    });

    expect(request).toHaveBeenCalledWith(
      '/arte/ai/tools/article%2Finternal/summary%20tool/1.0.0%2Bdraft/management',
      {method: 'GET', headers: {}},
    );
  });

  it('forwards provider status and type filters', async () => {
    await listToolProviders({status: 'enabled', providerType: 'mcp'});

    expect(request).toHaveBeenCalledWith('/arte/ai/tools/providers', {
      method: 'GET',
      params: {status: 'enabled', providerType: 'mcp'},
      headers: {},
    });
  });

  it('publishes an explicit compatibility baseline and user-facing notes', async () => {
    const reference = {namespace: 'local', name: 'query', version: '1.0.1'};
    const command = {compatibilityBaseVersion: '1.0.0', releaseNotes: '修复名称处理'};
    await publishToolVersion(reference, command);
    expect(request).toHaveBeenCalledWith('/arte/ai/tools/local/query/1.0.1/publish', {
      method: 'POST', data: command, headers: {'Content-Type': 'application/json'},
    });
    await getToolUpgradePreview(reference, '1.0.0');
    expect(request).toHaveBeenLastCalledWith('/arte/ai/tools/local/query/1.0.1/upgrade-preview', {
      method: 'GET', params: {baseVersion: '1.0.0'}, headers: {},
    });
  });

  it('updates version choice on the same binding while retaining configuration', async () => {
    const command = {
      bindingId: 'stable-id', expectedRowVersion: 3,
      tool: {namespace: 'local', name: 'query', version: '2.0.0'},
      versionPolicy: 'pinned' as const, configuration: {locale: 'zh'}, credentialReference: 'cred-ref'
    };
    await saveToolBinding(command);
    expect(request).toHaveBeenCalledWith('/arte/ai/tool-bindings', {
      method: 'POST', data: command, headers: {'Content-Type': 'application/json'},
    });
  });

  it('loads assistant options from the current-user scoped endpoint', async () => {
    await listAssistantToolOptions();

    expect(request).toHaveBeenCalledWith('/arte/ai/assistants/tool-options', {
      method: 'GET',
      headers: {},
    });
  });
});
