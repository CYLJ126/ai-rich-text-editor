import type {Editor} from '@tiptap/core';
import {Extension, type JSONContent} from '@tiptap/core';
import {DOMParser as PMDOMParser, DOMSerializer, Fragment,} from '@tiptap/pm/model';
import type {Transaction} from '@tiptap/pm/state';
import type {BlockInfo, ContentLevel, InlineInfo, InsertPosition, Range, SelectionInfo,} from './types';

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    contentHelperExtension: {
      /**
       * 判断当前光标/选区内容是块级还是行级
       */
      getContentLevel: () => ContentLevel;

      /**
       * 判断是否有选中内容，有则返回选中信息
       */
      getSelectionInfo: () => ReturnType;

      /**
       * 根据光标位置返回当前块级内容信息
       */
      getCurrentBlockInfo: () => ReturnType;

      /**
       * 根据光标位置返回当前行级内容信息
       */
      getCurrentInlineInfo: () => ReturnType;

      /**
       * 替换当前选中的行级内容
       * @param inlineContent 替换的行级 JSON 内容（nodes 数组）
       */
      replaceSelectionInline: (inlineContent: JSONContent[]) => ReturnType;

      /**
       * 替换当前选中的块级内容
       * @param blockContent 替换的块级 JSON 内容
       */
      replaceSelectionBlock: (blockContent: JSONContent) => ReturnType;

      /**
       * 在当前行级内容前后插入行级内容
       * @param inlineContent 插入的行级内容
       * @param position 插入位置
       */
      insertInlineContent: (
        inlineContent: JSONContent[],
        position: InsertPosition,
      ) => ReturnType;

      /**
       * 在当前块级内容前后插入块级内容
       * @param blockContent 插入的块级内容
       * @param position 插入位置
       */
      insertBlockContent: (
        blockContent: JSONContent,
        position: InsertPosition,
      ) => ReturnType;

      /**
       * 在当前块级内容前后插入多个段落块级内容
       * @param blockContent 插入的块级内容
       */
      replaceSelectionBlockMulti: (blockContent: JSONContent) => ReturnType;

      /**
       * 替换指定 Range 位置的内容
       * @param range  目标范围 { from, to }
       * @param content 替换内容，默认为 '' 表示删除；
       *                支持：string（纯文本或 HTML）| JSONContent | JSONContent[]
       */
      replace: (range: Range, content?: ReplaceContent) => ReturnType;
    };
  }
}

// ----------------------------------------------------------------
// 替换内容的联合类型
// ----------------------------------------------------------------
/** replace 方法接受的内容类型 */
export type ReplaceContent = string | JSONContent | JSONContent[];

