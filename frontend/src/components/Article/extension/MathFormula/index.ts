import {InputRule} from '@tiptap/core';
import {
  BlockMath,
  InlineMath,
  Mathematics,
  type MathematicsOptions,
} from '@tiptap/extension-mathematics';
import 'katex/dist/katex.min.css';
import './MathFormula.less';
import modalBridge, {type MathFormulaType} from './modalBridge';

declare module '@tiptap/core' {}

export interface MathFormulaOptions extends MathematicsOptions {
  /**
   * 是否启用点击编辑功能
   * @default true
   */
  enableClickEdit?: boolean;
}

// 保存当前编辑器实例，供点击回调使用
let currentEditor: any = null;

const insertMathFormula = (
  editor: any,
  latex: string,
  type: MathFormulaType,
  replaceSelection = false,
) => {
  const chain = editor.chain().focus();

  if (replaceSelection) {
    chain.deleteSelection();
  }

  return type === 'block'
    ? chain.insertBlockMath({ latex }).createParagraphNear().run()
    : chain.insertInlineMath({ latex }).run();
};

const MathFormulaBlock = BlockMath.extend({
  markdownTokenizer: {
    name: 'blockMath',
    level: 'block',
    start: (src: string) => src.match(/^[\t ]{0,3}\$\$/m)?.index ?? -1,
    tokenize: (src: string) => {
      const match = src.match(
        /^[\t ]{0,3}\$\$(?:[\t ]*\r?\n([\s\S]*?)\r?\n[\t ]{0,3}\$\$|[\t ]*([^\r\n]+?)[\t ]*\$\$)[\t ]*(?:\r?\n|$)/,
      );

      if (!match) return undefined;

      return {
        type: 'blockMath',
        raw: match[0],
        latex: (match[1] ?? match[2]).trim(),
      };
    },
  },

  addInputRules() {
    return [
      new InputRule({
        find: /^\$\$([^$\n]+?)\$\$$/,
        handler: ({ state, range, match }) => {
          const $from = state.doc.resolve(range.from);
          const node = this.type.create({ latex: match[1].trim() });
          const parent = $from.node(-1);
          const index = $from.index(-1);

          if (parent.canReplaceWith(index, index + 1, this.type)) {
            state.tr.replaceWith($from.before(), $from.after(), node);
          }
        },
      }),
    ];
  },
});

const MathFormulaInline = InlineMath.extend({
  markdownTokenizer: {
    name: 'inlineMath',
    level: 'inline',
    start: (src: string) => {
      const dollarIndex = src.indexOf('$');
      const parenthesisIndex = src.indexOf('\\(');
      const indexes = [dollarIndex, parenthesisIndex].filter(
        (index) => index >= 0,
      );

      return indexes.length > 0 ? Math.min(...indexes) : -1;
    },
    tokenize: (src: string) => {
      const match =
        src.match(/^\$\$(?!\$)([^$\r\n]+?)\$\$(?!\$)/) ??
        src.match(/^\\\(([^\r\n]+?)\\\)/) ??
        src.match(/^\$(?!\$)([^$\r\n]+?)\$(?!\$)/);

      if (!match) return undefined;

      return {
        type: 'inlineMath',
        raw: match[0],
        latex: match[1].trim(),
      };
    },
  },

  addInputRules() {
    const createRule = (find: RegExp) =>
      new InputRule({
        find,
        handler: ({ state, range, match }) => {
          state.tr.replaceWith(
            range.from,
            range.to,
            this.type.create({ latex: match[1].trim() }),
          );
        },
      });

    return [
      createRule(/(?<!\$)\$\$([^$\n]+?)\$\$$/),
      createRule(/\\\(([^\r\n]+?)\\\)$/),
    ];
  },
});

/**
 * 判断当前选区是否应该视为"块级选区"
 *
 * 判断规则：
 * 1. 选区完整覆盖了当前块节点（从块起始到块结束）
 * 2. 或选区跨越了多个顶层块节点
 */
const isBlockSelection = (state: any): boolean => {
  const { selection } = state;

  if (selection.empty) return false;

  const { $from, $to } = selection;

  // 当前块节点内容的起始/结束位置
  const blockStart = $from.start($from.depth);
  const blockEnd = $to.end($to.depth);

  // 选区完整覆盖整个块节点
  const coversWholeBlock =
    selection.from <= blockStart && selection.to >= blockEnd;

  // 选区跨越多个顶层块节点
  const crossesMultipleBlocks = $from.node(1) !== $to.node(1);

  return coversWholeBlock || crossesMultipleBlocks;
};

