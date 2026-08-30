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
        label: '文本加粗【Mod-B】',
        icon: <BoldOutlined />,
        onClick: () => editorRef.current?.chain().focus().toggleBold().run(),
      },
      {
        key: 'text-italic',
        label: '文本斜体【Mod-I】',
        icon: <ItalicOutlined />,
        onClick: () => editorRef.current?.chain().focus().toggleItalic().run(),
      },
      {
        key: 'text-underline',
        label: '文本下划线【Mod-U】',
        icon: <UnderlineOutlined />,
        onClick: () =>
          editorRef.current?.chain().focus().toggleUnderline().run(),
      },
      {
        key: 'text-strike-through',
        label: '文本删除线【Mod-Shift-S】',
        icon: <StrikethroughOutlined />,
        onClick: () => editorRef.current?.chain().focus().toggleStrike().run(),
      },
      {
        key: 'text-color',
        label: '文本颜色',
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
        label: '文本背景色',
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
        label: '文本对齐',
        renderCustom: () => (
          <DropdownToolbarButton
            icon={<AlignCenterOutlined />}
            options={[
              { label: '左对齐【Mod-Shift-L】', value: 'left' },
              { label: '居中对齐【Mod-Shift-E】', value: 'center' },
              { label: '右对齐【Mod-Shift-R】', value: 'right' },
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
        label: '文本列表',
        renderCustom: () => (
          <DropdownToolbarButton
            icon={<UnorderedListOutlined />}
            options={[
              { label: '有序列表【Mod-Shift-7】', value: 'ordered' },
              { label: '无序列表【Mod-Shift-8】', value: 'unordered' },
              { label: '任务列表【Mod-Shift-9】', value: 'toggleTaskList' },
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
        label: '文本格式化',
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
        label: '其他文本',
        renderCustom: () => (
          <DropdownToolbarButton
            icon={<NodeExpandOutlined />}
            options={[
              { label: '文本代码【Mod-E】', value: 'text-code' },
              { label: '文本链接【Mod-K】', value: 'text-link' },
              { label: '文本上标【Mod-.】', value: 'text-superscript' },
              { label: '文本下标【Mod-,】', value: 'text-subscript' },
              { label: '数学公式【Mod-M】', value: 'text-math' },
              { label: '文本引用【Mod-Shift-B】', value: 'quote' },
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
        label: '文本翻译',
        renderCustom: () => (
          <DropdownToolbarButton
            icon={<TranslationOutlined />}
            options={[
              { label: '翻译选中内容', value: 'translateSelection' },
              {
                label: '翻译并替换选中内容',
                value: 'translateAndReplaceSelection',
              },
              {
                label: '翻译并插入选中内容',
                value: 'translateSelectionAndInsertAfter',
              },
              { label: '翻译本段', value: 'translateParagraphs' },
              {
                label: '翻译并替换本段',
                value: 'translateAndReplaceParagraphs',
              },
              { label: '翻译并插入引用', value: 'translateAndInsertAsQuote' },
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
        label: '文本工具',
        renderCustom: () => (
          <DropdownToolbarButton
            icon={<ToolOutlined />}
            options={[{ label: '解读', value: 'interpret' }]}
            onSelect={(val) => {
              switch (val) {
                case 'interpret':
                  message.info('解读功能暂未实现').then();
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
        label: '字体大小',
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
        label: '字体族',
        renderCustom: () => (
          <DropdownToolbarButton
            icon={<span>字</span>}
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
        label: '行高',
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