export const ContentHelperExtension = Extension.create({
  name: 'contentHelper',

  // ----------------------------------------------------------------
  // 私有工具方法（挂载到 storage 供外部访问）
  // ----------------------------------------------------------------
  addStorage() {
    return {
      /** 对外暴露的查询方法（不走 command 链，直接返回值） */
      api: null as ContentHelperAPI | null,
    };
  },

  onBeforeCreate() {
    // 在编辑器创建完成后，将 API 实例挂载到 storage
    this.storage.api = new ContentHelperAPI(this.editor);
  },

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------
  addCommands() {
    return {
      // ① 判断内容层级
      getContentLevel:
        () =>
          ({ editor }: { editor: Editor }): ContentLevel => {
            const api = new ContentHelperAPI(editor);
            return api.getContentLevel();
          },

      // ② 获取选中信息
      getSelectionInfo:
        () =>
          ({ editor }: { editor: Editor }): SelectionInfo => {
            const api = new ContentHelperAPI(editor);
            return api.getSelectionInfo();
          },

      // ③ 获取当前块级信息
      getCurrentBlockInfo:
        () =>
          ({ editor }: { editor: Editor }): BlockInfo | null => {
            const api = new ContentHelperAPI(editor);
            return api.getCurrentBlockInfo();
          },

      // ④ 获取当前行级信息
      getCurrentInlineInfo:
        () =>
          ({ editor }: { editor: Editor }): InlineInfo | null => {
            const api = new ContentHelperAPI(editor);
            return api.getCurrentInlineInfo();
          },

      // ⑤ 替换选中的行级内容
      replaceSelectionInline:
        (inlineContent: JSONContent[]) =>
          ({ editor, tr, dispatch }: any): boolean => {
            const api = new ContentHelperAPI(editor);
            return api.replaceSelectionInline(inlineContent, tr, dispatch);
          },

      // ⑥ 替换选中的块级内容
      replaceSelectionBlock:
        (blockContent: JSONContent) =>
          ({ editor, tr, dispatch }: any): boolean => {
            const api = new ContentHelperAPI(editor);
            return api.replaceSelectionBlock(blockContent, tr, dispatch);
          },

      // ⑦ 在行级内容前后插入
      insertInlineContent:
        (inlineContent: JSONContent[], position: InsertPosition) =>
          ({ editor, tr, dispatch }: any): boolean => {
            const api = new ContentHelperAPI(editor);
            return api.insertInlineContent(inlineContent, position, tr, dispatch);
          },

      // ⑧ 在块级内容前后插入
      insertBlockContent:
        (blockContent: JSONContent, position: InsertPosition) =>
          ({ editor, tr, dispatch }: any): boolean => {
            const api = new ContentHelperAPI(editor);
            return api.insertBlockContent(blockContent, position, tr, dispatch);
          },

      // ⑨ 在块级内容前后插入多个段落块级内容
      replaceSelectionBlockMulti:
        (blockContent: JSONContent) =>
          ({ editor, tr, dispatch }: any): boolean => {
            const api = new ContentHelperAPI(editor);
            return api.replaceSelectionBlockMulti(blockContent, tr, dispatch);
          },

      // ⑩ 替换指定 Range 的内容
      replace:
        (range: Range, content: ReplaceContent = '') =>
          ({ editor, tr, dispatch }: any): boolean => {
            const api = new ContentHelperAPI(editor);
            return api.replace(range, content, tr, dispatch);
          },
    };
  },
} as any);

// ====================================================================
// ContentHelperAPI - 核心逻辑类
// ====================================================================
class ContentHelperAPI {
  constructor(private editor: Editor) {}

  // ------------------------------------------------------------------
  // 工具：节点转 HTML
  // ------------------------------------------------------------------
  private nodeToHTML(node: import('@tiptap/pm/model').Node): string {
    try {
      const schema = this.editor.schema;
      const serializer = DOMSerializer.fromSchema(schema);
      const dom = serializer.serializeNode(node);
      const div = document.createElement('div');
      div.appendChild(dom);
      return div.innerHTML;
    } catch {
      return '';
    }
  }

  private fragmentToHTML(
    fragment: import('@tiptap/pm/model').Fragment,
  ): string {
    try {
      const schema = this.editor.schema;
      const serializer = DOMSerializer.fromSchema(schema);
      const domFragment = serializer.serializeFragment(fragment);
      const div = document.createElement('div');
      div.appendChild(domFragment);
      return div.innerHTML;
    } catch {
      return '';
    }
  }

  // ------------------------------------------------------------------
  // 工具：将 ReplaceContent 解析为 ProseMirror Fragment
  // 返回 null 代表删除（空 fragment）
  // ------------------------------------------------------------------
  private resolveContentToFragment(
    content: ReplaceContent,
    contextFrom: number,
  ): Fragment {
    const schema = this.editor.schema;

    // ① 空字符串 / undefined —— 删除，返回空 Fragment
    if (content === '' || content === undefined || content === null) {
      return Fragment.empty;
    }

    // ② 字符串（纯文本或 HTML）
    if (typeof content === 'string') {
      return this.parseStringContent(content, contextFrom);
    }

    // ③ JSONContent[]（数组形式）
    if (Array.isArray(content)) {
      try {
        const nodes = content.map((json) => schema.nodeFromJSON(json));
        return Fragment.from(nodes);
      } catch (e) {
        console.error('[ContentHelper] replace: JSONContent[] 解析失败', e);
        return Fragment.empty;
      }
    }

    // ④ JSONContent（单个对象）
    if (typeof content === 'object' && 'type' in content) {
      try {
        const node = schema.nodeFromJSON(content);
        return Fragment.from(node);
      } catch (e) {
        console.error('[ContentHelper] replace: JSONContent 解析失败', e);
        return Fragment.empty;
      }
    }

    console.warn(
      '[ContentHelper] replace: 无法识别的 content 类型，将执行删除',
    );
    return Fragment.empty;
  }