/**
 * 数学公式扩展
 * 基于 @tiptap/extension-mathematics 封装，提供更好的用户体验
 */
export const MathFormula = Mathematics.extend<MathFormulaOptions>({
  name: 'mathFormula',

  addOptions() {
    return {
      ...this.parent?.(),
      enableClickEdit: true,
    };
  },

  addExtensions() {
    return [
      MathFormulaBlock.configure({
        ...this.options.blockOptions,
        katexOptions: {
          ...this.options.katexOptions,
          displayMode: true,
        },
      }),
      MathFormulaInline.configure({
        ...this.options.inlineOptions,
        katexOptions: {
          ...this.options.katexOptions,
          displayMode: false,
        },
      }),
    ];
  },

  addCommands() {
    return {
      ...this.parent?.(),

      /**
       * 插入内联数学公式
       */
      insertInlineMathFormula:
        (attrs?: { latex?: string }) =>
        ({ commands, editor, state }: any) => {
          const { latex } = attrs || {};

          // 直接传入 latex → 立即插入
          if (latex) {
            return commands.insertInlineMath({ latex });
          }

          // 有文本选区 → 将选区包裹为内联公式
          if (!state.selection.empty) {
            const selectedText = state.doc.textBetween(
              state.selection.from,
              state.selection.to,
            );
            if (selectedText.trim()) {
              return (
                commands.deleteSelection() &&
                commands.insertInlineMath({ latex: selectedText })
              );
            }
          }

          // 无 latex、无选区 → 通过 bridge 打开弹窗
          if (modalBridge.handler) {
            modalBridge.handler.openModal('inline', '', (newLatex, newType) => {
              if (newLatex) {
                insertMathFormula(editor, newLatex, newType);
              }
            });
            return true;
          }

          return false;
        },

      /**
       * 插入块级数学公式
       */
      insertBlockMathFormula:
        (attrs?: { latex?: string }) =>
        ({ commands, editor, state }: any) => {
          const { latex } = attrs || {};

          if (latex) {
            return commands.insertBlockMath({ latex });
          }

          if (!state.selection.empty) {
            const selectedText = state.doc.textBetween(
              state.selection.from,
              state.selection.to,
              '\n',
            );
            if (selectedText.trim()) {
              return (
                commands.deleteSelection() &&
                commands.insertBlockMath({ latex: selectedText })
              );
            }
          }

          if (modalBridge.handler) {
            modalBridge.handler.openModal('block', '', (newLatex, newType) => {
              if (newLatex) {
                insertMathFormula(editor, newLatex, newType);
              }
            });
            return true;
          }

          console.warn('[MathFormula] handler 为 null，无法打开弹窗');
          return false;
        },

      /**
       * 智能切换数学公式
       *
       * 根据当前选区类型自动决定插入内联或块级公式：
       * - 有选区 + 块级选区（完整覆盖段落）→ 块级公式
       * - 有选区 + 行内选区（部分文字）      → 内联公式
       * - 无选区                            → 通过弹窗选择后插入内联公式
       *
       * @param attrs.latex     - 可选，直接指定 LaTeX 内容，跳过弹窗
       * @param attrs.forceType - 可选，强制指定公式类型 'inline' | 'block'
       */
      toggleMathFormula:
        (attrs?: { latex?: string; forceType?: 'inline' | 'block' }) =>
        ({ commands, editor, state }: any) => {
          const { latex, forceType } = attrs || {};

          // ── 工具函数：读取当前选区的纯文本 ────────────────────────
          const getSelectedText = (separator = ''): string => {
            if (state.selection.empty) return '';
            return state.doc.textBetween(
              state.selection.from,
              state.selection.to,
              separator,
            );
          };

          // ── 工具函数：删除选区并插入内联公式 ──────────────────────
          const replaceWithInlineMath = (latexStr: string): boolean => {
            if (!latexStr.trim()) return false;
            return (
              commands.deleteSelection() &&
              commands.insertInlineMath({ latex: latexStr })
            );
          };

          // ── 工具函数：删除选区并插入块级公式 ──────────────────────
          const replaceWithBlockMath = (latexStr: string): boolean => {
            if (!latexStr.trim()) return false;
            return (
              commands.deleteSelection() &&
              commands.insertBlockMath({ latex: latexStr })
            );
          };

          // ── 1. 强制指定类型 ──────────────────────────────────────
          if (forceType === 'block') {
            if (latex) {
              return state.selection.empty
                ? commands.insertBlockMath({ latex })
                : replaceWithBlockMath(latex);
            }
            return commands.insertBlockMathFormula();
          }

          if (forceType === 'inline') {
            if (latex) {
              return state.selection.empty
                ? commands.insertInlineMath({ latex })
                : replaceWithInlineMath(latex);
            }
            return commands.insertInlineMathFormula();
          }

          // ── 2. 无选区：打开弹窗，默认插入内联公式 ────────────────
          if (state.selection.empty) {
            if (latex) {
              return commands.insertInlineMath({ latex });
            }

            if (!modalBridge.handler) {
              console.warn('[MathFormula] toggleMathFormula: handler 为 null');
              return false;
            }

            modalBridge.handler.openModal('inline', '', (newLatex, newType) => {
              if (!newLatex) return;
              insertMathFormula(editor, newLatex, newType);
            });

            return true;
          }

          // ── 3. 有选区：根据选区类型决定插入何种公式 ──────────────
          const useBlock = isBlockSelection(state);

          if (useBlock) {
            // 块级选区 → 块级公式
            const latexContent = latex ?? getSelectedText('\n');

            if (latexContent.trim()) {
              return replaceWithBlockMath(latexContent);
            }

            // 选区内无有效文本 → 弹窗输入
            if (!modalBridge.handler) {
              console.warn('[MathFormula] toggleMathFormula: handler 为 null');
              return false;
            }

            modalBridge.handler.openModal('block', '', (newLatex, newType) => {
              if (!newLatex) return;
              insertMathFormula(editor, newLatex, newType, true);
            });

            return true;
          } else {
            // 行内选区 → 内联公式
            const latexContent = latex ?? getSelectedText();

            if (latexContent.trim()) {
              return replaceWithInlineMath(latexContent);
            }

            // 选区内无有效文本 → 弹窗输入
            if (!modalBridge.handler) {
              console.warn('[MathFormula] toggleMathFormula: handler 为 null');
              return false;
            }

            modalBridge.handler.openModal('inline', '', (newLatex, newType) => {
              if (!newLatex) return;
              insertMathFormula(editor, newLatex, newType, true);
            });

            return true;
          }
        },
    };
  },

  onCreate() {
    this.parent?.();
    // 存储编辑器实例
    currentEditor = this.editor;
  },

  onDestroy() {
    this.parent?.();
    // 清理编辑器实例
    currentEditor = null;
  },
  // 快捷键设置
  addKeyboardShortcuts() {
    return {
      ...this.parent?.(),
      Enter: () => {
        const { selection } = this.editor.state;
        const { $from } = selection;

        if (
          !selection.empty ||
          !$from.parent.isTextblock ||
          $from.parent.textContent.trim() !== '$$'
        ) {
          return false;
        }

        return this.editor
          .chain()
          .deleteRange({ from: $from.start(), to: $from.end() })
          .insertBlockMathFormula()
          .run();
      },
      'Mod-m': () => this.editor.commands.insertInlineMathFormula(),
      'Mod-Shift-m': () => this.editor.commands.insertBlockMathFormula(),
    };
  },
} as any);

