import {i18nText} from '@/utils/i18n';
import {Flex, InputNumber, message, Tooltip, Typography} from 'antd';
import React, {forwardRef, useCallback, useEffect, useMemo, useState,} from 'react';
import {ModelSelector} from '@/components';
import {useArticleInfoStore, useEditorStore} from '@/components/Article';
import {ArticleSummary, CharacterCount,} from '@/components/Article/components';
import {saveArticleSummary} from '@/services/ant-design-pro/richText';
import type {ModelConfig} from '@/types/ai.model.type';
import {type AiContextSettings, clampPercentage, DEFAULT_AI_CONTEXT_SETTINGS,} from '@/utils/ai';
import EmptySidebar from './EmptySidebar';

const { Text } = Typography;

interface WritingManagerProps {
  height?: number | string;
  currentUser: string | undefined;
}

type WritingManagerRef = object;

const WritingManager = forwardRef<WritingManagerRef, WritingManagerProps>(
  ({ height = 'auto', currentUser }, _ref) => {
    const editor = useEditorStore((state) => state.editor);
    const articleInfo = useArticleInfoStore((state) => state.articleInfo);
    const setArticleInfo = useArticleInfoStore((state) => state.setArticleInfo);
    const characterCount = useArticleInfoStore((state) => state.characterCount);
    const [currentModel, setCurrentModel] = useState<ModelConfig | undefined>();
    const [continuationCharacterCountCeil, setContinuationCharacterCountCeil] =
      useState<number>(200);
    const [contextSettings, setContextSettings] =
      useState<AiContextSettings>(DEFAULT_AI_CONTEXT_SETTINGS);
    const effectiveContextSettings = useMemo<AiContextSettings>(
      () => ({
        ...contextSettings,
        contextCharacterCount: Math.min(
          contextSettings.contextCharacterCount,
          characterCount,
        ),
      }),
      [characterCount, contextSettings],
    );

    const syncEditorAiModel = useCallback(
      (
        model: ModelConfig | undefined,
        characterCountCeil: number,
        aiContextSettings: AiContextSettings,
      ): void => {
        if (!editor || editor.isDestroyed) return;
        editor.commands.setAiModel({
          ...model,
          continuationCharacterCountCeil: characterCountCeil,
          ...aiContextSettings,
        });
      },
      [editor],
    );

    const handleModelSelect = useCallback(
      (model: ModelConfig): void => {
        setCurrentModel(model);

        // ModelSelector 的回调和 React effect 之间存在一个渲染窗口。
        // 在这里同步挂载模型，避免用户选择后立即触发补全/续写时，
        // editor.aiModel.id 仍为 undefined，导致 JSON.stringify 省略 modelId。
        syncEditorAiModel(
          model,
          continuationCharacterCountCeil,
          effectiveContextSettings,
        );
      },
      [
        continuationCharacterCountCeil,
        effectiveContextSettings,
        syncEditorAiModel,
      ],
    );

    const updateSummary = useCallback(
      (id: number, summary: string): void => {
        if (articleInfo?.id !== id) return;
        const temp = {
          ...articleInfo,
          summary,
        };
        setArticleInfo(temp);
        if (editor && !editor.isDestroyed) {
          editor.commands.setArticleInfo(temp);
        }
        // 向后端更新总结摘要
        saveArticleSummary(id, summary).then(() => message.success(i18nText("app.article.sidebar.writingmanager.72fe37cb")));
      },
      [articleInfo, setArticleInfo, editor],
    );

    // 选中的模型挂到编辑器上，AI 补全等编辑器内部能力从 editor.aiModel 取
    // 编辑器实例重建时也要重新挂载
    useEffect(() => {
      syncEditorAiModel(
        currentModel,
        continuationCharacterCountCeil,
        effectiveContextSettings,
      );
    }, [
      currentModel,
      continuationCharacterCountCeil,
      effectiveContextSettings,
      syncEditorAiModel,
    ]);

    const updateContextSetting = useCallback(
      (key: keyof AiContextSettings, value: number | null): void => {
        const fallbackValue = DEFAULT_AI_CONTEXT_SETTINGS[key];
        const normalizedValue =
          key === 'contextCharacterCount'
            ? Math.min(characterCount, Math.max(0, value ?? fallbackValue))
            : clampPercentage(value ?? fallbackValue);
        const nextSettings = {...contextSettings, [key]: normalizedValue};

        // 前后文共同瓜分上下文预算，保持两项之和为 100%。
        if (key === 'beforeContextRatio') {
          nextSettings.afterContextRatio = 100 - normalizedValue;
        } else if (key === 'afterContextRatio') {
          nextSettings.beforeContextRatio = 100 - normalizedValue;
        }

        setContextSettings(nextSettings);
        const effectiveNextSettings = {
          ...nextSettings,
          contextCharacterCount: Math.min(
            nextSettings.contextCharacterCount,
            characterCount,
          ),
        };
        syncEditorAiModel(
          currentModel,
          continuationCharacterCountCeil,
          effectiveNextSettings,
        );
      },
      [
        contextSettings,
        characterCount,
        currentModel,
        continuationCharacterCountCeil,
        syncEditorAiModel,
      ],
    );

    if (!articleInfo) {
      return <EmptySidebar />;
    }

    return (
      <div className="p-2" style={{ height }}>
        <Flex vertical gap="small" className="items-center">
          <ModelSelector width="100%" onSelect={handleModelSelect} />
          <CharacterCount characterCount={characterCount} />
          <Flex align="center" justify="space-between" className="w-full">
            <Tooltip title={i18nText("app.article.sidebar.writingmanager.91c75304")}>
              <Text type="secondary" className="text-sm whitespace-nowrap">
                {i18nText("app.article.sidebar.writingmanager.dd1ba7a2")}
              </Text>
            </Tooltip>
            <InputNumber
              min={0}
              max={characterCount}
              step={50}
              changeOnWheel
              value={effectiveContextSettings.contextCharacterCount}
              onChange={(value) =>
                updateContextSetting('contextCharacterCount', value)
              }
              style={{ width: 120 }}
            />
          </Flex>
          <Flex align="center" justify="space-between" className="w-full">
            <Text type="secondary" className="text-sm whitespace-nowrap">
              {i18nText("app.article.sidebar.writingmanager.5e5f07aa")}
            </Text>
            <InputNumber
              min={0}
              max={100}
              step={5}
              suffix="%"
              changeOnWheel
              value={contextSettings.beforeContextRatio}
              onChange={(value) =>
                updateContextSetting('beforeContextRatio', value)
              }
              style={{ width: 120 }}
            />
          </Flex>
          <Flex align="center" justify="space-between" className="w-full">
            <Text type="secondary" className="text-sm whitespace-nowrap">
              {i18nText("app.article.sidebar.writingmanager.cb6fbd7f")}
            </Text>
            <InputNumber
              min={0}
              max={100}
              step={5}
              suffix="%"
              changeOnWheel
              value={contextSettings.afterContextRatio}
              onChange={(value) =>
                updateContextSetting('afterContextRatio', value)
              }
              style={{ width: 120 }}
            />
          </Flex>
          <Flex align="center" justify="space-between" className="w-full">
            <Text type="secondary" className="text-sm whitespace-nowrap">
              {i18nText("app.article.sidebar.writingmanager.10ca2c0a")}
            </Text>
            <InputNumber
              min={50}
              max={2000}
              step={50}
              changeOnWheel
              value={continuationCharacterCountCeil}
              onChange={(value) => {
                const nextValue = value ?? 200;
                setContinuationCharacterCountCeil(nextValue);
                syncEditorAiModel(
                  currentModel,
                  nextValue,
                  effectiveContextSettings,
                );
              }}
              style={{ width: 120 }}
            />
          </Flex>
          <ArticleSummary
            articleInfo={articleInfo}
            onSummaryUpdate={updateSummary}
            currentUser={currentUser}
            currentModel={currentModel}
          />
        </Flex>
      </div>
    );
  },
);

export default WritingManager;
