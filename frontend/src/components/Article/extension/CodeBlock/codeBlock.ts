import CodeBlockLowlight from "@tiptap/extension-code-block-lowlight";
import {NodeViewRendererProps} from "@tiptap/core";
import {ReactNodeViewRenderer} from "@tiptap/react";
import CodeBlockView from "./CodeBlockView";
// 提前创建渲染器，避免每次调用 addNodeView 时重复初始化

const codeBlockRenderer = ReactNodeViewRenderer(CodeBlockView);

export const CustomCodeBlock = CodeBlockLowlight.extend({
  addNodeView() {
    return (props: NodeViewRendererProps) => {
      const language = props.node.attrs?.language as string | null;
      // mermaid 语言块跳过，交由独立的 mermaid 扩展处理
      if (language === "mermaid") {
        // 返回空对象，tiptap 会回退到默认的 DOM 渲染
        return {};
      }
      return codeBlockRenderer(props);
    };
  },
} as any);
