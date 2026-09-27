import {request} from '@umijs/max';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {getToolVersionDetail, listToolCatalog, listToolProviders,} from './ai.tool';

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
});