  // ------------------------------------------------------------------
  // 工具：解析字符串内容
  // 优先尝试 HTML 解析，纯文本回退为 schema.text
  // ------------------------------------------------------------------
  private parseStringContent(content: string, contextFrom: number): Fragment {
    const schema = this.editor.schema;

    // 判断目标位置所处的上下文：块级还是行级
    const $pos = this.editor.state.doc.resolve(contextFrom);
    const isInsideTextblock = $pos.parent.isTextblock;

    // ---------- 纯文本节点（行级上下文中的最简情形）----------
    // 如果内容不含任何 HTML 标签，直接作为纯文本处理
    const looksLikeHTML = /<[a-z][\s\S]*>/i.test(content);

    if (!looksLikeHTML) {
      // 纯文本：直接创建 text node
      if (isInsideTextblock) {
        try {
          return Fragment.from(schema.text(content));
        } catch {
          return Fragment.empty;
        }
      }
      // 块级上下文中的纯文本：包装为 paragraph
      try {
        const paraType = schema.nodes.paragraph;
        if (paraType) {
          const para = paraType.create(null, schema.text(content));
          return Fragment.from(para);
        }
      } catch {
        /* ignore */
      }
      return Fragment.empty;
    }

    // ---------- HTML 字符串：用 DOMParser 解析 ----------
    try {
      const domParser = PMDOMParser.fromSchema(schema);
      const tempDiv = document.createElement('div');
      tempDiv.innerHTML = content;

      const docNode = domParser.parse(tempDiv);

      // 如果目标位置在 textblock 内，取出所有行级节点
      if (isInsideTextblock) {
        // 获取 doc -> paragraph -> inline nodes
        const inlineNodes: import('@tiptap/pm/model').Node[] = [];
        docNode.forEach((blockNode) => {
          blockNode.forEach((inlineNode) => {
            inlineNodes.push(inlineNode);
          });
        });
        return inlineNodes.length > 0
          ? Fragment.from(inlineNodes)
          : Fragment.empty;
      }

      // 块级上下文：返回解析出的顶层块节点
      const blockNodes: import('@tiptap/pm/model').Node[] = [];
      docNode.forEach((node) => {
        blockNodes.push(node);
      });
      return blockNodes.length > 0 ? Fragment.from(blockNodes) : Fragment.empty;
    } catch (e) {
      console.error('[ContentHelper] replace: HTML 解析失败', e);
      return Fragment.empty;
    }
  }

  // ------------------------------------------------------------------
  // ⑨ 替换指定 Range 的内容（核心方法）
  // ------------------------------------------------------------------
  replace(
    range: Range,
    content: ReplaceContent = '',
    tr: import('@tiptap/pm/state').Transaction,
    dispatch?: (tr: import('@tiptap/pm/state').Transaction) => void,
  ): boolean {
    const { from, to } = range;
    const docSize = this.editor.state.doc.content.size;

    // ---- 边界校验 ----
    if (from < 0 || to > docSize || from > to) {
      console.warn(
        `[ContentHelper] replace: range 越界或无效 (from=${from}, to=${to}, docSize=${docSize})`,
      );
      return false;
    }

    try {
      const fragment = this.resolveContentToFragment(content, from);

      // replaceWith：用 fragment 替换 [from, to] 区间
      // 当 fragment 为 Fragment.empty 时，等效于删除
      tr.replaceWith(from, to, fragment);

      if (dispatch) dispatch(tr);
      return true;
    } catch (e) {
      console.error('[ContentHelper] replace error:', e);
      return false;
    }
  }