// 配置方法，用于自定义配置
export const configureMathFormula = (options: MathFormulaOptions = {}) => {
  /**
   * 点击已有公式时，通过 bridge 打开弹窗编辑
   */
  const handleClickEdit = (node: any, pos: number, type: MathFormulaType) => {
    if (!options.enableClickEdit || !currentEditor) return;
    if (!modalBridge.handler) return;

    modalBridge.handler.openModal(
      type,
      node.attrs.latex ?? '',
      (newLatex, newType) => {
        try {
          if (newType !== type) {
            currentEditor
              .chain()
              .focus()
              .insertContentAt(
                { from: pos, to: pos + node.nodeSize },
                {
                  type: newType === 'block' ? 'blockMath' : 'inlineMath',
                  attrs: { latex: newLatex },
                },
              )
              .run();
            return;
          }

          const updateCmd =
            type === 'block' ? 'updateBlockMath' : 'updateInlineMath';
          currentEditor
            .chain()
            .focus()
            .setNodeSelection(pos)
            [updateCmd]({ latex: newLatex })
            .run();
        } catch (error) {
          console.error(`Failed to update ${type} math:`, error);
        }
      },
    );
  };

  return MathFormula.configure({
    enableClickEdit: options.enableClickEdit ?? true,
    blockOptions: {
      onClick: (node: any, pos: number) => handleClickEdit(node, pos, 'block'),
    },
    inlineOptions: {
      onClick: (node: any, pos: number) => handleClickEdit(node, pos, 'inline'),
    },
  } as any);
};

export type {
  MathFormulaModalProps,
  MathFormulaType,
} from './MathFormulaModal';
export { default as MathFormulaModal } from './MathFormulaModal';
export { default as MathFormulaView } from './MathFormulaView';
