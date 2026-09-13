import {i18nText} from '@/utils/i18n';
import UniqueID from '@tiptap/extension-unique-id';
import type { Editor } from '@tiptap/core';
import {EditorContent, useEditor} from '@tiptap/react';
import dayjs from 'dayjs';
import React, {useCallback, useEffect, useMemo, useRef} from 'react';
import {configAiExtensions, defaultExtensions, useArticleInfoStore, useEditorStore,} from '@/components/Article';
import {BubbleToolbarButton, TableHandle,} from '@/components/Article/components';
import {
  CommentsExtension,
  type CommentsProvider,
  FloatingResultExtension,
  FloatingResultView,
  getSuggestions,
  MathFormulaView,
  MyArticleInfoRef,
  MyKeyboardShortcuts,
  SlashCommand,
  TiptapAudio,
  TiptapVideo,
  TiptapYoutube,
} from '@/components/Article/extension';
import type {ArticleInfoType, ToolbarButtonItem} from '@/types/rt.type';
import styles from './RichTextArea.less';
import SearchReplaceBar from './SearchReplaceBar';

// 富文本编辑器组件属性
export interface RichTextAreaProps {
  onSave?: () => void; // 内部保存调用父组件的保存逻辑，如 Ctrl + S
  visible?: boolean; // 是否可见
  syncRawText?: boolean; // 分栏模式下同步 Markdown 源码
  editButtons: ToolbarButtonItem[]; // 编辑按钮
}

