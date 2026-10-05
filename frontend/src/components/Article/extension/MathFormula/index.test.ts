import {Editor} from '@tiptap/core';
import Document from '@tiptap/extension-document';
import Paragraph from '@tiptap/extension-paragraph';
import Text from '@tiptap/extension-text';
import {Markdown} from '@tiptap/markdown';
import {afterEach, describe, expect, it, vi} from 'vitest';
import {configureMathFormula} from './index';
import modalBridge, {type MathFormulaType} from './modalBridge';

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

describe('MathFormula modal updates', () => {
  const editors: Editor[] = [];

  afterEach(() => {
    editors.forEach((editor) => editor.destroy());
    editors.length = 0;
    modalBridge.handler = null;
  });

  const createEditor = async (type: MathFormulaType, enableClickEdit = true) => {
    const math = {type: `${type}Math`, attrs: {latex: 'x'}};
    const editor = new Editor({
      extensions: [Document, Paragraph, Text, Markdown, configureMathFormula({enableClickEdit})],
      content: {
        type: 'doc',
        content: [
          {type: 'paragraph', content: [{type: 'text', text: 'Before'}]},
          type === 'block' ? math : {
            type: 'paragraph',
            content: [{type: 'text', text: 'Left'}, math, {type: 'text', text: 'Right'}],
          },
          {type: 'paragraph', content: [{type: 'text', text: 'After'}]},
        ],
      },
    });
    editors.push(editor);
    await vi.waitFor(() => expect(editor.isInitialized).toBe(true));
    editor.commands.setTextSelection(1);
    return editor;
  };

  const openFormula = (editor: Editor, type: MathFormulaType) => {
    const openModal = vi.fn();
    modalBridge.handler = {openModal};
    editor.view.dom.querySelector<HTMLElement>(`[data-type="${type}-math"]`)!
      .click();
    expect(openModal).toHaveBeenCalledWith(type, 'x', expect.any(Function));
    return openModal.mock.calls[0][2] as (latex: string, type: MathFormulaType) => void;
  };

  it.each(['inline', 'block'] as const)('updates clicked %s math with the cursor elsewhere and persists it', async (type) => {
    const editor = await createEditor(type);
    const onUpdate = vi.fn();
    editor.on('update', onUpdate);
    const confirm = openFormula(editor, type);

    confirm('y^2', type);

    expect(editor.view.dom.querySelector(`[data-type="${type}-math"]`))
      .toHaveAttribute('data-latex', 'y^2');
    expect(onUpdate).toHaveBeenCalledOnce();
    const savedMarkdown = editor.getMarkdown();
    editor.commands.setContent(savedMarkdown, {contentType: 'markdown'});
    expect(editor.view.dom.querySelector(`[data-type="${type}-math"]`))
      .toHaveAttribute('data-latex', 'y^2');
  });

  it.each(['inline', 'block'] as const)('keeps %s updates in the owning editor when another editor mounts', async (type) => {
    const editor = await createEditor(type);
    const confirm = openFormula(editor, type);
    const otherEditor = await createEditor(type);

    confirm('z', type);

    expect(editor.view.dom.querySelector(`[data-type="${type}-math"]`))
      .toHaveAttribute('data-latex', 'z');
    expect(otherEditor.view.dom.querySelector(`[data-type="${type}-math"]`))
      .toHaveAttribute('data-latex', 'x');
  });

  it('keeps updates working after another editor is destroyed', async () => {
    const editor = await createEditor('block');
    const otherEditor = await createEditor('block');
    otherEditor.destroy();
    const confirm = openFormula(editor, 'block');

    confirm('z', 'block');

    expect(editor.view.dom.querySelector('[data-type="block-math"]'))
      .toHaveAttribute('data-latex', 'z');
  });

  it.each([
    ['inline', 'block'],
    ['block', 'inline'],
  ] as const)('changes formula type from %s to %s without losing surrounding text', async (type, newType) => {
    const editor = await createEditor(type);
    const confirm = openFormula(editor, type);
    const originalText = editor.state.doc.textContent;

    confirm('z', newType);

    expect(editor.view.dom.querySelector(`[data-type="${newType}-math"]`))
      .toHaveAttribute('data-latex', 'z');
    expect(editor.view.dom.querySelector(`[data-type="${type}-math"]`)).toBeNull();
    expect(editor.state.doc.textContent).toBe(originalText);
  });

  it('enables click editing by default', async () => {
    const editor = new Editor({
      extensions: [Document, Paragraph, Text, configureMathFormula()],
      content: {type: 'doc', content: [{type: 'blockMath', attrs: {latex: 'x'}}]},
    });
    editors.push(editor);
    await vi.waitFor(() => expect(editor.isInitialized).toBe(true));

    openFormula(editor, 'block')('z', 'block');

    expect(editor.view.dom.querySelector('[data-type="block-math"]'))
      .toHaveAttribute('data-latex', 'z');
  });

  it('respects disabled click editing', async () => {
    const editor = await createEditor('block', false);
    const openModal = vi.fn();
    modalBridge.handler = {openModal};

    editor.view.dom.querySelector<HTMLElement>('[data-type="block-math"]')!.click();

    expect(openModal).not.toHaveBeenCalled();
  });
});
