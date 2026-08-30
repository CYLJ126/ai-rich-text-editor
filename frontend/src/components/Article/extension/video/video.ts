import {createAtomBlockMarkdownSpec, mergeAttributes, Node, parseAttributes, serializeAttributes,} from '@tiptap/core';
import {ReactNodeViewRenderer} from '@tiptap/react';
import {DEFAULT_VIDEO_ATTRIBUTES, type VideoAttributes,} from './video-input-dialog';
import {VideoView} from './video-view';

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    video: {
      setVideo: (attributes: VideoAttributes) => ReturnType;
    };
  }
}

const VIDEO_ATTRIBUTE_NAMES = Object.keys(
  DEFAULT_VIDEO_ATTRIBUTES,
) as (keyof VideoAttributes)[];
const VIDEO_BOOLEAN_ATTRIBUTES = [
  'controls',
  'autoplay',
  'loop',
  'muted',
  'playsInline',
  'bilibiliDanmaku',
  'bilibiliShowPoster',
] as const satisfies readonly (keyof VideoAttributes)[];
const VIDEO_NUMBER_ATTRIBUTES = [
  'widthPercent',
  'bilibiliPage',
  'bilibiliStartTime',
] as const satisfies readonly (keyof VideoAttributes)[];

function serializeVideoAttributes(attributes: Record<string, unknown>) {
  return serializeAttributes(
    Object.fromEntries(
      Object.entries(attributes).map(([name, value]) => [
        name,
        value === false ? 'false' : value,
      ]),
    ),
  );
}

function parseVideoAttributes(value: string) {
  const attributes = parseAttributes(value);

  for (const name of VIDEO_BOOLEAN_ATTRIBUTES) {
    if (name in attributes) {
      attributes[name] =
        attributes[name] === true || attributes[name] === 'true';
    }
  }
  for (const name of VIDEO_NUMBER_ATTRIBUTES) {
    if (name in attributes) {
      const numberValue = Number(attributes[name]);
      attributes[name] = Number.isFinite(numberValue) ? numberValue : null;
    }
  }

  return attributes;
}

export const TiptapVideo = Node.create({
  name: 'video',
  group: 'block',
  atom: true,
  draggable: true,
  selectable: true,

  addAttributes() {
    return Object.fromEntries(
      VIDEO_ATTRIBUTE_NAMES.map((name) => [
        name,
        { default: DEFAULT_VIDEO_ATTRIBUTES[name] },
      ]),
    );
  },

  addCommands() {
    return {
      setVideo:
        (attributes) =>
        ({ commands }) =>
          commands.insertContent({
            type: this.name,
            attrs: attributes,
          }),
    };
  },

  parseHTML() {
    return [{ tag: 'div[data-content-type="video"]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'div',
      mergeAttributes({ 'data-content-type': 'video' }, HTMLAttributes),
    ];
  },

  addNodeView() {
    return ReactNodeViewRenderer(VideoView, {
      className: 'not-prose',
      attrs: () => ({ contentEditable: 'false' }),
    });
  },

  markdownTokenName: 'video',
  ...createAtomBlockMarkdownSpec({
    nodeName: 'video',
    name: 'video',
    defaultAttributes: DEFAULT_VIDEO_ATTRIBUTES,
    requiredAttributes: ['src'],
    allowedAttributes: VIDEO_ATTRIBUTE_NAMES,
    parseAttributes: parseVideoAttributes,
    serializeAttributes: serializeVideoAttributes,
  }),
});
