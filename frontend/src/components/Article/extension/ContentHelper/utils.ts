import type {Editor, JSONContent} from '@tiptap/core';
import {DOMSerializer, type Node as ProseMirrorNode} from '@tiptap/pm/model';

/**
 * 将 ProseMirror 节点序列化为 HTML 字符串
 */
export function nodeToHTML(node: ProseMirrorNode, editor: Editor): string {
  try {
    const schema = editor.schema;
    const serializer = DOMSerializer.fromSchema(schema);
    const fragment = serializer.serializeFragment(node.content);
    const div = document.createElement('div');
    div.appendChild(fragment);
    return div.innerHTML;
  } catch {
    return '';
  }
}

/**
 * 将节点本身（含标签）序列化为 HTML
 */
export function nodeToFullHTML(node: ProseMirrorNode, editor: Editor): string {
  try {
    const schema = editor.schema;
    const serializer = DOMSerializer.fromSchema(schema);
    const dom = serializer.serializeNode(node);
    const div = document.createElement('div');
    div.appendChild(dom);
    return div.innerHTML;
  } catch {
    return '';
  }
}

/**
 * 将 JSONContent 转换为 HTML（通过临时 editor 解析）
 */
export function jsonContentToHTML(
  editor: Editor,
  content: JSONContent,
): string {
  try {
    // 创建临时节点来序列化
    const schema = editor.schema;
    const node = schema.nodeFromJSON(content);
    return nodeToFullHTML(node, editor);
  } catch {
    return '';
  }
}
