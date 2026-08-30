import React, {memo, useCallback, useEffect, useMemo, useRef, useState,} from 'react';
import {Select, Tag, Tooltip} from 'antd';
import {createStyles} from 'antd-style';
import {useModelsStore} from '@/stores/modelsStore';
import type {ModelConfig} from '@/types/ai.type';
import {MyDynamicIcon} from "@/components";

const useStyles = createStyles(({ token, css }) => ({
  selector: css`
    height: 30px;

    .ant-select-selector {
      border-radius: 8px !important;
      font-size: 12px !important;
      height: 30px !important;
      padding: 0 10px !important;
    }

    .ant-select-selection-item {
      line-height: 28px !important;
      display: flex;
      align-items: center;
      gap: 4px;
    }
  `,
  optionRow: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    width: 100%;
  `,
  optionLeft: css`
    display: flex;
    flex: 1;
    align-items: center;
    gap: 6px;
    min-width: 0;
    overflow: hidden;
  `,
  optionName: css`
    overflow: hidden;
    font-size: 14px;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,

  capabilities: css`
    display: flex;
    flex-shrink: 0;
    gap: 2px;
  `,

  tag: css`
    flex-shrink: 0;
    height: 16px;
    margin-inline-end: 0;
    padding: 0 4px;
    border-radius: 3px;
    font-size: 10px;
    line-height: 14px;
  `,
}));

// 模型能力标签颜色
const CAPABILITY_COLORS: Record<string, string> = {
  vision: 'blue',
  code: 'green',
  math: 'purple',
  search: 'orange',
  thinking: 'magenta',
  long: 'cyan',
  function: 'gold',
};

interface ModelCapabilityProps {
  cap: string;
  className?: string;
}

const ModelCapability = memo<ModelCapabilityProps>(
  ({ cap, className }) => {
    return (
      <Tag
        color={CAPABILITY_COLORS[cap] ?? 'default'}
        className={className}
      >
        {cap}
      </Tag>
    );
  },
);

ModelCapability.displayName = 'ModelCapability';

interface ModelSelectorProps {
  width?: string | number;
  onSelect?: (value: ModelConfig) => void;
}

// ─── 模型选择器组件：下拉选择模型，下拉列表是全局的，在初始或编辑模型时更新 ───
const ModelSelector: React.FC<ModelSelectorProps> = memo(
  ({ width = 330, onSelect }) => {
    const { styles } = useStyles();

    const loading = useModelsStore((state) => state.loading);
    const models = useModelsStore((state) => state.models);
    const loadModels = useModelsStore((state) => state.loadModels);

    /**
     * 只保存模型 ID，避免 Store 刷新后 activeModel
     * 仍然引用旧的 ModelConfig 对象。
     */
    const [activeModelId, setActiveModelId] = useState<string>();

    /**
     * 保存最新的 onSelect，避免父组件每次渲染创建新函数时，
     * 触发默认模型选择 Effect。
     */
    const onSelectRef = useRef(onSelect);

    /**
     * 用于避免 React Strict Mode 下默认选择回调重复执行。
     */
    const lastAutoSelectedModelIdRef = useRef<string | undefined>(undefined);

    useEffect(() => {
      onSelectRef.current = onSelect;
    }, [onSelect]);

    /**
     * 模型初始化加载。
     *
     * loadModels 内部已经实现：
     * 1. 初始化状态判断
     * 2. 请求去重
     * 3. loading 管理
     * 4. Strict Mode 重复调用保护
     */
    useEffect(() => {
      void loadModels().catch((error: unknown) => {
        console.error('加载模型配置失败：', error);
      });
    }, [loadModels]);

    /**
     * 模型列表加载完成后自动选择第一个可用模型。
     *
     * 如果当前模型仍然存在，则保持当前选择。
     * 如果当前模型被删除或者被禁用，则自动切换。
     */
    useEffect(() => {
      if (models.length === 0) {
        setActiveModelId(undefined);
        lastAutoSelectedModelIdRef.current = undefined;
        return;
      }

      const currentModel = models.find(
        (model) => model.modelId === activeModelId,
      );

      if (currentModel && currentModel.status !== 3) {
        return;
      }

      const defaultModel = models.find(
        (model) => model.status !== 3,
      );

      if (!defaultModel) {
        setActiveModelId(undefined);
        return;
      }

      setActiveModelId(defaultModel.modelId);

      if (
        lastAutoSelectedModelIdRef.current !== defaultModel.modelId
      ) {
        lastAutoSelectedModelIdRef.current = defaultModel.modelId;
        onSelectRef.current?.(defaultModel);
      }
    }, [activeModelId, models]);

    const handleSelect = useCallback(
      (modelId: string) => {
        const selectedModel = models.find(
          (model) => model.modelId === modelId,
        );

        if (!selectedModel) {
          return;
        }

        setActiveModelId(selectedModel.modelId);
        lastAutoSelectedModelIdRef.current = selectedModel.modelId;
        onSelectRef.current?.(selectedModel);
      },
      [models],
    );

    const options = useMemo(
      () =>
        models.map((model) => ({
          value: model.modelId,
          disabled: model.status === 3,
          label: (
            <div className={styles.optionRow}>
              <div className={styles.optionLeft}>
                <MyDynamicIcon iconName={model.icon || 'openai'} />
                <span className={styles.optionName}>
                  {model.modelName}
                </span>
              </div>

              <div className={styles.capabilities}>
                {!!model.supportFunction && (
                  <ModelCapability
                    cap="function"
                    className={styles.tag}
                  />
                )}

                {!!model.supportSearch && (
                  <ModelCapability
                    cap="search"
                    className={styles.tag}
                  />
                )}

                {!!model.supportThinking && (
                  <ModelCapability
                    cap="thinking"
                    className={styles.tag}
                  />
                )}

                {!!model.supportVision && (
                  <ModelCapability
                    cap="vision"
                    className={styles.tag}
                  />
                )}
              </div>
            </div>
          ),
        })),
      [
        models,
        styles.capabilities,
        styles.optionLeft,
        styles.optionName,
        styles.optionRow,
        styles.tag,
      ],
    );

    return (
      <Tooltip title="选择模型">
        <Select<string>
          className={styles.selector}
          style={{ width }}
          loading={loading}
          value={activeModelId}
          onChange={handleSelect}
          options={options}
          placeholder="选择模型"
          popupMatchSelectWidth={false}
          popupStyle={{ minWidth: 240 }}
          size="small"
          variant="outlined"
          allowClear={false}
        />
      </Tooltip>
    );
  },
);

ModelSelector.displayName = 'ModelSelector';

export default ModelSelector;
