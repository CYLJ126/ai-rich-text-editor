import {i18nText} from '@/utils/i18n';
import {
  AlignCenterOutlined,
  BgColorsOutlined,
  BoldOutlined,
  FontColorsOutlined,
  FontSizeOutlined,
  FormatPainterOutlined,
  ItalicOutlined,
  LineHeightOutlined,
  NodeExpandOutlined,
  StrikethroughOutlined,
  ToolOutlined,
  TranslationOutlined,
  UnderlineOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import type {Editor, JSONContent} from '@tiptap/core';
import {message} from 'antd';
import React, {createContext, type ReactNode, useContext, useEffect, useMemo, useRef, useState,} from 'react';
import {MyColorPicker} from '@/components';
import {DropdownToolbarButton} from '@/components/Article/components';
import {toggleLink} from '@/components/Article/extension/MyLink';
import {FONT_FAMILY_OPTIONS, FONT_SIZE_OPTIONS, LINE_HEIGHT_OPTIONS, type ToolbarButtonItem,} from '@/types/rt.type';
import {type CustomProperty, TextProcessor} from '@/utils/textProcessor';

// ─── useContext 属性 ───
export interface RichTextContextType {
  editorRef: React.RefObject<any>; // 编辑器实例引用
  editButtons: ToolbarButtonItem[]; // 编辑按钮
}

const formatProperty: CustomProperty = {
  zhOrEn: true,
  punctuationMark: true,
  clearBreakLine: false,
  compressSpace: true,
  withSpace: true,
  pasteFromClipboard: false,
  rewriteClipboard: false,
  isHandleClipboard: false,
  handleList: false,
  handleMarkdownTable: false,
};

const RichTextContext = createContext<RichTextContextType | undefined>(
  undefined,
);

export function RichTextProvider({ children }: { children: ReactNode }) {
  const editorRef = useRef<Editor>(null as unknown as Editor);
  const [fontColor, setFontColor] = useState<string | undefined>(
    localStorage.getItem('editor-font-color') || '#ce2416',
  );
  const [backgroundColor, setBackgroundColor] = useState<string | undefined>(
    localStorage.getItem('editor-background-color') || '#f6c114',
  );
  const [currentFontSize, setCurrentFontSize] = useState<string | undefined>();
  const [currentFontFamily, setCurrentFontFamily] = useState<
    string | undefined
  >();
  const [currentLineHeight, setCurrentLineHeight] = useState<
    string | undefined
  >();

  useEffect(() => {
    if (!editorRef.current || editorRef.current.isDestroyed) return;
    // 监听编辑器选区变化，同步字体属性状态
    const syncAttrs = () => {
      const attrs = editorRef.current?.getAttributes('textStyle');
      // 使用 setState 更新，触发重渲染使 DropdownToolbarButton activeValue 生效
      setCurrentFontSize(attrs?.fontSize || null);
      setCurrentFontFamily(attrs?.fontFamily || null);
      setCurrentLineHeight(attrs?.lineHeight || null);
    };
    editorRef.current?.on('selectionUpdate', syncAttrs);
    editorRef.current?.on('transaction', syncAttrs);
    return () => {
      editorRef.current?.off('selectionUpdate', syncAttrs);
      editorRef.current?.off('transaction', syncAttrs);
    };
  }, [editorRef.current]);

  const editButtons = useMemo<ToolbarButtonItem[]>(
    () => [
      {
        key: 'text-bold',
        label: i18nText("app.article.editor.richtextcontext.c9c3dc07"),
        icon: <BoldOutlined />,
        onClick: () => editorRef.current?.chain().focus().toggleBold().run(),
      },
      {
        key: 'text-italic',
        label: i18nText("app.article.editor.richtextcontext.6334cde6"),
        icon: <ItalicOutlined />,
        onClick: () => editorRef.current?.chain().focus().toggleItalic().run(),
      },
      {
        key: 'text-underline',
        label: i18nText("app.article.editor.richtextcontext.b060e21d"),
        icon: <UnderlineOutlined />,
        onClick: () =>
          editorRef.current?.chain().focus().toggleUnderline().run(),
      },
      {
        key: 'text-strike-through',
        label: i18nText("app.article.editor.richtextcontext.89920612"),
        icon: <StrikethroughOutlined />,
        onClick: () => editorRef.current?.chain().focus().toggleStrike().run(),
      },
      {
        key: 'text-color',
        label: i18nText("app.article.editor.richtextcontext.d7355ca6"),
        icon: <FontColorsOutlined />,
        children: (
          // 使用 setFontColor 替换 ref 赋值
          <MyColorPicker
            value={fontColor}
            notify={(color) => {
              setFontColor(color);
              localStorage.setItem('editor-font-color', color);
            }}
          />
        ),
        onClick: () =>
          editorRef.current
            ?.chain()
            .focus()
            .toggleFontColor(fontColor ?? '#FF0000')
            .run(),
      },
      {
        key: 'text-background-color',
        label: i18nText("app.article.editor.richtextcontext.4383f4c2"),
        icon: <BgColorsOutlined />,
        children: (
          // 使用 setBackgroundColor 替换 ref 赋值
          <MyColorPicker
            value={backgroundColor}
            notify={(color) => {
              setBackgroundColor(color);
              localStorage.setItem('editor-background-color', color);
            }}
          />
        ),
        onClick: () =>
          editorRef.current
            ?.chain()
            .focus()
            .toggleBackgroundColor(backgroundColor ?? '#ffc078')
            .run(),
      },
      {
        key: 'text-align',
        label: i18nText("app.article.editor.richtextcontext.0a5f9390"),
        renderCustom: () => (
          <DropdownToolbarButton
            icon={<AlignCenterOutlined />}
            options={[
              { label: i18nText("app.article.editor.richtextcontext.d0891109"), value: 'left' },
              { label: i18nText("app.article.editor.richtextcontext.e9a097b6"), value: 'center' },
              { label: i18nText("app.article.editor.richtextcontext.e2f3554d"), value: 'right' },
            ]}
            onSelect={(val) =>
              (editorRef.current?.chain().focus() as any)
                ?.setTextAlign(val)
                ?.run()
            }
          />
        ),
      },
      {
        key: 'text-list',
        label: i18nText("app.article.editor.richtextcontext.158fc602"),
        renderCustom: () => (
          <DropdownToolbarButton
            icon={<UnorderedListOutlined />}
            options={[
              { label: i18nText("app.article.editor.richtextcontext.ec9f8eb3"), value: 'ordered' },
              { label: i18nText("app.article.editor.richtextcontext.5463c81d"), value: 'unordered' },
              { label: i18nText("app.article.editor.richtextcontext.e2579ac7"), value: 'toggleTaskList' },
            ]}
            onSelect={(val) => {
              switch (val) {
                case 'ordered':
                  editorRef.current?.chain().focus().toggleOrderedList().run();
                  return;
                case 'unordered':
                  editorRef.current?.chain().focus().toggleBulletList().run();
                  return;
                case 'toggleTaskList':
                  editorRef.current?.chain().focus().toggleTaskList().run();
                  return;
                default:
                  break;
              }
            }}
          />
        ),
      },
      {
        key: 'text-format',
        label: i18nText("app.article.editor.richtextcontext.7049b9d2"),
        icon: <FormatPainterOutlined />,
        onClick: () => {
          const rawSelection =
            editorRef.current?.commands?.getSelectionInfo() as any;
          if (!rawSelection?.hasSelection) return;
          const contentLevel = editorRef.current?.commands?.getContentLevel();
          const formattedText = TextProcessor.handleChinese(
            formatProperty,
            rawSelection.text,
          );
          if (contentLevel === 'inline') {
            // 一段内容
            editorRef.current?.commands?.replaceSelectionInline([
              { type: 'text', text: formattedText } as JSONContent,
            ]);
          } else {
            // 多段内容：深度递归格式化所有 text 节点
            function formatNode(node: any): any {
              if (node.type === 'text' && typeof node.text === 'string') {
                // 格式化后如果文本为空，返回 null 标记为需要过滤
                const formatted = TextProcessor.handleChinese(
                  formatProperty,
                  node.text,
                );
                if (formatted === '') return null;
                return {
                  ...node,
                  text: formatted,
                };
              }
              if (node.content && Array.isArray(node.content)) {
                return {
                  ...node,
                  // 过滤掉 null（即空文本节点）
                  content: node.content
                    .map((child: any) => formatNode(child))
                    .filter((child: any) => child !== null),
                };
              }
              return node;
            }

            // 只取干净的 doc 结构，避免多余字段干扰 nodeFromJSON
            const cleanDoc: JSONContent = {
              type: 'doc',
              content: (rawSelection.content?.content ?? []).map((para: any) =>
                formatNode(para),
              ),
            };
            editorRef.current?.commands?.replaceSelectionBlockMulti(cleanDoc);
          }
          // 使选中区域保持不变，不加这行代码需要点一下才会恢复原选择内容，加这行代码在格式化后会自动跳一下，恢复成原选择内容
          editorRef.current?.chain().focus('end');
        },
      },
      {
        key: 'text-type',
        label: i18nText("app.article.editor.richtextcontext.425156a9"),
        renderCustom: () => (
          <DropdownToolbarButton
            icon={<NodeExpandOutlined />}
            options={[
              { label: i18nText("app.article.editor.richtextcontext.bf7c3465"), value: 'text-code' },
              { label: i18nText("app.article.editor.richtextcontext.f37c2f04"), value: 'text-link' },
              { label: i18nText("app.article.editor.richtextcontext.6d52f7d4"), value: 'text-superscript' },
              { label: i18nText("app.article.editor.richtextcontext.61000ae1"), value: 'text-subscript' },
              { label: i18nText("app.article.editor.richtextcontext.971e0f7b"), value: 'text-math' },
              { label: i18nText("app.article.editor.richtextcontext.f5375a63"), value: 'quote' },
            ]}
            onSelect={(val) => {
              if (!editorRef.current) return;
              switch (val) {
                case 'text-code':
                  editorRef.current?.chain().focus().toggleCode().run();
                  return;
                case 'text-link':
                  toggleLink(editorRef.current);
                  return;
                case 'text-superscript':
                  editorRef.current?.chain().focus().toggleSuperscript().run();
                  return;
                case 'text-subscript':
                  editorRef.current?.chain().focus().toggleSubscript().run();
                  return;
                case 'text-math':
                  editorRef.current?.chain().focus().toggleMathFormula().run();
                  return;
                case 'quote':
                  editorRef.current?.chain().focus().toggleBlockquote().run();
                  return;
                default:
                  break;
              }
            }}
          />
        ),
      },
      {
        key: 'text-translate',
        label: i18nText("app.article.editor.richtextcontext.b66ccf99"),
        renderCustom: () => (
          <DropdownToolbarButton
            icon={<TranslationOutlined />}
            options={[
              { label: i18nText("app.article.editor.richtextcontext.e63a45fc"), value: 'translateSelection' },
              {
                label: i18nText("app.article.editor.richtextcontext.8d0fb29e"),
                value: 'translateAndReplaceSelection',
              },
              {
                label: i18nText("app.article.editor.richtextcontext.eb30ca2a"),
                value: 'translateSelectionAndInsertAfter',
              },
              { label: i18nText("app.article.editor.richtextcontext.6ee668d6"), value: 'translateParagraphs' },
              {
                label: i18nText("app.article.editor.richtextcontext.b9bfc9bc"),
                value: 'translateAndReplaceParagraphs',
              },
              { label: i18nText("app.article.editor.richtextcontext.386caa53"), value: 'translateAndInsertAsQuote' },
            ]}
            onSelect={(val) => {
              switch (val) {
                case 'translateSelection':
                  editorRef.current?.chain().focus().translateSelection().run();
                  return;
                case 'translateAndReplaceSelection':
                  editorRef.current
                    ?.chain()
                    .focus()
                    .translateAndReplaceSelection()
                    .run();
                  return;
                case 'translateSelectionAndInsertAfter':
                  editorRef.current
                    ?.chain()
                    .focus()
                    .translateSelectionAndInsertAfter()
                    .run();
                  return;
                case 'translateParagraphs':
                  editorRef.current
                    ?.chain()
                    .focus()
                    .translateCurrentBlock()
                    .run();
                  return;
                case 'translateAndReplaceParagraphs':
                  editorRef.current
                    ?.chain()
                    .focus()
                    .translateAndReplaceBlock()
                    .run();
                  return;
                case 'translateAndInsertAsQuote':
                  editorRef.current
                    ?.chain()
                    .focus()
                    .translateAndInsertAsQuote()
                    .run();
                  return;
              }
              (editorRef.current?.chain().focus() as any)
                ?.translateSelectionAndInsertAfter()
                .run();
            }}
          />
        ),
      },
      {
        key: 'text-tools',
        label: i18nText("app.article.editor.richtextcontext.26ce764b"),
        renderCustom: () => (
          <DropdownToolbarButton
            icon={<ToolOutlined />}
            options={[{ label: i18nText("app.article.editor.richtextcontext.904d0278"), value: 'interpret' }]}
            onSelect={(val) => {
              switch (val) {
                case 'interpret':
                  message.info(i18nText("app.article.editor.richtextcontext.d70cb7fd")).then();
                  return;
                default:
                  break;
              }
            }}
          />
        ),
      },
      {
        key: 'text-font-size',
        label: i18nText("app.article.editor.richtextcontext.6e52f611"),
        renderCustom: () => (
          <DropdownToolbarButton
            icon={<FontSizeOutlined />}
            options={FONT_SIZE_OPTIONS}
            activeValue={currentFontSize}
            onSelect={(val) => {
              setCurrentFontSize(val);
              (editorRef.current?.chain().focus() as any)
                ?.toggleFontSize(val)
                ?.run();
            }}
          />
        ),
      },
      {
        key: 'text-font-family',
        label: i18nText("app.article.editor.richtextcontext.5f50ffc6"),
        renderCustom: () => (
          <DropdownToolbarButton
            icon={<span>{i18nText("app.article.editor.richtextcontext.75b177fb")}</span>}
            options={FONT_FAMILY_OPTIONS}
            activeValue={currentFontFamily}
            onSelect={(val) => {
              setCurrentFontFamily(val);
              (editorRef.current?.chain().focus() as any)
                ?.toggleFontFamily(val)
                ?.run();
            }}
          />
        ),
      },
      {
        key: 'text-line-height',
        label: i18nText("app.article.editor.richtextcontext.b267a805"),
        renderCustom: () => (
          <DropdownToolbarButton
            icon={<LineHeightOutlined />}
            options={LINE_HEIGHT_OPTIONS}
            activeValue={currentLineHeight}
            onSelect={(val) => {
              setCurrentLineHeight(val);
              (editorRef.current?.chain().focus() as any)
                ?.toggleLineHeight(val)
                ?.run();
            }}
          />
        ),
      },
    ],
    [
      fontColor,
      backgroundColor,
      currentFontSize,
      currentFontFamily,
      currentLineHeight,
    ],
  );

  const value = useMemo(() => {
    return {
      editorRef,
      editButtons,
    };
  }, [editButtons, editorRef]);

  return (
    <RichTextContext.Provider value={value}>
      {children}
    </RichTextContext.Provider>
  );
}

// 自定义 hook 方便使用
export function useRichTextData() {
  const context = useContext(RichTextContext);
  if (!context) {
    throw new Error('useRichTextData must be used within a RichTextProvider');
  }
  return context;
}
