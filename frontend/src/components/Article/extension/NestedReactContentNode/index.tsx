import {NodeViewContent, NodeViewWrapper, ReactNodeViewRenderer} from '@tiptap/react';
import styles from './index.less';
import React from 'react';
import {createBlockMarkdownSpec, Node} from '@tiptap/core';

const NestedContentComponent = ({ node }: any) => {
  return (
    <NodeViewWrapper className={styles.nestedContentNode}>
      <div className={styles.divWrapper}>
        <h4 style={{ margin: '0 0 8px 0' }}>
          {node.attrs.title || 'Nested Content Component'}
        </h4>
        <p style={{ margin: 0, color: '#374151' }}>
          {node.attrs.content || 'Content - This is a custom React node view!'}
        </p>
        <div>
          <NodeViewContent />
        </div>
      </div>
    </NodeViewWrapper>
  );
};

const NestedReactContentNode = Node.create({
  name: 'nestedReactContentNode',
  group: 'block',
  content: 'block+',
  addAttributes() {
    return {
      content: {
        default: 'Content - This is a custom React node view!',
      },
      title: {
        default: 'Title - Nested React Content Node',
      },
    };
  },

  parseHTML() {
    return [
      {
        tag: 'div[data-type="custom-react-node"]',
      },
    ];
  },

  renderHTML({ HTMLAttributes }) {
    return ['div', { 'data-type': 'nested-react-content-node', ...HTMLAttributes }, 0];
  },

  addNodeView() {
    return ReactNodeViewRenderer(NestedContentComponent);
  },
  markdownTokenName: 'nestedReactContentNode',
  ...createBlockMarkdownSpec({
    nodeName: 'nestedReactContentNode',
    name: 'nestedContent',
  }),
});

/**
 * 自定义嵌套组件
 *
 * 未渲染内容形如：
 * :::nestedContent {content="This is a custom React node view with fenced syntax!" title="自定义嵌套标题"}
 *
 * Isn't this great?
 *
 * :::
 *
 * :::nestedContent {content="Here is another custom React node view with more content!" title="一级嵌套标题"}
 *
 * Another one with even more inline content to **edit**!
 *
 * :::nestedContent {content="Nested node" title="二级嵌套标题"}
 *
 * Nested content is also supported!
 *
 * :::
 *
 * :::
 */
export default NestedReactContentNode;
