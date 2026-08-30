import {create} from 'zustand';
import {listModelConfigs} from '@/services/ant-design-pro/ai.rbac';
import type {ModelConfig} from '@/types/ai.type';

interface ModelsState {
  loading: boolean; // 是否正在加载
  initialized: boolean; // 是否已经完成过初始化加载
  error: unknown | null; // 最近一次加载错误
  models: ModelConfig[]; // 模型列表
  /**
   * 设置模型列表。
   * 直接设置模型列表时，认为已经完成初始化。
   */
  setModels: (newModels: ModelConfig[] | ((previousModels: ModelConfig[]) => ModelConfig[])) => void;
  /**
   * 加载模型列表。
   *
   * @param force 是否强制重新请求
   */
  loadModels: (force?: boolean) => Promise<ModelConfig[]>;
  /**
   * 强制刷新模型列表
   */
  refreshModels: () => Promise<ModelConfig[]>;
  /**
   * 将模型列表标记为需要重新加载。
   * 该方法本身不会立即发起请求。
   */
  invalidateModels: () => void;
  /**
   * 根据数据库主键获取模型
   */
  getModelById: (id: number | null | undefined) => ModelConfig | null;
  /**
   * 根据 modelId 获取模型
   */
  getModelByModelId: (modelId: string | null | undefined) => ModelConfig | null;
}

/**
 * 当前正在执行的模型请求。
 *
 * 放在 Store 外部用于请求去重：
 * 1. React Strict Mode 下 Effect 重复执行时复用同一个请求
 * 2. 多个 ModelSelector 同时挂载时复用同一个请求
 */
let pendingLoadPromise: Promise<ModelConfig[]> | null = null;

export const useModelsStore = create<ModelsState>((set, get) => ({
  loading: false,
  initialized: false,
  error: null,
  models: [],

  setModels: (newModels) => {
    set((state) => ({
      models:
        typeof newModels === 'function'
          ? newModels(state.models)
          : newModels,
      initialized: true,
      error: null,
    }));
  },

  loadModels: (force = false) => {
    const currentState = get();
    // 已初始化并且不是强制刷新时，直接返回现有数据。即使 models 是空数组，也不会重复请求
    if (currentState.initialized && !force) {
      return Promise.resolve(currentState.models);
    }
    // 已经存在进行中的请求，直接复用
    if (pendingLoadPromise) {
      return pendingLoadPromise;
    }

    set({ loading: true, error: null });

    pendingLoadPromise = listModelConfigs({})
      .then((response) => {
        const models = response?.records ?? [];
        set({ models, initialized: true, error: null });
        return models;
      })
      .catch((error: unknown) => {
        set({ initialized: false, error });
        throw error;
      })
      .finally(() => {
        set({ loading: false });
        pendingLoadPromise = null;
      });
    return pendingLoadPromise;
  },

  refreshModels: () => {
    return get().loadModels(true);
  },

  invalidateModels: () => {
    set({ initialized: false });
  },

  getModelById: (id) => {
    // 不使用 if (!id)，因为理论上 id 可能为 0
    if (id == null) {
      return null;
    }
    return get().models.find((model) => model.id === id) ?? null;
  },

  getModelByModelId: (modelId) => {
    if (!modelId) {
      return null;
    }
    return (
      get().models.find((model) => model.modelId === modelId) ?? null
    );
  },
}));
