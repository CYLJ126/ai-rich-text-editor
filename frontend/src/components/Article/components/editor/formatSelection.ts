import type { Editor, JSONContent } from '@tiptap/core';
import { type CustomProperty, TextProcessor } from '@/utils/textProcessor';

const formatProperty: CustomProperty = {
  zhOrEn: true,
  punctuationMark: true,
  clearBreakLine: false,
  compressSpace: true,
  withSpace: true,
  pasteFromClipboard: false,
  rewriteClipboard: false,
  isHandleClipboard: false,
  handleList: false,
  handleMarkdownTable: false,
};

/**
 * 递归格式化节点
 * @param node 被选中的节点对象
 */
function formatNode(node: any): any {
  if (node.type === 'text' && typeof node.text === 'string') {
    const formatted = TextProcessor.handleChinese(formatProperty, node.text);
    if (formatted === '') return null;
    return {
      ...node,
      text: formatted,
    };
  }
  if (node.content && Array.isArray(node.content)) {
    return {
      ...node,
      content: node.content
        .map((child: any) => formatNode(child))
        .filter((child: any) => child !== null),
    };
  }
  return node;
}

/**
 * 格式化选区内容
 * @param editor 编辑器实例
 */
export function formatSelection(editor: Editor | null | undefined) {
  const rawSelection = editor?.commands?.getSelectionInfo() as any;
  if (!rawSelection?.hasSelection) return;

  const contentLevel = editor?.commands?.getContentLevel();
  const formattedText = TextProcessor.handleChinese(
    formatProperty,
    rawSelection.text,
  );

  if (contentLevel === 'inline') {
    editor?.commands?.replaceSelectionInline([
      { type: 'text', text: formattedText } as JSONContent,
    ]);
  } else {
    const cleanDoc: JSONContent = {
      type: 'doc',
      content: (rawSelection.content?.content ?? []).map((paragraph: any) =>
        formatNode(paragraph),
      ),
    };
    editor?.commands?.replaceSelectionBlockMulti(cleanDoc);
  }

  // 格式化后恢复编辑器焦点和选区。
  editor?.chain().focus('end');
}
