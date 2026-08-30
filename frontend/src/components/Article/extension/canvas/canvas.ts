import {
  createAtomBlockMarkdownSpec,
  mergeAttributes,
  Node,
} from '@tiptap/core';
import { ReactNodeViewRenderer } from '@tiptap/react';
import { CanvasBlockView } from './canvas-block-view';

export const CANVAS_TYPES = ['drawio', 'mindmap', 'whiteboard'] as const;

export type CanvasType = (typeof CANVAS_TYPES)[number];

export interface CanvasBlockAttrs {
  canvasType: CanvasType;
  title: string;
  sourceUrl: string;
  data: string;
  preview: string;
  schemaVersion: number;
}

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    canvasBlock: {
      insertCanvasBlock: (
        attrs: Partial<CanvasBlockAttrs> & { canvasType: CanvasType },
      ) => ReturnType;
      updateCanvasBlock: (attrs: Partial<CanvasBlockAttrs>) => ReturnType;
    };
  }
}

export const CanvasBlock = Node.create({
  name: 'canvasBlock',
  group: 'block',
  atom: true,
  draggable: true,
  selectable: true,

  addAttributes() {
    return {
      canvasType: { default: 'drawio' },
      title: { default: '' },
      sourceUrl: { default: '' },
      data: { default: '' },
      preview: { default: '' },
      schemaVersion: { default: 4 },
    };
  },

  addCommands() {
    return {
      insertCanvasBlock:
        (attrs) =>
        ({ commands }) =>
          commands.insertContent({
            type: this.name,
            attrs,
          }),
      updateCanvasBlock:
        (attrs) =>
        ({ commands }) =>
          commands.updateAttributes(this.name, attrs),
    };
  },

  parseHTML() {
    return [{ tag: 'div[data-content-type="canvas-block"]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'div',
      mergeAttributes({ 'data-content-type': 'canvas-block' }, HTMLAttributes),
    ];
  },

  addNodeView() {
    return ReactNodeViewRenderer(CanvasBlockView, {
      className: 'not-prose',
      attrs: () => ({ contentEditable: 'false' }),
    });
  },

  markdownTokenName: 'canvasBlock',
  ...createAtomBlockMarkdownSpec({
    nodeName: 'canvasBlock',
    name: 'canvas',
    allowedAttributes: [
      'canvasType',
      'title',
      'sourceUrl',
      'data',
      'preview',
      'schemaVersion',
    ],
  }),
});
