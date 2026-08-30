import {Node} from "@tiptap/core";
import {ReactNodeViewRenderer} from "@tiptap/react";
import AiWriterView from "./AiWriterView";

export interface AiWriterOptions {
  HTMLAttributes: Record<string, any>;
}

declare module "@tiptap/core" {
  interface Commands<ReturnType> {
    aiWriterCommands: {
      setAiWriter: () => ReturnType;
    };
  }
}

/**
 * AI Writer 续写扩展
 */
export const AiWriter = Node.create<AiWriterOptions>({
  name: "aiWriter",
  group: "block",
  marks: "",
  atom: true,

  addOptions() {
    return {
      HTMLAttributes: {},
    };
  },

  addCommands() {
    return {
      setAiWriter:
        () =>
          ({editor, chain}: any) => {
            const $aiWriter = editor.$node(this.name);
            if ($aiWriter) {
              return false;
            }

            return chain()
              .insertContent({
                type: this.name,
              })
              .setMeta("preventUpdate", true)
              .run();
          },
    };
  },

  renderHTML() {
    return ["div"];
  },

  addNodeView() {
    return ReactNodeViewRenderer(AiWriterView, {
      className: this.options.HTMLAttributes.class,
    });
  },
} as any);
