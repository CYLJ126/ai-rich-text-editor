import {Editor} from '@tiptap/core';
import Document from '@tiptap/extension-document';
import Paragraph from '@tiptap/extension-paragraph';
import Text from '@tiptap/extension-text';
import {afterEach, describe, expect, it, vi} from 'vitest';
import {configureMathFormula} from './index';

vi.mock('@/components', () => ({
  useEditorStore: (selector: (state: { operationMode: string }) => unknown) =>
    selector({operationMode: 'edit'}),
}));

describe('MathFormula paste rules', () => {
  let editor: Editor | undefined;

  afterEach(() => {
    editor?.destroy();
    editor = undefined;
  });

  it('converts single-dollar content to inline math and removes adjacent spaces', () => {
    editor = new Editor({
      extensions: [Document, Paragraph, Text, configureMathFormula()],
      content: '<p></p>',
    });

    editor.commands.insertContent('这个 $y = ax + b$ 公式是', {
      applyPasteRules: true,
    });

    expect(editor.getJSON()).toEqual({
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            {type: 'text', text: '这个'},
            {type: 'inlineMath', attrs: {latex: 'y = ax + b'}},
            {type: 'text', text: '公式是'},
          ],
        },
      ],
    });
  });

  it('does not treat escaped or double-dollar syntax as inline pasted math', () => {
    editor = new Editor({
      extensions: [Document, Paragraph, Text, configureMathFormula()],
      content: '<p></p>',
    });

    editor.commands.insertContent('保留 \\$x\\$ 和 $$y$$', {
      applyPasteRules: true,
    });

    expect(editor.getJSON()).toEqual({
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [{type: 'text', text: '保留 \\$x\\$ 和 $$y$$'}],
        },
      ],
    });
  });

  it.each([
    ['LF', '\n'],
    ['CRLF', '\r\n'],
    ['line separator', '\u2028'],
    ['paragraph separator', '\u2029'],
    ['Tiptap hard-break placeholder', '\uFFFC'],
  ])('does not pair dollar signs across a %s', (_, lineBreak) => {
    editor = new Editor({
      extensions: [Document, Paragraph, Text, configureMathFormula()],
      content: '<p></p>',
    });

    const pastedText = `中天$${lineBreak}这是一个$`;
    editor.commands.insertContent(pastedText, {applyPasteRules: true});

    expect(editor.getJSON()).toEqual({
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [{type: 'text', text: pastedText}],
        },
      ],
    });
  });

  it('pairs multiple dollar-delimited formulas independently on one line', () => {
    editor = new Editor({
      extensions: [Document, Paragraph, Text, configureMathFormula()],
      content: '<p></p>',
    });

    editor.commands.insertContent('甲$x$乙$y$丙', {
      applyPasteRules: true,
    });

    expect(editor.getJSON()).toEqual({
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            {type: 'text', text: '甲'},
            {type: 'inlineMath', attrs: {latex: 'x'}},
            {type: 'text', text: '乙'},
            {type: 'inlineMath', attrs: {latex: 'y'}},
            {type: 'text', text: '丙'},
          ],
        },
      ],
    });
  });
});
