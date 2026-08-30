import Subscript from '@tiptap/extension-subscript';

/** 下标 */
export const TiptapSubscript = Subscript.extend({
  name: 'subscript',
  renderMarkdown: (node: any, helpers: any) => {
    if (node.type !== 'subscript') return;
    const content = helpers.renderChildren(node);
    return `<sub>${content}</sub>`;
  }
});