// 富文本编辑器组件
const RichTextArea: React.FC<RichTextAreaProps> = ({
  onSave,
  visible = true,
  syncRawText = false,
  editButtons,
}) => {
  const viewSize = useEditorStore((state) => state.viewSize);
  const editorStyle = useEditorStore((state) => state.editorStyle);
  const editAreaHeight = useEditorStore((state) => state.editAreaHeight);
  const setEditor = useEditorStore((state) => state.setEditor);
  const setViewSize = useEditorStore((state) => state.setViewSize);
  const setEditorStyle = useEditorStore((state) => state.setEditorStyle);
  const operationMode = useEditorStore((state) => state.operationMode);
  const floatingState = useEditorStore((state) => state.floatingState);
  const setFloatingState = useEditorStore((state) => state.setFloatingState);

  const rawText = useArticleInfoStore.getState().rawText;
  const setRawText = useArticleInfoStore((state) => state.setRawText);
  const setCharacterCount = useArticleInfoStore(
    (state) => state.setCharacterCount,
  );
  const setSavingState = useArticleInfoStore((state) => state.setSavingState);
  const markRichTextEdited = useArticleInfoStore(
    (state) => state.markRichTextEdited,
  );
  const articleInfo = useArticleInfoStore((state) => state.articleInfo);
  const commentsProvider = useArticleInfoStore(
    (state) => state.commentsProvider,
  );

  const articleInfoRef = useRef<ArticleInfoType | undefined>(undefined);
  const countTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const rawSyncTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const syncRawTextRef = useRef(syncRawText);
  syncRawTextRef.current = syncRawText;

  // 编辑器内容更新回调
  const handleUpdate = ({ editor }: { editor: Editor }) => {
    if (!countTimerRef.current) {
      countTimerRef.current = setTimeout(() => {
        countTimerRef.current = null;
        if (!editor.isDestroyed) {
          setCharacterCount(editor.storage.characterCount.characters());
        }
      }, 250);
    }
    markRichTextEdited(dayjs());
    if (rawSyncTimerRef.current) clearTimeout(rawSyncTimerRef.current);
    if (syncRawTextRef.current) {
      rawSyncTimerRef.current = setTimeout(() => {
        rawSyncTimerRef.current = null;
        if (editor.isDestroyed) return;
        const { lastRichTextEditTime, lastRawTextEditTime } =
          useArticleInfoStore.getState();
        if (
          lastRichTextEditTime &&
          (!lastRawTextEditTime || lastRichTextEditTime.isAfter(lastRawTextEditTime))
        ) {
          setRawText(editor.getMarkdown());
        }
      }, 400);
    }
    if (operationMode === 'edit') {
      setSavingState(4);
    }
  };

  useEffect(() => () => {
    if (countTimerRef.current) clearTimeout(countTimerRef.current);
    if (rawSyncTimerRef.current) clearTimeout(rawSyncTimerRef.current);
  }, []);

  useEffect(() => {
    if (!syncRawText && rawSyncTimerRef.current) {
      clearTimeout(rawSyncTimerRef.current);
      rawSyncTimerRef.current = null;
    }
  }, [syncRawText]);

  // 配置编辑器扩展
  const assembleExtensions = useCallback(
    () => [
      ...defaultExtensions,
      UniqueID.configure({
        // 只能处理完整的节点，比如在流式输出时，节点内容不完整，会报错，故只在需要的地方添加
        types: 'all',
      }),
      // 注册 MyArticleInfoRef，将外部 ref 传入
      MyArticleInfoRef.configure({
        articleInfoRef,
      }),
      MyKeyboardShortcuts.configure({
        save: () => {
          return onSave?.();
        },
      }),
      SlashCommand.configure({
        suggestion: getSuggestions(),
      }),
      ...configAiExtensions,
      FloatingResultExtension.configure({
        onStateChange: setFloatingState,
      }),
      CommentsExtension.configure({
        provider: commentsProvider as CommentsProvider,
      }),
      TiptapYoutube,
      TiptapAudio,
      TiptapVideo,
    ],
    [onSave],
  );

  const editor: Editor | null = useEditor({
    // 在 React 提交后创建实例，避免预渲染/登录跳转丢弃的 render 留下已销毁实例。
    immediatelyRender: false,
    shouldRerenderOnTransaction: false,
    content: rawText,
    contentType: 'markdown',
    extensions: assembleExtensions(),
    onUpdate: handleUpdate,
    onDestroy: () => {
      // Tiptap 随后会把 commandManager 置空；先同步移除全局引用，
      // 避免侧栏 effect 在销毁窗口内继续读取 editor.commands。
      if (useEditorStore.getState().editor === editor) {
        setEditor(undefined);
      }
    },
    onCreate: ({ editor }: any) => {
      console.log('编辑器创建成功，支持的命令：', Object.keys(editor.commands));
      console.log(
        '编辑器创建成功，支持的扩展：',
        Object.keys(editor.extensionStorage),
      );
    },
  } as any);

  useEffect(() => {
    console.log('初始化编辑器');
    setEditor(editor ?? undefined);
    // 设置宽度
    const viewSize = localStorage.getItem('richText-editor-width');
    viewSize && setViewSize(Number(viewSize));
    // 设置背景色
    const editorStyle = localStorage.getItem('richText-editor-style');
    if (editorStyle) {
      setEditorStyle(JSON.parse(editorStyle));
    }
    return () => {
      // useEditor 会负责销毁实例。这里只清理发布到全局 store 的引用，
      // 避免重复 destroy 后 React/Tiptap 的卸载流程继续读取 commands。
      if (useEditorStore.getState().editor === editor) {
        setEditor(undefined);
      }
    };
  }, [editor]);

  useEffect(() => {
    if (!editor || editor.isDestroyed) return;
    editor.setEditable(operationMode === 'edit');
  }, [operationMode, editor]);

  const bubbleEditButtons = useMemo<ToolbarButtonItem[]>(
    () =>
      operationMode === 'edit'
        ? editButtons
        : editButtons.filter((button) => button.key === 'add-comment'),
    [editButtons, operationMode],
  );

  useEffect(() => {
    if (!articleInfo) return;
    // 文章信息变化时，更新 ref，使在编辑器中能获取到最新的文章信息
    articleInfoRef.current = articleInfo;
    const timer = setTimeout(() => {
      if (!editor || editor.isDestroyed) return;
      editor.commands.setArticleInfo(articleInfo);
    }, 0);
    return () => clearTimeout(timer);
  }, [articleInfo, editor]);

  /**
   * 在容器层处理编辑器快捷键：
   * 1. 拦截 Ctrl+S / Cmd+S，实际保存逻辑由 TipTap 处理
   * 2. 在编辑器正文中按 Tab 时插入制表符，避免焦点跳到下一个元素
   */
  const handleKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 's') {
      e.preventDefault(); // 阻止浏览器默认的保存网页行为
    }

    if (
      e.key === 'Tab' &&
      !e.defaultPrevented &&
      !e.shiftKey &&
      !e.ctrlKey &&
      !e.metaKey &&
      !e.altKey &&
      operationMode === 'edit' &&
      editor?.isFocused
    ) {
      e.preventDefault();
      editor.view.dispatch(editor.state.tr.insertText('\t').scrollIntoView());
    }
  };

  return (
    <div
      className={styles.richTextArea}
      style={{
        height: editAreaHeight,
        display: 'flex',
        justifyContent: 'center',
      }}
      onKeyDown={handleKeyDown}
    >
      {editor ? (
        <div
          className="tiptap-editor-content"
          style={{
            backgroundColor: editorStyle.backgroundColor,
            width: viewSize === 0 ? '100%' : viewSize,
          }}
        >
          <EditorContent
            editor={editor}
            className={`prose dark:prose-invert max-w-full z-0 px-6`}
          />
          <SearchReplaceBar
            editor={editor}
            enabled={visible}
            canReplace={operationMode === 'edit'}
          />
          <MathFormulaView />
          {operationMode === 'edit' && <TableHandle editor={editor} />}
          {/* 悬浮按钮 */}
          {(operationMode === 'edit' || operationMode === 'revise') && (
            <BubbleToolbarButton
              editor={editor}
              editButtons={bubbleEditButtons}
              allowReadOnly={operationMode === 'revise'}
            />
          )}
          {/* 悬浮结果框 */}
          {floatingState.visible && floatingState.position && (
            <FloatingResultView
              content={floatingState.content}
              position={floatingState.position}
              label={i18nText("app.article.editor.richtextarea.ab9dbf52")}
              onClose={() => {
                if (!editor.isDestroyed) editor.commands.hideFloatingResult();
              }}
            />
          )}
        </div>
      ) : (
        <div>{i18nText("app.article.editor.richtextarea.ab02172a")}</div>
      )}
    </div>
  );
};

export default RichTextArea;
