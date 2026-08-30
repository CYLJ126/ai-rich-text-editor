import {Editor} from '@tiptap/core';
import {TextStyle} from '@tiptap/extension-text-style';
import type {Mark} from '@tiptap/pm/model';

/**
 * 文本格式扩展
 */
declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    myTextStyle: {
      /**
       * 切换字体大小（有则取消，无则设置）
       */
      toggleFontSize: (fontSize: string) => ReturnType;
      /**
       * 切换字体系列（有则取消，无则设置）
       */
      toggleFontFamily: (fontFamily: string) => ReturnType;
      /**
       * 切换字体颜色（有则取消，无则设置）
       */
      toggleFontColor: (color: string) => ReturnType;
      /**
       * 切换行高（有则取消，无则设置）
       */
      toggleLineHeight: (lineHeight: string) => ReturnType;
      /**
       * 切换背景颜色（有则取消，无则设置）
       */
      toggleBackgroundColor: (backgroundColor: string) => ReturnType;
    };
  }
}

// ✅ 工具函数：检测选区内是否所有文本都具有指定的 textStyle 属性值
function isTextStyleAttrActive(
  editor: Editor,
  attrName: string,
  value: string,
): boolean {
  const {state} = editor;
  const {from, to, empty} = state.selection;

  if (empty) {
    // 光标处直接读取属性
    const attrs = editor.getAttributes('textStyle');
    return attrs[attrName] === value;
  }

  // 遍历选区内所有节点，检查是否全部包含该属性
  let allMatch = true;
  let hasTextNode = false;

  state.doc.nodesBetween(from, to, (node) => {
    if (!node.isText) return;
    hasTextNode = true;

    const mark = node.marks.find((m: Mark) => m.type.name === 'textStyle');
    if (!mark || mark.attrs[attrName] !== value) {
      allMatch = false;
    }
  });

  return hasTextNode && allMatch;
}

// ✅ 工具函数：清除选区内 textStyle mark 的某个属性，保留其他属性
function unsetTextStyleAttr(attrName: string) {
  return ({chain, editor}: any) => {
    const {state} = editor;
    const {from, to} = state.selection;
    let tr = state.tr;

    // 收集选区内所有 textStyle mark 的当前属性，去掉目标属性后重新设置
    state.doc.nodesBetween(from, to, (node: any, pos: number) => {
      if (!node.isText) return;

      const textStyleMark = node.marks.find(
        (m: Mark) => m.type.name === 'textStyle',
      );

      // 先移除旧的 textStyle mark
      const markType = state.schema.marks['textStyle'];
      if (!markType) return;

      const nodeFrom = Math.max(pos, from);
      const nodeTo = Math.min(pos + node.nodeSize, to);

      tr = tr.removeMark(nodeFrom, nodeTo, markType);

      if (textStyleMark) {
        // 构建新属性：去掉目标属性
        const newAttrs = {...textStyleMark.attrs, [attrName]: null};

        // 检查是否还有其他非 null 属性
        const hasOtherAttrs = Object.entries(newAttrs).some(
          ([k, v]) =>
            k !== attrName && v !== null && v !== undefined && v !== '',
        );

        if (hasOtherAttrs) {
          // 还有其他属性，重新设置 mark（不含目标属性）
          const newMark = markType.create(newAttrs);
          tr = tr.addMark(nodeFrom, nodeTo, newMark);
        }
        // 没有其他属性则不重新添加，相当于完全移除 textStyle
      }
    });

    return editor.view.dispatch(tr);
  };
}

