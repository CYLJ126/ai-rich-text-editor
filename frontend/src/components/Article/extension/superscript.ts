import Superscript from '@tiptap/extension-superscript';

/** 上标 */
export const TiptapSuperscript = Superscript.extend({
  name: 'superscript',
  renderMarkdown: (node: any, helpers: any) => {
    if (node.type !== 'superscript') return;
    const content = helpers.renderChildren(node);
    return `<sup>${content}</sup>`;
  }
});
