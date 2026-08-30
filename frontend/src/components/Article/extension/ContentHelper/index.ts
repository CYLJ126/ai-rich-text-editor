import {Editor, JSONContent} from '@tiptap/core';
import type {InsertPosition} from './types';

export {ContentHelperExtension} from './ContentHelperExtension';
export type {
  BlockInfo,
  ContentLevel,
  InlineInfo,
  InsertPosition,
  SelectionInfo,
} from './types';

/**
 * 便捷 Hook：直接通过 editor 实例调用查询方法（非 Command 方式）
 */
export function useContentHelper(editor: Editor | null) {
  if (!editor) return null;

  return {
    /** ① 获取内容层级 */
    getContentLevel: () => editor.commands.getContentLevel(),

    /** ② 获取选中信息 */
    getSelectionInfo: () => editor.commands.getSelectionInfo(),

    /** ③ 获取当前块级内容 */
    getCurrentBlockInfo: () => editor.commands.getCurrentBlockInfo(),

    /** ④ 获取当前行级内容 */
    getCurrentInlineInfo: () => editor.commands.getCurrentInlineInfo(),

    /** ⑤ 替换选中行级内容 */
    replaceSelectionInline: (content: JSONContent[]) =>
      editor.commands.replaceSelectionInline(content),

    /** ⑥ 替换选中块级内容 */
    replaceSelectionBlock: (content: JSONContent) =>
      editor.commands.replaceSelectionBlock(content),

    /** ⑦ 插入行级内容 */
    insertInlineContent: (
      content: JSONContent[],
      position: InsertPosition,
    ) => editor.commands.insertInlineContent(content, position),

    /** ⑧ 插入块级内容 */
    insertBlockContent: (
      content: JSONContent,
      position: InsertPosition,
    ) => editor.commands.insertBlockContent(content, position),
  };
}