export const MyTextStyle = TextStyle.extend({
  name: 'textStyle',

  addAttributes() {
    return {
      ...this.parent?.(),
      fontSize: {
        default: null,
        parseHTML: (element: HTMLElement) => element.style.fontSize || null,
        renderHTML: (attributes: Record<string, any>) => {
          if (!attributes.fontSize) return {};
          return {style: `font-size: ${attributes.fontSize}`};
        },
      },
      fontFamily: {
        default: null,
        parseHTML: (element: HTMLElement) => element.style.fontFamily || null,
        renderHTML: (attributes: Record<string, any>) => {
          if (!attributes.fontFamily) return {};
          return {style: `font-family: ${attributes.fontFamily}`};
        },
      },
      color: {
        default: null,
        parseHTML: (element: HTMLElement) => element.style.color || null,
        renderHTML: (attributes: Record<string, any>) => {
          if (!attributes.color) return {};
          return {style: `color: ${attributes.color}`};
        },
      },
      lineHeight: {
        default: null,
        parseHTML: (element: HTMLElement) => element.style.lineHeight || null,
        renderHTML: (attributes: Record<string, any>) => {
          if (!attributes.lineHeight) return {};
          return {style: `line-height: ${attributes.lineHeight}`};
        },
      },
      backgroundColor: {
        default: null,
        parseHTML: (element: HTMLElement) =>
          element.style.backgroundColor || null,
        renderHTML: (attributes: Record<string, any>) => {
          if (!attributes.backgroundColor) return {};
          return {style: `background-color: ${attributes.backgroundColor}`};
        },
      },
    };
  },

  renderMarkdown(mark: Mark, helpers: any, node: any) {
    const attrs = mark.attrs as {
      fontSize?: string;
      fontFamily?: string;
      color?: string;
      lineHeight?: string;
      backgroundColor?: string;
    };

    // 检查是否有任何样式属性
    const hasStyle = Object.values(attrs).some(
      (v) => v !== null && v !== undefined && v !== '',
    );

    if (!hasStyle) {
      // 没有样式，直接渲染子内容
      return helpers.renderChildren(node);
    }

    // 构建 style 字符串
    const styleMap: string[] = [];
    if (attrs.fontSize) styleMap.push(`font-size: ${attrs.fontSize}`);
    if (attrs.fontFamily) styleMap.push(`font-family: ${attrs.fontFamily}`);
    if (attrs.color) styleMap.push(`color: ${attrs.color}`);
    if (attrs.lineHeight) styleMap.push(`line-height: ${attrs.lineHeight}`);
    if (attrs.backgroundColor)
      styleMap.push(`background-color: ${attrs.backgroundColor}`);

    const styleStr = styleMap.join('; ');
    const content = helpers.renderChildren(node);

    // 返回带 span 标签的 HTML 内容
    return `<span style="${styleStr}">${content}</span>`;
  },

  addCommands() {
    return {
      // ✅ 使用 isTextStyleAttrActive 检测整个选区状态
      toggleFontSize:
        (fontSize: string) =>
          ({chain, editor}: any) => {
            // 选择了"默认"，直接清除 fontSize 属性
            if (fontSize === 'default') {
              return unsetTextStyleAttr('fontSize')({chain, editor});
            }
            // 整个选区都是该 fontSize → 取消
            if (isTextStyleAttrActive(editor, 'fontSize', fontSize)) {
              return unsetTextStyleAttr('fontSize')({chain, editor});
            }
            // 否则设置
            return chain().setMark('textStyle', {fontSize}).run();
          },

      toggleFontFamily:
        (fontFamily: string) =>
          ({chain, editor}: any) => {
            // 选择了"默认"，直接清除 fontFamily 属性
            if (fontFamily === 'default') {
              return unsetTextStyleAttr('fontFamily')({chain, editor});
            }
            if (isTextStyleAttrActive(editor, 'fontFamily', fontFamily)) {
              return unsetTextStyleAttr('fontFamily')({chain, editor});
            }
            return chain().setMark('textStyle', {fontFamily}).run();
          },

      toggleFontColor:
        (color: string) =>
          ({chain, editor}: any) => {
            if (isTextStyleAttrActive(editor, 'color', color)) {
              return unsetTextStyleAttr('color')({chain, editor});
            }
            return chain().setMark('textStyle', {color}).run();
          },

      toggleLineHeight:
        (lineHeight: string) =>
          ({chain, editor}: any) => {
            // 选择了"默认"，直接清除 lineHeight 属性
            if (lineHeight === 'default') {
              return unsetTextStyleAttr('lineHeight')({chain, editor});
            }
            if (isTextStyleAttrActive(editor, 'lineHeight', lineHeight)) {
              return unsetTextStyleAttr('lineHeight')({chain, editor});
            }
            return chain().setMark('textStyle', {lineHeight}).run();
          },

      toggleBackgroundColor:
        (backgroundColor: string) =>
          ({chain, editor}: any) => {
            if (
              isTextStyleAttrActive(editor, 'backgroundColor', backgroundColor)
            ) {
              return unsetTextStyleAttr('backgroundColor')({chain, editor});
            }
            return chain().setMark('textStyle', {backgroundColor}).run();
          },
    } as any;
  },
} as any);
