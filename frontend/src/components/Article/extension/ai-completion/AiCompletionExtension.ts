import {Extension} from '@tiptap/core';
import type {ModelConfig} from '@/types/ai.model.type';
import type {AiContextSettings} from '@/utils/ai';
import type {AiCompletionOptions} from './types';
import {AI_COMPLETION_PLUGIN_KEY, createAiCompletionPlugin, dispatchAction} from './AiCompletionPlugin';

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    aiCompletion: {
      setAiModel: (model: AiModelConfig | undefined) => ReturnType;
    };
  }

  interface Editor {
    /** 当前选中的 AI 模型，由外部通过 setAiModel 挂载 */
    readonly aiModel: AiModelConfig | null;
  }
}

export type AiModelConfig = ModelConfig &
  Partial<AiContextSettings> & {
    continuationCharacterCountCeil?: number;
  };

export const AiCompletionExtension = Extension.create<AiCompletionOptions>({
  name: 'aiCompletion',

  addOptions() {
    return {
      enabled: true,
      debounceMs: 400,
      manualTriggerKey: 'ctrl+ ',
      preferCurrentSection: true,
    };
  },

  addStorage() {
    return {
      // 当前选中的 AI 模型，storage 不会被 TipTap 代理克隆，存什么取什么
      model: null as ModelConfig | null,
    };
  },

  onCreate() {
    const {editor} = this;

    Object.defineProperty(editor, 'aiModel', {
      get(): ModelConfig | null {
        return (editor.storage.aiCompletion?.model as ModelConfig | null) ?? null;
      },
      enumerable: false,
      configurable: true,
    });
  },

  onDestroy() {
    const {editor} = this;
    editor.storage.aiCompletion.model = null;
    try {
      Object.defineProperty(editor, 'aiModel', {value: null, configurable: true});
    } catch { /* ignore */
    }
  },

  addProseMirrorPlugins() {
    const getEditor = () => this.editor;
    return [createAiCompletionPlugin(getEditor, this.options)];
  },

  addCommands() {
    return {
      /**
       * 设置补全使用的 AI 模型
       */
      setAiModel:
        (model: AiModelConfig | undefined) =>
          () => {
            this.editor.storage.aiCompletion.model = model ?? null;
            return true;
          },

      /**
       * 切换 AI 补全开关
       */
      toggleAiCompletion:
        (enabled?: boolean) =>
          ({view}: any) => {
            const pluginState = AI_COMPLETION_PLUGIN_KEY.getState(view.state);
            const newEnabled = enabled !== undefined ? enabled : !pluginState?.enabled;
            dispatchAction(view, {type: 'toggle', enabled: newEnabled});
            return true;
          },

      /**
       * 手动触发补全
       */
      triggerAiCompletion:
        () =>
          ({view}: any) => {
            const {selection} = view.state;
            dispatchAction(view, {
              type: 'startLoading',
              triggerFrom: selection.from,
              triggerTo: selection.to,
            });
            return true;
          },

      /**
       * 接受当前补全
       */
      acceptAiCompletion:
        () =>
          ({view}: any) => {
            const pluginState = AI_COMPLETION_PLUGIN_KEY.getState(view.state);
            if (pluginState?.completion?.status === 'done') {
              // acceptCompletion 在 plugin 内部，这里通过 keydown 模拟 Tab 触发
              // 或直接 dispatch accept action（实际插入逻辑在 plugin 的 handleKeyDown 中）
              // 由于插入逻辑耦合在 plugin 内，这里通过自定义 meta 触发
              const fakeTab = new KeyboardEvent('keydown', {key: 'Tab', bubbles: true});
              view.dom.dispatchEvent(fakeTab);
            }
            return true;
          },

      /**
       * 取消当前补全
       */
      dismissAiCompletion:
        () =>
          ({view}: any) => {
            dispatchAction(view, {type: 'dismiss'});
            return true;
          },
    };
  },
} as any);
