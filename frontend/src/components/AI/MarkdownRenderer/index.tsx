import { i18nText } from '@/utils/i18n';
import React, { memo, useEffect } from 'react';
import { EditorContent, useEditor } from '@tiptap/react';
import 'katex/dist/katex.min.css';
import { defaultExtensions } from '@/components/Article/extension/defaultExtensions';
import useStyles from './style';

// ─── 构建 Tiptap 扩展列表（稳定引用，避免每次渲染重建） ───

/**
 * 构建只读渲染所需的全部 Tiptap 扩展
 */
function buildExtensions() {
  return [...defaultExtensions];
}

// 模块级稳定引用，避免 useEditor 因扩展数组引用变化而重建
const EXTENSIONS = buildExtensions();

interface MarkdownRendererProps {
  content: string;
}

// ─── 主组件：渲染 AI 响应内容为 Markdown 格式 ───
const MarkdownRenderer: React.FC<MarkdownRendererProps> = memo(
  ({ content }) => {
    const { styles } = useStyles();

    const editor = useEditor({
      // ── 只读模式 ───
      editable: false,

      extensions: EXTENSIONS,

      // ── 初始内容（Markdown 字符串） ───
      // tiptap-markdown 扩展会将 Markdown 字符串转换为 ProseMirror 文档
      content,

      // ── 禁用不必要的编辑器特性以提升性能 ───
      enableInputRules: false,
      enablePasteRules: false,

      // ── 无障碍属性 ───
      editorProps: {
        attributes: {
          role: 'article',
          'aria-label': i18nText('app.ai.markdown.content'),
          // 阻止用户选中触发工具栏（只读模式不需要）
          spellcheck: 'false',
        },
      },
    });

    // ── 内容变化时同步更新编辑器 ───
    // 使用 commands.setContent 而非销毁重建，性能更优
    useEffect(() => {
      if (!editor || editor.isDestroyed) return;

      // 获取当前编辑器中的 Markdown 内容，与新内容对比，避免无效更新
      const current = editor.getMarkdown() ?? '';
      if (current === content) return;

      // setContent 第二个参数 false 表示不触发 onUpdate 回调，避免循环
      editor.commands.setContent(content, {
        contentType: 'markdown',
      });
    }, [editor, content]);

    // ── 编辑器销毁时清理 ───
    useEffect(() => {
      return () => {
        if (editor && !editor.isDestroyed) {
          editor.destroy();
        }
      };
    }, [editor]);

    return (
      <div className={styles.editorWrapper}>
        <EditorContent editor={editor} />
      </div>
    );
  },
);

export default MarkdownRenderer;
