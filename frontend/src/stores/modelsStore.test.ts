import {beforeEach, describe, expect, it, vi} from 'vitest';
import {listModelConfigs} from '@/services/ant-design-pro/ai.rbac';
import {useModelsStore} from './modelsStore';

vi.mock('@/services/ant-design-pro/ai.rbac', () => ({listModelConfigs: vi.fn()}));

describe('authorized model catalog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useModelsStore.setState({models: [], initialized: false, loading: false, error: null});
  });

  it('loads every page so public models beyond the first page remain selectable', async () => {
    vi.mocked(listModelConfigs)
      .mockResolvedValueOnce({records: [{id: 1}], total: 2})
      .mockResolvedValueOnce({records: [{id: 2, publicFlag: true}], total: 2});
    const result = await useModelsStore.getState().loadModels();
    expect(result.map((model) => model.id)).toEqual([1, 2]);
    expect(listModelConfigs).toHaveBeenNthCalledWith(2, {current: 2, size: 100});
  });

  it('refresh removes withdrawn public configurations', async () => {
    vi.mocked(listModelConfigs).mockResolvedValueOnce({records: [{id: 1}], total: 1});
    await useModelsStore.getState().loadModels();
    vi.mocked(listModelConfigs).mockResolvedValueOnce({records: [], total: 0});
    await useModelsStore.getState().refreshModels();
    expect(useModelsStore.getState().models).toEqual([]);
  });
});
