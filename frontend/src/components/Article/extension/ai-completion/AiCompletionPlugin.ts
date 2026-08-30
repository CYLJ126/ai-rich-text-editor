import type {Editor} from '@tiptap/core';
import {Plugin, PluginKey} from '@tiptap/pm/state';
import type {DecorationSet, EditorView} from '@tiptap/pm/view';
import {STREAM_COMPLETION_URL, streamChat} from '@/services/ant-design-pro/ai.chat';
import {buildContinuationContext} from '@/utils/ai';
import {extractDocText} from './context-builder';
import {createLiveGhostTextDecoration, createLoadingDecoration, EMPTY_DECORATIONS,} from './decoration';
import {detectTrigger} from './trigger';
import type {AiCompletionOptions, CompletionState} from './types';

export const AI_COMPLETION_PLUGIN_KEY = new PluginKey<AiCompletionPluginState>(
  'aiCompletion',
);

// ─── Plugin State ───
export interface AiCompletionPluginState {
  decorations: DecorationSet;
  completion: CompletionState | null;
  enabled: boolean;
}

// ─── Meta Actions ───
const META_KEY = 'aiCompletionAction';

export type AiCompletionAction =
  | { type: 'startLoading'; triggerFrom: number; triggerTo: number }
  | { type: 'updateText'; text: string }
  | { type: 'complete' }
  | { type: 'accept' }
  | { type: 'dismiss' }
  | { type: 'error'; message: string }
  | { type: 'toggle'; enabled: boolean };

export function dispatchAction(view: EditorView, action: AiCompletionAction) {
  const tr = view.state.tr.setMeta(META_KEY, action);
  view.dispatch(tr);
}

// ─── 将易失状态提升到模块级（插件销毁重建不影响） ───
// 注意：每个 createAiCompletionPlugin 调用对应独立的状态容器
interface PluginRuntimeState {
  debounceTimer: ReturnType<typeof setTimeout> | null;
  abortController: AbortController | null;
  currentView: EditorView | null;
  pendingAccept: boolean;
  lastTriggerDocVersion: number;
  /** 流式过程中直接更新 ghost DOM，避免高频 dispatch */
  domUpdater?: ((text: string) => void) | null;
  /** 流式结束时的完整文本，供 accept 使用 */
  streamedText?: string;
}

