import {Editor} from '@tiptap/core';
import Document from '@tiptap/extension-document';
import Paragraph from '@tiptap/extension-paragraph';
import {TableCell, TableHeader, TableRow} from '@tiptap/extension-table';
import Text from '@tiptap/extension-text';
import {Markdown} from '@tiptap/markdown';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import ClipboardUtil from '@/utils/ClipboardUtil';
import {CustomTable} from './table';
import {copyTableAsMarkdown, deleteTableAt, serializeTableToMarkdown,} from './table-clipboard';

const markdown = `Before

| Name | Value |
| --- | --- |
| Alpha | 1 |

After`;

const serializedTable =
  '| Name  | Value |\n| ----- | ----- |\n| Alpha | 1     |';

const findTablePosition = (editor: Editor) => {
  let tablePos: number | undefined;
  editor.state.doc.descendants((node, pos) => {
    if (node.type.name === 'table') {
      tablePos = pos;
      return false;
    }
    return true;
  });
  return tablePos;
};

describe('table clipboard actions', () => {
  let editor: Editor;

  beforeEach(() => {
    editor = new Editor({
      extensions: [
        Document,
        Paragraph,
        Text,
        Markdown,
        CustomTable,
        TableRow,
        TableHeader,
        TableCell,
      ],
      content: markdown,
      contentType: 'markdown',
    });
  });

  afterEach(() => {
    editor.destroy();
    vi.restoreAllMocks();
  });

  it('serializes only the selected table as Markdown', () => {
    const tablePos = findTablePosition(editor);
    expect(tablePos).toBeTypeOf('number');

    const tableNode = editor.state.doc.nodeAt(tablePos as number);
    if (!tableNode) {
      throw new Error('Expected the document to contain a table');
    }
    expect(serializeTableToMarkdown(editor, tableNode)).toBe(serializedTable);
  });

  it('copies the table Markdown to the clipboard', async () => {
    const writeText = vi
      .spyOn(ClipboardUtil, 'writeText')
      .mockResolvedValue(true);
    const tablePos = findTablePosition(editor);

    await expect(copyTableAsMarkdown(editor, tablePos as number)).resolves.toBe(
      true,
    );
    expect(writeText).toHaveBeenCalledWith(serializedTable);
  });

  it('deletes only the target table', () => {
    const tablePos = findTablePosition(editor);

    expect(deleteTableAt(editor, tablePos as number)).toBe(true);
    expect(editor.getText()).toContain('Before');
    expect(editor.getText()).toContain('After');
    expect(findTablePosition(editor)).toBeUndefined();
  });
});
