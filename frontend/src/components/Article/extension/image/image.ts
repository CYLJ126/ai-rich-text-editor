import Image from '@tiptap/extension-image';
import {ReactNodeViewRenderer} from '@tiptap/react';
import {ImageView} from './image-view';

export const TiptapImage = Image.extend({
  addNodeView() {
    return ReactNodeViewRenderer(ImageView, {
      className: 'not-prose',
      attrs: () => ({contentEditable: 'false'}),
    });
  },
}).configure({
  allowBase64: false,
  resize: false,
  HTMLAttributes: {
    class: 'tiptap-img',
  },
});