// ─── Plugin 工厂 ───
export function createAiCompletionPlugin(
  getEditor: () => Editor,
  options: AiCompletionOptions,
): Plugin {
  // runtime 状态对象在 createAiCompletionPlugin 调用时创建一次
  // 不随 plugin.view 的 destroy/update 生命周期重置
  const runtime: PluginRuntimeState = {
    debounceTimer: null,
    abortController: null,
    currentView: null,
    pendingAccept: false,
    lastTriggerDocVersion: -1,
  };

  let enabled = options.enabled !== false;
  const debounceMs = options.debounceMs ?? 400;

  function cancelCompletion(
    view: EditorView,
    reason: 'dismiss' | 'accept' = 'dismiss',
  ) {
    if (runtime.debounceTimer !== null) {
      console.log('[AiCompletion] cancelCompletion: 清除防抖定时器');
      clearTimeout(runtime.debounceTimer);
      runtime.debounceTimer = null;
    }
    if (runtime.abortController) {
      runtime.abortController.abort();
      runtime.abortController = null;
    }
    runtime.pendingAccept = false;
    // 清理 domUpdater
    runtime.domUpdater = null;
    runtime.streamedText = '';

    const pluginState = AI_COMPLETION_PLUGIN_KEY.getState(view.state);
    if (pluginState?.completion) {
      dispatchAction(view, {
        type: reason === 'dismiss' ? 'dismiss' : 'accept',
      });
    }
  }

  async function triggerCompletion(
    view: EditorView,
    triggerFrom: number,
    triggerTo: number,
  ) {
    const editor = getEditor();

    if (!enabled) {
      console.debug('[AiCompletion] 功能已禁用，跳过触发');
      return;
    }

    if (runtime.abortController) {
      runtime.abortController.abort();
    }
    runtime.abortController = new AbortController();

    // 显示加载状态
    dispatchAction(view, { type: 'startLoading', triggerFrom, triggerTo });
    options.onCompletionStart?.(triggerFrom);

    try {
      // ── 取光标前后文本，用占位符标出光标位置，提示词由后端组装 ───
      const { fullText, cursorOffset } = extractDocText(view.state.doc, triggerTo);
      const rawBefore = fullText.slice(0, cursorOffset);
      // 去掉触发符 "//"
      const cleanBefore = rawBefore.endsWith('//')
        ? rawBefore.slice(0, -2)
        : rawBefore;
      const contextText = `${cleanBefore}${fullText.slice(cursorOffset)}`;
      const originalText = buildContinuationContext(
        contextText,
        cleanBefore.length,
        editor.aiModel ?? undefined,
      );

      // ── 模型：取挂载在编辑器上的模型（写作管理侧边栏中选择） ───
      const modelId = editor.aiModel?.id;
      const articleId = editor.articleInfo?.id;
      const characterCountCeil = editor.aiModel?.continuationCharacterCountCeil;

      // ── 流式调用后端补全接口 ───
      // 已累积的补全文本（后端按增量 delta 下发）
      let accumulated = '';
      // 后端 done:true 和流结束时都会触发 onDone，只处理第一次
      let finished = false;

      const finish = () => {
        if (finished) return;
        finished = true;
        const view_ = runtime.currentView;
        if (!view_) return;
        dispatchAction(view_, { type: 'complete' });

        if (runtime.pendingAccept) {
          runtime.pendingAccept = false;
          requestAnimationFrame(() => {
            if (runtime.currentView) acceptCompletion(runtime.currentView, true);
          });
        }
      };

      await streamChat(
        STREAM_COMPLETION_URL,
        {
          modelId,
          originalText,
          characterCountCeil,
          generateType: 'continuation',
          scene: 'writing_prompt',
          chatRagRequest: {
            knowledgeBaseType: 'article',
            articleIds: articleId ? [articleId] : [],
          },
        },
        {
          onContent: (delta) => {
            accumulated += delta;
            const view_ = runtime.currentView;
            if (!view_) return;
            const state = AI_COMPLETION_PLUGIN_KEY.getState(view_.state);
            if (!state?.completion) return;
            dispatchAction(view_, { type: 'updateText', text: accumulated });
          },
          onDone: finish,
          onError: (err) => {
            if (finished) return;
            finished = true;
            const view_ = runtime.currentView;
            if (!view_) return;
            runtime.pendingAccept = false;
            const error = new Error(err?.message ?? 'AI 补全失败');
            dispatchAction(view_, { type: 'error', message: error.message });
            options.onError?.(error);
          },
        },
        runtime.abortController.signal,
      );
    } catch (err: any) {
      // 主动取消（新一轮触发 / Esc / 接受补全）：状态已由取消方处理，这里不再覆盖
      if (err?.name === 'AbortError') return;
      const error = err instanceof Error ? err : new Error(String(err));
      runtime.pendingAccept = false;
      dispatchAction(view, { type: 'error', message: error.message });
      options.onError?.(error);
    }
  }

  /**
   * 接受补全：插入文本到真实文档，并删除触发符 "//"
   */
  function acceptCompletion(view: EditorView, all = true) {
    const pluginState = AI_COMPLETION_PLUGIN_KEY.getState(view.state);
    if (!pluginState?.completion) return;

    const { completion } = pluginState;
    if (!completion.text) {
      cancelCompletion(view);
      return;
    }

    let textToInsert = completion.text;
    if (!all) {
      const nextWordMatch = textToInsert.match(/^(\s*\S+)/);
      textToInsert = nextWordMatch ? nextWordMatch[1] : textToInsert;
    }

    const { triggerPos, triggerEnd } = completion;

    const { tr } = view.state;
    tr.replaceWith(
      triggerPos,
      triggerEnd,
      view.state.schema.text(textToInsert),
    );
    const newCursorPos = triggerPos + textToInsert.length;
    tr.setSelection(
      // @ts-expect-error - constructor.near 是 ProseMirror 内部 API
      view.state.selection.constructor.near(tr.doc.resolve(newCursorPos)),
    );
    tr.setMeta(META_KEY, { type: 'accept' } as AiCompletionAction);
    view.dispatch(tr);
    options.onCompletionAccept?.(textToInsert);
  }

  /**
   * 逐词接受
   */
  function acceptNextWord(view: EditorView) {
    const pluginState = AI_COMPLETION_PLUGIN_KEY.getState(view.state);
    if (!pluginState?.completion) return;

    const { completion } = pluginState;
    if (!completion.text) {
      cancelCompletion(view);
      return;
    }

    const nextWordMatch = completion.text.match(/^(\s*\S+)/);
    if (!nextWordMatch) {
      acceptCompletion(view, true);
      return;
    }

    const wordToInsert = nextWordMatch[1];
    const remaining = completion.text.slice(wordToInsert.length);

    const { triggerPos, triggerEnd } = completion;
    const { tr } = view.state;

    tr.replaceWith(
      triggerPos,
      triggerEnd,
      view.state.schema.text(wordToInsert),
    );
    const newCursorPos = triggerPos + wordToInsert.length;
    console.log(
      'view.state.selection.constructor: ',
      view.state.selection.constructor,
    );
    tr.setSelection(
      view.state.selection.constructor.near(tr.doc.resolve(newCursorPos)),
    );
    tr.setMeta(META_KEY, { type: 'accept' } as AiCompletionAction);
    view.dispatch(tr);
    options.onCompletionAccept?.(wordToInsert);

    if (remaining.trim() && runtime.currentView) {
      requestAnimationFrame(() => {
        if (!runtime.currentView) return;
        dispatchAction(runtime.currentView, {
          type: 'startLoading',
          triggerFrom: newCursorPos,
          triggerTo: newCursorPos,
        });
        requestAnimationFrame(() => {
          if (!runtime.currentView) return;
          dispatchAction(runtime.currentView, {
            type: 'updateText',
            text: remaining,
          });
          dispatchAction(runtime.currentView, { type: 'complete' });
        });
      });
    }
  }

  return new Plugin<AiCompletionPluginState>({
    key: AI_COMPLETION_PLUGIN_KEY,

    // ── State ───
    state: {
      init(): AiCompletionPluginState {
        return {
          decorations: EMPTY_DECORATIONS,
          completion: null,
          enabled,
        };
      },

      apply(tr, pluginState, _oldState, newState): AiCompletionPluginState {
        const action = tr.getMeta(META_KEY) as AiCompletionAction | undefined;

        let { decorations, completion } = pluginState;

        // 映射装饰与补全位置
        if (decorations !== EMPTY_DECORATIONS) {
          decorations = decorations.map(tr.mapping, tr.doc);
        }
        if (completion) {
          completion = {
            ...completion,
            triggerPos: tr.mapping.map(completion.triggerPos),
            triggerEnd: tr.mapping.map(completion.triggerEnd),
          };
        }

        // 无 action 时，只有在"非补全触发的 docChanged"下才清除状态
        // 通过检查是否有 accept 的 meta 来避免误清除
        if (!action) {
          // 用户输入导致文档变化时才清除，且排除由 accept 引起的变化
          if (completion && tr.docChanged) {
            // 检查是否是 accept 操作（accept 会带 META_KEY）
            // 走到这里说明是用户输入导致的文档变化，取消补全
            return {
              decorations: EMPTY_DECORATIONS,
              completion: null,
              enabled: pluginState.enabled,
            };
          }
          return { ...pluginState, decorations, completion };
        }

        switch (action.type) {
          case 'toggle':
            enabled = action.enabled;
            return {
              decorations: EMPTY_DECORATIONS,
              completion: null,
              enabled: action.enabled,
            };

          case 'startLoading': {
            // 每次开始新的加载前，必须清空旧的 domUpdater
            // 否则第二次补全的首个 token 会走旧引用分支，跳过 decoration 重建
            runtime.domUpdater = null;
            runtime.streamedText = '';

            const newCompletion: CompletionState = {
              status: 'loading',
              triggerPos: action.triggerFrom,
              triggerEnd: action.triggerTo,
              text: '',
            };
            const loadingDeco = createLoadingDecoration(
              action.triggerTo,
              newState.doc,
            );
            return {
              decorations: loadingDeco,
              completion: newCompletion,
              enabled: pluginState.enabled,
            };
          }

          case 'updateText': {
            if (!completion) return pluginState;
            const updated: CompletionState = {
              ...completion,
              status: 'streaming',
              text: action.text,
            };

            // domUpdater 存在 → 说明 live decoration 的 DOM 节点已挂载
            // 直接原地更新，避免重建 DecorationSet
            if (runtime.domUpdater) {
              runtime.domUpdater(action.text);
              runtime.streamedText = action.text;
              return {
                decorations: pluginState.decorations,
                completion: updated,
                enabled: pluginState.enabled,
              };
            }

            // domUpdater 为 null → 首个 token，需要建立 live ghost decoration
            // 此时 loading decoration 被替换为 ghost text decoration
            const { decorationSet, updateDOM } = createLiveGhostTextDecoration(
              completion.triggerPos,
              completion.triggerEnd,
              completion.triggerEnd,
              action.text,
              newState.doc,
            );
            runtime.domUpdater = updateDOM;
            runtime.streamedText = action.text;

            return {
              decorations: decorationSet,
              completion: updated,
              enabled: pluginState.enabled,
            };
          }

          case 'complete': {
            if (!completion) return pluginState;
            // complete 时也同步 streamedText 到 completion.text
            // 确保 accept 时读到的是最终完整文本
            const finalText = runtime.streamedText || completion.text;
            const done: CompletionState = {
              ...completion,
              status: 'done',
              text: finalText,
            };
            return { ...pluginState, decorations, completion: done };
          }

          case 'accept':
          case 'dismiss':
            // 清理 domUpdater（dismiss 路径的清理，accept 路径由 cancelCompletion 处理）
            runtime.domUpdater = null;
            runtime.streamedText = '';
            return {
              decorations: EMPTY_DECORATIONS,
              completion: null,
              enabled: pluginState.enabled,
            };

          case 'error': {
            if (!completion) return pluginState;
            // 错误时也清理
            runtime.domUpdater = null;
            runtime.streamedText = '';
            return {
              decorations: EMPTY_DECORATIONS,
              completion: {
                ...completion,
                status: 'error',
                error: action.message,
              },
              enabled: pluginState.enabled,
            };
          }

          default:
            return pluginState;
        }
      },
    },

    // ── Decorations ───
    props: {
      decorations(state) {
        return (
          AI_COMPLETION_PLUGIN_KEY.getState(state)?.decorations ??
          EMPTY_DECORATIONS
        );
      },

      handleKeyDown(view, event) {
        const pluginState = AI_COMPLETION_PLUGIN_KEY.getState(view.state);
        const hasCompletion =
          pluginState?.completion &&
          (pluginState.completion.status === 'streaming' ||
            pluginState.completion.status === 'done' ||
            pluginState.completion.status === 'loading');

        // ── Escape：取消 ───
        if (event.key === 'Escape' && hasCompletion) {
          cancelCompletion(view, 'dismiss');
          options.onCompletionDismiss?.();
          return true;
        }

        // ── Tab / Enter：全接受 ───
        if ((event.key === 'Tab' || event.key === 'Enter') && hasCompletion) {
          event.preventDefault();
          const status = pluginState?.completion?.status;

          if (status === 'done') {
            acceptCompletion(view, true);
            return true;
          }

          // loading/streaming 时标记 pendingAccept，等完成后自动接受
          if (status === 'loading' || status === 'streaming') {
            runtime.pendingAccept = true;
            return true;
          }
        }

        // ── Ctrl+→：逐词接受 ───
        if (
          event.key === 'ArrowRight' &&
          (event.ctrlKey || event.metaKey) &&
          pluginState?.completion?.status === 'done'
        ) {
          event.preventDefault();
          acceptNextWord(view);
          return true;
        }

        // ── 手动触发（Ctrl+Space）───
        const manualKey = options.manualTriggerKey ?? 'ctrl+ ';
        if (matchesShortcut(event, manualKey) && enabled) {
          event.preventDefault(); // 阻止默认行为（浏览器可能有默认 Ctrl+Space 行为）
          const { selection } = view.state;
          if (runtime.debounceTimer !== null) {
            clearTimeout(runtime.debounceTimer);
            runtime.debounceTimer = null;
          }
          triggerCompletion(view, selection.from, selection.to).catch(
            console.error,
          );
          return true;
        }

        return false;
      },
    },

    // ── View ───
    view(editorView) {
      // view() 每次创建时更新 currentView，但不重置 runtime 其他状态
      runtime.currentView = editorView;

      return {
        update(view, prevState) {
          runtime.currentView = view;
          const pluginState = AI_COMPLETION_PLUGIN_KEY.getState(view.state);

          // 功能未启用
          if (!pluginState?.enabled) return;

          // 已有补全状态时不重复触发
          if (pluginState?.completion) return;

          // 文档未变化时不检测（选区变化等不触发）
          if (view.state.doc === prevState.doc) return;

          // 检测触发条件
          const triggerResult = detectTrigger(view.state);

          if (!triggerResult) {
            if (runtime.debounceTimer !== null) {
              clearTimeout(runtime.debounceTimer);
              console.log('[AiCompletion] 无触发条件，清除防抖定时器');
              runtime.debounceTimer = null;
            }
            // 重置版本号，允许下次重新触发
            runtime.lastTriggerDocVersion = -1;
            return;
          }

          const currentDocVersion = (view.state as any).version ?? Date.now();
          if (currentDocVersion === runtime.lastTriggerDocVersion) {
            console.log('[AiCompletion] 同一文档版本，跳过');
            return;
          }
          runtime.lastTriggerDocVersion = currentDocVersion;

          if (runtime.debounceTimer !== null) {
            clearTimeout(runtime.debounceTimer);
          }

          console.log(
            '[AiCompletion] 创建防抖定时器，将在',
            debounceMs,
            'ms 后触发',
          );
          runtime.debounceTimer = setTimeout(() => {
            console.log('[AiCompletion] 进入延时触发逻辑');
            runtime.debounceTimer = null;
            const latestView = runtime.currentView;
            if (!latestView) {
              console.log('[AiCompletion] latestView 为空，取消');
              return;
            }

            const latestTrigger = detectTrigger(latestView.state);
            if (!latestTrigger) {
              console.log('[AiCompletion] 延时后触发条件不满足，取消');
              return;
            }

            // 超时后再次检查是否已有补全
            const latestPluginState = AI_COMPLETION_PLUGIN_KEY.getState(
              latestView.state,
            );
            if (latestPluginState?.completion) {
              console.log('[AiCompletion] 延时后已有补全，取消');
              return;
            }

            console.debug('[AiCompletion] 触发 AI 补全', latestTrigger);

            triggerCompletion(
              latestView,
              latestTrigger.from,
              latestTrigger.to,
            ).catch(console.error);
          }, debounceMs);
        },

        destroy() {
          // destroy 时只清理 currentView，不清除 debounceTimer
          // 因为插件可能立即重建（编辑器 remount），定时器应该继续运行
          console.log('[AiCompletion] plugin.view destroy，保留 debounceTimer');
          runtime.currentView = null;
          // 不在 destroy 中清除 debounceTimer 和 abortController
          // 只有在编辑器真正卸载时（通过外部清理）才需要清理
        },
      };
    },
  });
}

// ─── 快捷键匹配工具 ───

function matchesShortcut(event: KeyboardEvent, shortcut: string): boolean {
  const parts = shortcut.toLowerCase().split('+');
  const key = parts[parts.length - 1].trim();
  const ctrl = parts.includes('ctrl');
  const meta = parts.includes('meta');
  const shift = parts.includes('shift');
  const alt = parts.includes('alt');

  const keyMatch =
    key === ' '
      ? event.key === ' ' || event.code === 'Space'
      : event.key.toLowerCase() === key;

  return (
    keyMatch &&
    (!ctrl || event.ctrlKey) &&
    (!meta || event.metaKey) &&
    (!shift || event.shiftKey) &&
    (!alt || event.altKey)
  );
}
