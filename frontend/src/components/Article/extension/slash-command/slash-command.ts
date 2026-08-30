import {Editor, Extension, Range} from "@tiptap/core";
import {PluginKey} from "@tiptap/pm/state";
import Suggestion, {SuggestionOptions} from "@tiptap/suggestion";

type OnCommandSelect = (props: { editor: Editor; range: Range }) => void;

export interface SuggestionItem {
  id: string;
  title: string;
  description: string;
  keywords: string[];
  command: OnCommandSelect;
}

export interface SlashCommandNodeAttrs {
  command: OnCommandSelect;
}

export interface SlashCommandOptions<Item extends SuggestionItem = any> {
  /**
   * 命令建议选项。
   * @default {}
   * @example { char: '/', pluginKey: slashCommandPluginKey, command: ({ editor, range, props }) => { ... } }
   */
  suggestion: Omit<SuggestionOptions<Item, SlashCommandNodeAttrs>, "editor">;
}

/**
 * 斜杠命令扩展 key。
 */
export const slashCommandPluginKey = new PluginKey("slashCommand");

/**
 * 斜杠命令扩展，输入 / 后显示命令列表，点击命令后执行命令。
 */
export const SlashCommand = Extension.create<SlashCommandOptions>({
  name: "slashCommand",

  addOptions() {
    return {
      suggestion: {
        char: "/",
        pluginKey: slashCommandPluginKey,
        command: ({editor, range, props}) => {
          props.command({editor, range});
        },
        allow: ({editor}) => {
          return !editor.isActive("codeBlock");

        },
      },
    };
  },

  addProseMirrorPlugins() {
    return [
      Suggestion({
        editor: this.editor,
        ...this.options.suggestion,
      }),
    ];
  },
});
