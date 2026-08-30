import {Extension} from "@tiptap/core";
import {Plugin, PluginKey} from "@tiptap/pm/state";
import {Decoration, DecorationSet} from "@tiptap/pm/view";

export interface SelectionOptions {
  HTMLAttributes: Record<string, any>;
}

/**
 * 选区高亮扩展
 * 当编辑器失去焦点时，保持选中文字的高亮显示效果。
 * 用 ProseMirror Decoration（装饰器） 模拟浏览器原生选区高亮，解决编辑器失焦后选中状态不可见的 UX 问题。
 *
 * 使用场景：
 * 用户在编辑器中选中一段文字
 *          ↓
 * 点击编辑器外部（编辑器失焦）
 *          ↓
 * 浏览器原生选中高亮消失
 *          ↓
 * 此扩展用 .selection CSS class 补充显示高亮
 *          ↓
 * 用户仍能看到之前选中的范围
 */
export const Selection = Extension.create<SelectionOptions>({
  name: "selection",

  addProseMirrorPlugins() {
    const {editor} = this;

    return [
      new Plugin({
        key: new PluginKey("selection"),
        props: {
          decorations(state) {
            if (state.selection.empty) {
              return null;
            }

            if (editor.isFocused === true) {
              return null;
            }

            return DecorationSet.create(state.doc, [
              Decoration.inline(state.selection.from, state.selection.to, {
                class: "selection",
              }),
            ]);
          },
        },
      }),
    ];
  },
});