  // ------------------------------------------------------------------
  // ① 判断内容层级
  // ------------------------------------------------------------------
  getContentLevel(): ContentLevel {
    const { state } = this.editor;
    const { selection } = state;
    const { $from } = selection;

    // 如果选区跨越块级节点，则视为块级操作
    if (!selection.empty) {
      const { $to } = selection;
      if ($from.depth !== $to.depth || $from.parent !== $to.parent) {
        return 'block';
      }
    }

    // 光标所在父节点是块级节点，其子节点为行级
    const parent = $from.parent;
    if (parent.isTextblock) {
      return 'inline';
    }

    return 'block';
  }

  // ------------------------------------------------------------------
  // ② 获取选中信息
  // ------------------------------------------------------------------
  getSelectionInfo(): SelectionInfo {
    const { state } = this.editor;
    const { selection, doc } = state;
    const { from, to } = selection;

    if (selection.empty) {
      return {
        hasSelection: false,
        text: '',
        content: null,
        html: '',
        level: null,
        range: null,
      };
    }

    const selectedSlice = doc.slice(from, to);
    const text = doc.textBetween(from, to, '\n');

    // 构建 JSONContent
    const content: JSONContent = {
      type: 'doc',
      content: selectedSlice.content.toJSON() as JSONContent[],
    };

    const html = this.fragmentToHTML(selectedSlice.content);
    const level = this.getContentLevel();

    return {
      hasSelection: true,
      text,
      content,
      html,
      level,
      range: { from, to },
    };
  }

  // ------------------------------------------------------------------
  // ③ 获取当前块级信息
  // ------------------------------------------------------------------
  getCurrentBlockInfo(): BlockInfo | null {
    const { state } = this.editor;
    const { selection } = state;
    const { $from } = selection;

    // 找到最近的块级祖先节点
    let depth = $from.depth;
    while (depth > 0 && !$from.node(depth).isBlock) {
      depth--;
    }

    // 确保不是 doc 节点本身（depth=0）
    if (depth === 0) {
      depth = 1;
    }

    const blockNode = $from.node(depth);
    if (!blockNode) return null;

    const blockStart = $from.before(depth);
    const blockEnd = $from.after(depth);

    return {
      type: blockNode.type.name,
      text: blockNode.textContent,
      content: blockNode.toJSON() as JSONContent,
      html: this.nodeToHTML(blockNode),
      range: { from: blockStart, to: blockEnd },
      depth,
    };
  }

  // ------------------------------------------------------------------
  // ④ 获取当前行级信息（光标所在文本块内的全部内联内容）
  // ------------------------------------------------------------------
  getCurrentInlineInfo(): InlineInfo | null {
    const { state } = this.editor;
    const { selection } = state;
    const { $from } = selection;

    const parent = $from.parent;
    if (!parent.isTextblock) return null;

    // 文本块的起始位置（不含节点本身的开始标记，即 +1）
    const parentStart = $from.start(); // 文本块内容起始位置
    const parentEnd = $from.end(); // 文本块内容结束位置

    return {
      text: parent.textContent,
      content: {
        type: 'fragment',
        content: parent.content.toJSON() as JSONContent[],
      },
      html: this.fragmentToHTML(parent.content),
      range: { from: parentStart, to: parentEnd },
    };
  }

  // ------------------------------------------------------------------
  // ⑤ 替换选中的行级内容
  // ------------------------------------------------------------------
  replaceSelectionInline(
    inlineContent: JSONContent[],
    tr: import('@tiptap/pm/state').Transaction,
    dispatch?: (tr: import('@tiptap/pm/state').Transaction) => void,
  ): boolean {
    const { state } = this.editor;
    const { selection } = state;

    if (selection.empty) {
      console.warn('[ContentHelper] replaceSelectionInline: 没有选中内容');
      return false;
    }

    const level = this.getContentLevel();
    if (level !== 'inline') {
      console.warn(
        '[ContentHelper] replaceSelectionInline: 当前选中内容非行级',
      );
      return false;
    }

    try {
      const schema = this.editor.schema;
      // 将 JSONContent[] 转为 ProseMirror nodes
      const nodes = inlineContent.map((json) => schema.nodeFromJSON(json));

      // 验证所有节点必须是行级
      const allInline = nodes.every((n) => n.isInline || n.isText);
      if (!allInline) {
        console.warn(
          '[ContentHelper] replaceSelectionInline: 替换内容含有块级节点',
        );
        return false;
      }

      const fragment = Fragment.from(nodes);
      const { from, to } = selection;
      tr.replaceWith(from, to, fragment);

      if (dispatch) dispatch(tr);
      return true;
    } catch (e) {
      console.error('[ContentHelper] replaceSelectionInline error:', e);
      return false;
    }
  }

