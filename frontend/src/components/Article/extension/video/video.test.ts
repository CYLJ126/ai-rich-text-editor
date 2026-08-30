import {Editor} from '@tiptap/core';
import Document from '@tiptap/extension-document';
import Paragraph from '@tiptap/extension-paragraph';
import Text from '@tiptap/extension-text';
import {Markdown} from '@tiptap/markdown';
import {afterEach, describe, expect, it, vi} from 'vitest';
import {getSuggestions} from '../slash-command';
import {TiptapVideo} from './video';
import {DEFAULT_VIDEO_ATTRIBUTES} from './video-input-dialog';

vi.mock('./video-view', () => ({ VideoView: () => null }));
vi.mock('../slash-command/suggestion-list', () => ({ default: () => null }));

describe('TiptapVideo', () => {
  let editor: Editor | undefined;

  afterEach(() => {
    editor?.destroy();
    editor = undefined;
  });

  it('inserts and serializes a video node with player attributes', () => {
    editor = new Editor({
      extensions: [Document, Paragraph, Text, Markdown, TiptapVideo],
      content: '<p></p>',
    });

    const attributes = {
      ...DEFAULT_VIDEO_ATTRIBUTES,
      src: 'https://vjs.zencdn.net/v/oceans.mp4',
      poster: 'https://picsum.photos/800/600',
      aspectRatio: '4 / 3',
      widthPercent: 65,
      muted: true,
      bilibiliDanmaku: false,
    };

    expect(editor.commands.setVideo(attributes)).toBe(true);

    const video = editor
      .getJSON()
      .content?.find((node) => node.type === 'video');
    expect(video?.attrs).toMatchObject(attributes);
    expect(editor.getHTML()).toContain('data-content-type="video"');
    const markdown = editor.getMarkdown();
    expect(markdown).toContain(attributes.src);

    editor.destroy();
    editor = new Editor({
      extensions: [Document, Paragraph, Text, Markdown, TiptapVideo],
      content: markdown,
      contentType: 'markdown',
    } as never);

    const restoredVideo = editor
      .getJSON()
      .content?.find((node) => node.type === 'video');
    expect(restoredVideo?.attrs).toMatchObject(attributes);
  });

  it('appears in slash command search results', () => {
    const suggestions = getSuggestions();
    const items = suggestions.items?.({ query: 'video' } as never) ?? [];

    expect(items).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ id: 'video', title: 'Video' }),
      ]),
    );
  });
});
