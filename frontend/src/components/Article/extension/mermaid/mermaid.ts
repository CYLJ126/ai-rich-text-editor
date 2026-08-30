import {cn} from "@/lib/utils";
import {mergeAttributes, Node} from "@tiptap/core";
import {NodeSelection} from "@tiptap/pm/state";
import {ReactNodeViewRenderer} from "@tiptap/react";
import {MermaidView} from "./mermaid-view";

export interface MermaidOptions {
  HTMLAttributes: Record<string, any>;
}

declare module "@tiptap/core" {
  interface Commands<ReturnType> {
    mermaidCommands: {
      setMermaid: (props: { code: string }) => ReturnType;
      updateMermaid: (props: { code: string }) => ReturnType;
    };
  }
}

/** 将多行文本转换为 TipTap inline content（用 hardBreak 表示换行） */
export function codeToContent(code: string) {
  const lines = code.split("\n");
  const content: any[] = [];

  lines.forEach((line, index) => {
    if (index > 0) {
      // 插入硬换行节点
      content.push({ type: "hardBreak" });
    }
    if (line.length > 0) {
      content.push({ type: "text", text: line });
    }
  });

  return content;
}

// ─── Mermaid 扩展 ───
export const Mermaid = Node.create<MermaidOptions>({
  name: "mermaid",
  group: "block",
  // 允许 hardBreak 节点，用于保留换行
  content: "(text | hardBreak)*",
  marks: "",
  atom: true,
  draggable: true,
  allowGapCursor: true,

  addAttributes() {
    return {
      "data-content-type": {
        default: this.name,
      },
    };
  },

  addCommands() {
    return {
      setMermaid: ({code}: any) => {
        return ({commands}: any) => {
          if (!code) {
            return false;
          }

          return commands.insertContent({
            type: this.name,
            content: codeToContent(code),
          });
        };
      },
      updateMermaid: ({code}: any) => {
        return ({state, commands}: any) => {
          if (!code) {
            return false;
          }

          const { selection } = state;

          if (!(selection instanceof NodeSelection)) {
            return false;
          }

          if (selection.node.type.name !== this.name) {
            return false;
          }

          const { from, to } = selection;

          return commands.insertContentAt(
              { from, to },
              {
                type: this.name,
                content: codeToContent(code),
              }
          );
        };
      },
    };
  },

  // TODO 从 Markdown 文本转换为 Tiptap 内容时，渲染成代码块了
  parseHTML() {
    return [{ tag: `div[data-content-type="${this.name}"]` }];
  },

  renderText({node}: any) {
    return node.textContent ?? "";
  },

  renderHTML({HTMLAttributes}: any) {
    return [
      "div",
      mergeAttributes(this.options.HTMLAttributes, HTMLAttributes, {
        class: cn("w-full"),
      }),
      0,
    ];
  },

  addNodeView() {
    return ReactNodeViewRenderer(MermaidView, {
      className: cn("relative border border-[var(--ant-color-border)] rounded-md my-4 p-2 bg-white dark:bg-[var(--ant-color-text)]"),
      attrs: () => {
        return {
          contentEditable: "false",
        };
      },
    });
  },

  renderMarkdown: (node: any, _helpers: any) => {
    if (node.type !== 'mermaid') return '';
    const parts: string[] = [];
    // node.content 是 ProseMirror Fragment
    const fragment = node.content;
    if (fragment) {
      fragment.forEach((child: any) => {
        const typeName = child.type?.name ?? child.type;
        if (typeName === 'hardBreak') {
          parts.push('\n');
        } else if (typeof child.text === 'string') {
          parts.push(child.text);
        }
      });
    }
    const code = parts.join('');
    return `\`\`\`mermaid\n${code}\n\`\`\``;
  },
} as any);
