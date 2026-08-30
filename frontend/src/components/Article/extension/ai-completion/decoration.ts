import {Decoration, DecorationSet} from '@tiptap/pm/view';
import type {EditorState} from '@tiptap/pm/state';

// ─── CSS 类名常量 ───
export const LOADING_DECORATION_CLASS = 'ai-completion-loading';
export const GHOST_TEXT_CLASS = 'ai-completion-ghost';
export const TRIGGER_MARK_CLASS = 'ai-completion-trigger-mark';

// ─── 加载动画装饰 ───
/**
 * 在光标位置插入三点加载动画
 */
export function createLoadingDecoration(pos: number, doc: EditorState['doc']): DecorationSet {
  const widget = Decoration.widget(pos, () => {
    const span = document.createElement('span');
    span.className = LOADING_DECORATION_CLASS;
    span.setAttribute('aria-hidden', 'true');
    // 三个点，由 CSS 动画控制
    for (let i = 0; i < 3; i++) {
      const dot = document.createElement('span');
      dot.className = 'ai-completion-loading-dot';
      span.appendChild(dot);
    }
    return span;
  }, {side: 1, key: 'ai-loading'});

  return DecorationSet.create(doc, [widget]);
}

/**
 * 创建一个"可原地更新"的 ghost text decoration
 * 返回 decorationSet 和一个用于直接更新 DOM 的 updater 函数
 */
export function createLiveGhostTextDecoration(
  triggerFrom: number,
  triggerTo: number,
  ghostPos: number,
  initialText: string,
  doc: EditorState['doc'],
): {
  decorationSet: DecorationSet;
  /** 直接更新 DOM，无需 dispatch，适合高频流式场景 */
  updateDOM: (text: string) => void;
} {
  // 用闭包持有 span 引用
  let domRef: HTMLSpanElement | null = null;

  const decorations: Decoration[] = [];

  decorations.push(
    Decoration.inline(triggerFrom, triggerTo, {
      class: TRIGGER_MARK_CLASS,
      'data-trigger': '//',
    }),
  );

  const widget = Decoration.widget(
    ghostPos,
    () => {
      const span = document.createElement('span');
      span.className = GHOST_TEXT_CLASS;
      span.setAttribute('aria-hidden', 'true');
      span.setAttribute('data-ghost-text', initialText);
      span.textContent = initialText;
      domRef = span;
      return span;
    },
    // key 固定，节点创建一次后复用
    {side: 1, key: 'ai-ghost-text-live'},
  );
  decorations.push(widget);

  return {
    decorationSet: DecorationSet.create(doc, decorations),
    updateDOM: (text: string) => {
      if (domRef) {
        domRef.textContent = text;
        domRef.setAttribute('data-ghost-text', text);
      }
    },
  };
}

export const EMPTY_DECORATIONS = DecorationSet.empty;
