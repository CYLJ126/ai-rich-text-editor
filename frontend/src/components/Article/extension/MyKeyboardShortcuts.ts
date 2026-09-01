import { Extension } from '@tiptap/core';
import { formatSelection } from '@/components/Article/components/editor/formatSelection';
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
        // 插入链接（如果当前粘贴板中有链接地址，则选中文字后直接 Mod + V 就能为文字添加链接）
        toggleLink(this.editor);
        return true;
      },
      'Mod-Shift-f': () => {
        // 格式化选中文本
        formatSelection(this.editor);
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
        // 在表格中，向上插入一行
        if (!this.editor.isActive('table')) return false;
        return this.editor.commands.addRowBefore();
      },
      'Mod-Enter': () => {
        // 在表格中，向下插入一行
        if (!this.editor.isActive('table')) return false;
        return this.editor.commands.addRowAfter();
      },
      'Mod-Backspace': () => {
        // 在表格中，删除当前行
        if (!this.editor.isActive('table')) return false;
        return this.editor.commands.deleteRow();
      },
    };
  },
});
