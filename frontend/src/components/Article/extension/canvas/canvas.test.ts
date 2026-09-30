import {Editor} from '@tiptap/core';
import Document from '@tiptap/extension-document';
import Paragraph from '@tiptap/extension-paragraph';
import Text from '@tiptap/extension-text';
import {Markdown} from '@tiptap/markdown';
import {afterEach, describe, expect, it, vi} from 'vitest';
import {CanvasBlock} from './canvas';

vi.mock('./canvas-block-view', () => ({CanvasBlockView: () => null}));

describe('canvas conversation persistence', () => {
  let editor: Editor;
  afterEach(() => editor?.destroy());

  it('retains the linked conversation through JSON and Markdown reloads', () => {
    const extensions = [Document, Paragraph, Text, Markdown, CanvasBlock];
    editor = new Editor({extensions, content: '<p></p>'});
    const aiAttrs = {aiConversationId: 'conversation-123', aiModelId: 7, aiModelName: 'First model'};
    editor.commands.insertCanvasBlock({canvasType: 'drawio', ...aiAttrs, sourceUrl: '/source.drawio'});
    const json = editor.getJSON();
    const markdown = editor.getMarkdown();
    expect(json.content?.find(node => node.type === 'canvasBlock')?.attrs).toMatchObject(aiAttrs);
    editor.commands.setContent(markdown, {contentType: 'markdown'});
    // Atom Markdown attributes are strings; the dialog restores the numeric model ID.
    expect(editor.getJSON().content?.find(node => node.type === 'canvasBlock')?.attrs).toMatchObject({...aiAttrs, aiModelId: '7'});
    editor.commands.setContent(json);
    expect(editor.getJSON().content?.find(node => node.type === 'canvasBlock')?.attrs?.sourceUrl).toBe('/source.drawio');
    expect(editor.getJSON().content?.find(node => node.type === 'canvasBlock')?.attrs).toMatchObject(aiAttrs);
  });

  it('opens existing canvas nodes without a conversation link', () => {
    editor = new Editor({extensions: [Document, Paragraph, Text, CanvasBlock],
      content: {type: 'doc', content: [{type: 'canvasBlock', attrs: {canvasType: 'mindmap', sourceUrl: '/existing.json'}}]}});
    expect(editor.getJSON().content?.[0].attrs?.aiConversationId).toBe('');
    expect(editor.getJSON().content?.[0].attrs?.aiModelId).toBe(0);
    expect(editor.getJSON().content?.[0].attrs?.aiModelName).toBe('');
  });
});