  // ------------------------------------------------------------------
  // ⑥ 替换选中的块级内容
  // ------------------------------------------------------------------
  replaceSelectionBlock(
    blockContent: JSONContent,
    tr: import('@tiptap/pm/state').Transaction,
    dispatch?: (tr: import('@tiptap/pm/state').Transaction) => void,
  ): boolean {
    try {
      const schema = this.editor.schema;
      const newNode = schema.nodeFromJSON(blockContent);

      if (!newNode.isBlock) {
        console.warn(
          '[ContentHelper] replaceSelectionBlock: 替换内容必须是块级节点',
        );
        return false;
      }

      const blockInfo = this.getCurrentBlockInfo();
      if (!blockInfo) return false;

      const { from, to } = blockInfo.range;
      tr.replaceWith(from, to, newNode);

      if (dispatch) dispatch(tr);
      return true;
    } catch (e) {
      console.error('[ContentHelper] replaceSelectionBlock error:', e);
      return false;
    }
  }

  // ------------------------------------------------------------------
  // ⑦ 在行级内容前后插入行级内容
  // ------------------------------------------------------------------
  insertInlineContent(
    inlineContent: JSONContent[],
    position: InsertPosition,
    tr: import('@tiptap/pm/state').Transaction,
    dispatch?: (tr: import('@tiptap/pm/state').Transaction) => void,
  ): boolean {
    try {
      const schema = this.editor.schema;
      const nodes = inlineContent.map((json) => schema.nodeFromJSON(json));

      const allInline = nodes.every((n) => n.isInline || n.isText);
      if (!allInline) {
        console.warn(
          '[ContentHelper] insertInlineContent: 插入内容含有块级节点',
        );
        return false;
      }

      const inlineInfo = this.getCurrentInlineInfo();
      if (!inlineInfo) return false;

      const fragment = Fragment.from(nodes);
      const insertPos =
        position === 'before' ? inlineInfo.range.from : inlineInfo.range.to;

      tr.insert(insertPos, fragment);

      if (dispatch) dispatch(tr);
      return true;
    } catch (e) {
      console.error('[ContentHelper] insertInlineContent error:', e);
      return false;
    }
  }

  // ------------------------------------------------------------------
  // ⑧ 在块级内容前后插入块级内容
  // ------------------------------------------------------------------
  insertBlockContent(
    blockContent: JSONContent,
    position: InsertPosition,
    tr: import('@tiptap/pm/state').Transaction,
    dispatch?: (tr: import('@tiptap/pm/state').Transaction) => void,
  ): boolean {
    try {
      const schema = this.editor.schema;
      const newNode = schema.nodeFromJSON(blockContent);

      if (!newNode.isBlock) {
        console.warn(
          '[ContentHelper] insertBlockContent: 插入内容必须是块级节点',
        );
        return false;
      }

      const blockInfo = this.getCurrentBlockInfo();
      if (!blockInfo) return false;

      const insertPos =
        position === 'before' ? blockInfo.range.from : blockInfo.range.to;

      tr.insert(insertPos, newNode);

      if (dispatch) dispatch(tr);
      return true;
    } catch (e) {
      console.error('[ContentHelper] insertBlockContent error:', e);
      return false;
    }
  }

