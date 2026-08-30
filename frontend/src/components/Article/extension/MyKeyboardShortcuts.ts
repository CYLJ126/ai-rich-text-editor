import { Extension } from '@tiptap/core';
import { toggleLink } from '@/components/Article/extension/MyLink';

/**
 * 快捷键配置扩展
 * 官方默认快捷键：https://tiptap.dev/docs/editor/core-concepts/keyboard-shortcuts
 * 注意：
 * 1. 不要在调用过程中做 message 提示，这里只做唤起操作，不做逻辑处理
 * 2. 必须同步返回 true，告知 ProseMirror 该事件已被处理
 */
export interface KeyboardShortcutsOptions {
  /** 保存到后端的函数 */
  save: () => void;
}

export const MyKeyboardShortcuts = Extension.create<KeyboardShortcutsOptions>({
  name: 'keyboardShortcuts',

  addOptions() {
    return {
      save: async () => false,
    };
  },

  addKeyboardShortcuts() {
    const { options } = this;

    return {
      'Mod-s': () => {
        // 先异步执行保存，但同步返回 true 阻止浏览器默认行为
        options.save();
        // 必须同步返回 true，告知 ProseMirror 该事件已被处理
        return true;
      },
      'Mod-k': () => {
        toggleLink(this.editor);
        return true;
      },
      'Mod-Alt-Shift-t': () => {
        //插入表格
        this.editor
          .chain()
          .focus()
          .insertTable({ rows: 3, cols: 3, withHeaderRow: true })
          .run();
        return true;
      },
      'Mod-Shift-Enter': () => {
        if (!this.editor.isActive('table')) return false;
        return this.editor.commands.addRowBefore();
      },
      'Mod-Enter': () => {
        if (!this.editor.isActive('table')) return false;
        return this.editor.commands.addRowAfter();
      },
      'Mod-Backspace': () => {
        if (!this.editor.isActive('table')) return false;
        return this.editor.commands.deleteRow();
      },
    };
  },
});
