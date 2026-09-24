import {Fragment, type Node as ProseMirrorNode, Slice,} from '@tiptap/pm/model';
import type {Editor} from '@tiptap/react';
import ClipboardUtil from '@/utils/ClipboardUtil';

const getTableNode = (editor: Editor, tablePos: number) => {
  const node = editor.state.doc.nodeAt(tablePos);
  return node?.type.name === 'table' ? node : null;
};

const copyHtmlFallback = (html: string) => {
  const container = document.createElement('div');
  container.contentEditable = 'true';
  container.style.cssText =
    'position:fixed;left:-10000px;top:0;opacity:0;pointer-events:none;';
  container.innerHTML = html;
  document.body.appendChild(container);

  const selection = window.getSelection();
  const savedRanges = selection
    ? Array.from({length: selection.rangeCount}, (_, index) =>
      selection.getRangeAt(index).cloneRange(),
    )
    : [];
  const range = document.createRange();
  range.selectNodeContents(container);
  selection?.removeAllRanges();
  selection?.addRange(range);

  let copied = false;
  try {
    copied = document.execCommand('copy');
  } catch (error) {
    console.warn('兼容模式写入富文本剪贴板失败', error);
  } finally {
    selection?.removeAllRanges();
    savedRanges.forEach((savedRange) => {
      selection?.addRange(savedRange);
    });
    container.remove();
  }

  return copied;
};

const writeRichClipboard = async (
  html: string,
  plainText: string,
): Promise<boolean> => {
  if (navigator.clipboard?.write && typeof ClipboardItem !== 'undefined') {
    try {
      await navigator.clipboard.write([
        new ClipboardItem({
          'text/html': new Blob([html], {type: 'text/html'}),
          'text/plain': new Blob([plainText], {type: 'text/plain'}),
        }),
      ]);
      return true;
    } catch (error) {
      console.warn('写入富文本剪贴板失败，尝试兼容方案', error);
    }
  }

  return copyHtmlFallback(html) || ClipboardUtil.writeText(plainText);
};

export const serializeTableToMarkdown = (
  editor: Editor,
  tableNode: ProseMirrorNode,
) => {
  return editor.storage.markdown.manager.serialize(tableNode.toJSON()).trim();
};

export const copyTable = async (editor: Editor, tablePos: number) => {
  const tableNode = getTableNode(editor, tablePos);
  if (!tableNode) {
    return false;
  }

  const slice = new Slice(Fragment.from(tableNode), 0, 0);
  const {dom, text} = editor.view.serializeForClipboard(slice);
  const markdown = serializeTableToMarkdown(editor, tableNode);

  return writeRichClipboard(dom.innerHTML, markdown || text);
};

export const copyTableAsMarkdown = async (editor: Editor, tablePos: number) => {
  const tableNode = getTableNode(editor, tablePos);
  if (!tableNode) {
    return false;
  }

  return ClipboardUtil.writeText(serializeTableToMarkdown(editor, tableNode));
};

export const deleteTableAt = (editor: Editor, tablePos: number) => {
  const tableNode = getTableNode(editor, tablePos);
  if (!tableNode) {
    return false;
  }

  editor.view.dispatch(
    editor.state.tr
      .delete(tablePos, tablePos + tableNode.nodeSize)
      .scrollIntoView(),
  );
  editor.commands.focus();
  return true;
};

export const cutTable = async (editor: Editor, tablePos: number) => {
  const copied = await copyTable(editor, tablePos);
  return copied ? deleteTableAt(editor, tablePos) : false;
};