  // ------------------------------------------------------------------
  // ⑨ 在块级内容前后插入多个段落块级内容
  // ------------------------------------------------------------------
  replaceSelectionBlockMulti(
    blockContent: JSONContent,
    tr: Transaction,
    dispatch?: (tr: Transaction) => void,
  ): boolean {
    try {
      const schema = this.editor.schema;
      const { from, to } = tr.selection;
      const doc = tr.doc;

      function cleanNode(node: JSONContent): JSONContent | null {
        if (node.type === 'text') {
          if (!node.text || node.text === '') return null;
          return node;
        }
        if (node.content && Array.isArray(node.content)) {
          const cleanedContent = node.content
            .map((child) => cleanNode(child))
            .filter((child): child is JSONContent => child !== null);
          return { ...node, content: cleanedContent };
        }
        return node;
      }

      const cleanedDoc = cleanNode(blockContent);
      if (!cleanedDoc?.content?.length) return false;

      // Slice JSON 会保留列表、引用等外层容器。例如列表选区的结构是
      // orderedList -> listItem -> paragraph，不能把顶层 orderedList 当成
      // paragraph 替换回 listItem，否则 ProseMirror 会为非法结构补空列表项。
      // 递归取出真正与下方 paragraphRanges 一一对应的文本块即可保留容器。
      function collectTextblocks(node: JSONContent): JSONContent[] {
        const nodeType = node.type ? schema.nodes[node.type] : undefined;
        if (nodeType?.isTextblock) return [node];

        return (node.content ?? []).flatMap((child) =>
          collectTextblocks(child),
        );
      }

      const newTextblocks = collectTextblocks(cleanedDoc);

      // 收集选区内所有段落信息
      interface ParagraphRange {
        node: import('@tiptap/pm/model').Node;
        nodePos: number; // 段落节点自身的起始 pos
        contentFrom: number; // 段落内容起始 pos（nodePos + 1）
        contentTo: number; // 段落内容结束 pos（nodePos + nodeSize - 1）
        selFrom: number; // 选区在本段落内的起始 pos
        selTo: number; // 选区在本段落内的结束 pos
      }

      const paragraphRanges: ParagraphRange[] = [];

      doc.nodesBetween(from, to, (node, pos) => {
        if (node.type.isTextblock) {
          const contentFrom = pos + 1;
          const contentTo = pos + node.nodeSize - 1;
          paragraphRanges.push({
            node,
            nodePos: pos,
            contentFrom,
            contentTo,
            selFrom: Math.max(from, contentFrom),
            selTo: Math.min(to, contentTo),
          });
        }
      });

      if (paragraphRanges.length === 0) return false;
      if (newTextblocks.length !== paragraphRanges.length) {
        console.warn(
          '[ContentHelper] replaceSelectionBlockMulti: 替换文本块数量与选区不一致',
        );
        return false;
      }

      // 从后往前替换，避免 pos 偏移
      for (let i = paragraphRanges.length - 1; i >= 0; i--) {
        const info = paragraphRanges[i];
        const newParaContent = newTextblocks[i];
        if (!newParaContent) continue;

        const cleaned = cleanNode(newParaContent);
        if (!cleaned) continue;

        // 获取格式化后的新内容 nodes
        const newContentNodes = (cleaned.content ?? [])
          .map((child) => {
            const c = cleanNode(child);
            return c ? schema.nodeFromJSON(c) : null;
          })
          .filter((n): n is import('@tiptap/pm/model').Node => n !== null);

        const isFirstParaPartial = info.selFrom > info.contentFrom;
        const isLastParaPartial = info.selTo < info.contentTo;

        if (!isFirstParaPartial && !isLastParaPartial) {
          // ✅ 整段都在选区内：直接替换整个段落节点
          const newNode = schema.nodeFromJSON(cleaned);
          tr.replaceWith(
            info.nodePos,
            info.nodePos + info.node.nodeSize,
            newNode,
          );
        } else {
          // ✅ 段落只有部分在选区内：只替换选中范围内的内容
          // 保留选区外的内容不动，只替换 selFrom ~ selTo 之间
          const fragment = Fragment.fromArray(newContentNodes);
          tr.replaceWith(info.selFrom, info.selTo, fragment);
        }
      }

      if (dispatch) dispatch(tr);
      return true;
    } catch (e) {
      console.error('[ContentHelper] replaceSelectionBlockMulti error:', e);
      return false;
    }
  }
}
