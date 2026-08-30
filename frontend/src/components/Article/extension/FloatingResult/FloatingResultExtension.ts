import {Extension} from '@tiptap/core';
import type {FloatingResultState} from './FloatingResultPlugin';
import {createFloatingResultPlugin, floatingResultPluginKey} from './FloatingResultPlugin';

export interface FloatingResultOptions {
  /** 触发外部副作用（如渲染 React 组件）的回调 */
  onStateChange?: (state: FloatingResultState) => void;
}

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    floatingResult: {
      /**
       * 显示悬浮结果框
       * @param content 要展示的内容
       * @param label  可选标签，默认"结果"
       */
      showFloatingResult: (content: string, label?: string) => ReturnType;
      /** 隐藏悬浮结果框 */
      hideFloatingResult: () => ReturnType;
    };
  }
}

export const FloatingResultExtension = Extension.create<FloatingResultOptions>({
  name: 'floatingResult',

  addOptions() {
    return {
      onStateChange: undefined,
    };
  },

  addProseMirrorPlugins() {
    return [createFloatingResultPlugin()];
  },

  addCommands() {
    return {
      showFloatingResult:
        (content: string, label = '结果') =>
          ({editor, tr, dispatch}) => {
            const {from, to} = editor.state.selection;

            // 计算选区起始位置的屏幕坐标
            const start = editor.view.coordsAtPos(from);
            const end = editor.view.coordsAtPos(to);

            // 悬浮框出现在选区下方 8px
            const boxLeft = Math.min(start.left, end.left);
            const boxTop = Math.max(start.bottom, end.bottom) + 8;

            const newState: FloatingResultState = {
              visible: true,
              content,
              position: {top: boxTop, left: boxLeft},
              selectionFrom: from,
              selectionTo: to,
            };

            if (dispatch) {
              tr.setMeta(floatingResultPluginKey, newState);
              dispatch(tr);
            }

            // 通知外部（用于挂载 React 组件）
            this.options.onStateChange?.(newState);

            return true;
          },

      hideFloatingResult:
        () =>
          ({tr, dispatch}) => {
            const newState: FloatingResultState = {
              visible: false,
              content: '',
              position: null,
              selectionFrom: 0,
              selectionTo: 0,
            };

            if (dispatch) {
              tr.setMeta(floatingResultPluginKey, newState);
              dispatch(tr);
            }

            this.options.onStateChange?.(newState);

            return true;
          },
    };
  },
});
