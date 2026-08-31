import React, {forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState} from 'react';
import {EditorContent, useEditor} from '@tiptap/react';
import {Editor} from '@tiptap/core';
import {offset} from '@floating-ui/dom';
import DragHandle from '@tiptap/extension-drag-handle-react';
import {GripVerticalIcon} from 'lucide-react';
import {TableRow} from '@tiptap/extension-table';
import {Highlight} from '@tiptap/extension-highlight';

import styles from './SimpleEditor.less';

import {
  configureMathFormula,
  ContentHelperExtension,
  MyKeyboardShortcuts,
  MyTextStyle,
  MyTranslatorExtension,
  PasteCodeBlockHandler,
  PasteStyleHandler,
  TiptapSubscript,
  TiptapSuperscript,
} from '@/components/Article/extension';

import {BubbleToolbarButton, RichTextProvider, TableHandle, useRichTextData,} from '@/components/Article/components';

import {
  codeBlock,
  configTaskList,
  selection,
  TiptapHeading,
  TipTapMarkdown,
  TiptapStarterKit,
  TiptapTable,
  TiptapTableCell,
  TiptapTableHeader,
  TiptapTextAlign,
} from '@/components/Article/extension/defaultExtensions';

import {SearchHighlight} from '@/components/Article/extension/SearchHighlight';
import DraggableLine from '@/components/DraggableLine';
import {CharacterCount} from "@tiptap/extensions";

export interface SimpleEditorProps {
  /** 编辑器默认内容 */
  defaultContent?: string;
  /** 编辑器获得焦点回调 */
  onFocus?: (editor?: Editor) => void;
  /** 编辑器失去焦点回调 */
  onBlur?: (content: string, editor?: Editor) => void;
  /** 编辑器内容变化回调 */
  onUpdate?: (content: string, editor?: Editor) => void;
  /** 编辑器销毁回调 */
  onDestroy?: (content: string) => void;
  /** 编辑器粘贴内容回调，slice 为粘贴内容，content 为粘贴后的内容 */
  onPaste?: (slice: string, content: string, editor?: Editor) => void;
  /** 编辑器删除内容回调，content 为删除前的内容 */
  onDelete?: (content: string, editor?: Editor) => void;
  /** 编辑器保存回调 */
  onSave?: () => boolean | void;
  /** 编辑器外层容器类名 */
  className?: string;
  /**
   * 编辑器受控宽度。
   * number 按 px 处理，也支持 100%、50vw 等 CSS 尺寸。
   */
  width?: number | string;
  /**
   * 编辑器受控高度。
   * number 按 px 处理，也支持 100%、50vh 等 CSS 尺寸。
   */
  height?: number | string;
  /** 拖动右侧竖线时的宽度变化回调 */
  onWidthChange?: (width: number) => void;
  /** 拖动底部横线时的高度变化回调 */
  onHeightChange?: (height: number) => void;
  /** 是否允许横向拖动，即拖动右侧竖线调整宽度 */
  horizontalResizable?: boolean;
  /** 是否允许纵向拖动，即拖动底部横线调整高度 */
  verticalResizable?: boolean;
  /** 最小宽度 */
  minWidth?: number;
  /** 最大宽度 */
  maxWidth?: number;
  /** 最小高度 */
  minHeight?: number;
  /** 最大高度 */
  maxHeight?: number;
  /** 是否显示尺寸拖动图标 */
  showResizeIcon?: boolean;
  /** 是否只读 */
  readOnly?: boolean;
  /** 是否可拖动 Tiptap 节点 */
  draggable?: boolean;
  /** 是否显示滚动条 */
  showScrollbar?: boolean;
  /** 字符数限制上限 */
  characterCountCeil?: number;
}

export interface SimpleEditorRef {
  /** 编辑器内容设置 */
  setContent: (content: string) => void;
}

interface RenderedSize {
  width: number;
  height: number;
}

/**
 * 简单编辑器组件，基于 Tiptap StarterKit 实现，默认支持 Markdown 格式。
 *
 * width 和 height 始终由父组件控制。
 * 组件内部的 renderedSize 只记录 DOM 实际尺寸，不会直接修改容器宽高。
 */
const SimpleEditor = forwardRef<SimpleEditorRef, SimpleEditorProps>(
  (props, ref) => {
    const {
      defaultContent, onFocus, onBlur, onUpdate, onDestroy, onPaste, onDelete, onSave, className,
      width = '100%', height = '100%', onWidthChange, onHeightChange,
      horizontalResizable = false, verticalResizable = false,
      minWidth = 100, maxWidth = window.innerWidth, minHeight = 100, maxHeight = window.innerHeight,
      showResizeIcon = true, readOnly = false, draggable = false, showScrollbar = true,
      characterCountCeil = 20000,
    } = props;
    const {editorRef, editButtons} = useRichTextData();
    const containerRef = useRef<HTMLDivElement>(null);
    const contentRef = useRef<string>(defaultContent || '');
    const onFocusCbRef = useRef(onFocus);
    const onBlurCbRef = useRef(onBlur);
    const onUpdateCbRef = useRef(onUpdate);
    const onDestroyCbRef = useRef(onDestroy);
    const onPasteCbRef = useRef(onPaste);
    const onDeleteCbRef = useRef(onDelete);
    const onSaveCbRef = useRef(onSave);

    const [initialed, setInitialed] = useState(false);

    /**
     * 这里只记录容器最终渲染出来的像素尺寸。
     *
     * 当 width="100%" 或 height="100%" 时，DraggableLine 仍然需要一个
     * number 类型的实际起始尺寸，因此通过 ResizeObserver 获取。
     *
     * 这个状态不会反向设置容器 style，因此不影响受控模式。
     */
    const [renderedSize, setRenderedSize] = useState<RenderedSize>({
      width: typeof width === 'number' ? width : minWidth,
      height: typeof height === 'number' ? height : minHeight,
    });

    // 每次 render 同步最新回调，避免 Tiptap 内部持有过期闭包。
    onFocusCbRef.current = onFocus;
    onBlurCbRef.current = onBlur;
    onUpdateCbRef.current = onUpdate;
    onDestroyCbRef.current = onDestroy;
    onPasteCbRef.current = onPaste;
    onDeleteCbRef.current = onDelete;
    onSaveCbRef.current = onSave;

    const extensions = useMemo(
      () => [
        TiptapStarterKit,
        TiptapHeading,
        Highlight.configure({
          multicolor: true,
        }),
        SearchHighlight,
        ContentHelperExtension,
        MyTranslatorExtension,
        TiptapTextAlign,
        TiptapTable,
        TiptapTableHeader,
        TableRow,
        TiptapTableCell,
        ...configTaskList(),
        codeBlock,
        TiptapSubscript,
        TiptapSuperscript,
        TipTapMarkdown,
        MyTextStyle,
        PasteStyleHandler,
        PasteCodeBlockHandler,
        selection,

        configureMathFormula({
          enableClickEdit: true,
        }),

        CharacterCount.configure({
          limit: characterCountCeil,
        }),

        MyKeyboardShortcuts.configure({
          save: () => {
            return onSaveCbRef.current?.() ?? false;
          },
        }),
      ],
      [],
    );

    const editor = useEditor(
      {
        extensions,
        contentType: 'markdown',
        content: '',
        editable: !readOnly,
        onFocus: ({editor}: { editor: Editor }) => {
          onFocusCbRef.current?.(editor);
        },
        onBlur: ({editor}: { editor: Editor }) => {
          const markdown = editor.getMarkdown();
          contentRef.current = markdown;
          onBlurCbRef.current?.(markdown, editor);
        },
        onUpdate: ({editor}: { editor: Editor }) => {
          const markdown = editor.getMarkdown();
          contentRef.current = markdown;
          onUpdateCbRef.current?.(markdown, editor);
        },
        onDestroy: () => {
          onDestroyCbRef.current?.(contentRef.current);
        },
      },
      [],
    );

    editorRef.current = editor;

    /**
     * 初始化默认内容。
     * defaultContent 是默认值，而不是受控 content，因此这里只初始化一次。
     */
    useEffect(() => {
      if (initialed || !editorRef.current) {
        return;
      }
      if (defaultContent) {
        editorRef.current.commands.setContent(defaultContent, {
          contentType: 'markdown',
        });
        contentRef.current = defaultContent;
      }
      setInitialed(true);
    }, [defaultContent, editorRef, initialed]);

    /**
     * readOnly 可能在编辑器创建之后发生变化，需要主动同步 editable 状态。
     */
    useEffect(() => {
      if (!editor) {
        return;
      }
      editor.setEditable(!readOnly);
    }, [editor, readOnly]);

    /**
     * 获取编辑器容器的实际像素尺寸。
     *
     * 例如：
     * width="100%" 最终可能渲染为 900px；
     * DraggableLine 拖动时以 900 为起点进行计算。
     */
    useEffect(() => {
      const container = containerRef.current;

      if (!container) {
        return;
      }

      const updateRenderedSize = () => {
        const rect = container.getBoundingClientRect();

        setRenderedSize((previous) => {
          const nextWidth =
            rect.width > 0 ? rect.width : previous.width;

          const nextHeight =
            rect.height > 0 ? rect.height : previous.height;

          if (
            previous.width === nextWidth &&
            previous.height === nextHeight
          ) {
            return previous;
          }

          return {
            width: nextWidth,
            height: nextHeight,
          };
        });
      };

      updateRenderedSize();

      const resizeObserver = new ResizeObserver(updateRenderedSize);
      resizeObserver.observe(container);

      return () => {
        resizeObserver.disconnect();
      };
    }, []);

    useImperativeHandle(ref, () => ({
      setContent: (content: string) => {
        editorRef.current.commands.setContent(content, {
          contentType: 'markdown',
        });
        contentRef.current = content;
      }
    }));

    return (
      <>
        {/* Tiptap 节点拖动手柄 */}
        {!readOnly && draggable && editor && (
          <DragHandle
            editor={editor}
            computePositionConfig={{
              middleware: [
                offset({
                  mainAxis: -4,
                  crossAxis: 0,
                }),
              ],
            }}
          >
            <GripVerticalIcon className="text-muted-foreground"/>
          </DragHandle>
        )}

        <div
          ref={containerRef}
          className={[
            styles.editorContainer,
            className,
          ]
            .filter(Boolean)
            .join(' ')}
          style={{
            width,
            height,
            position: 'relative',
            overflow: 'visible',
          }}
        >
          <EditorContent
            editor={editor}
            className={styles.editorContent}
            style={{
              scrollbarWidth: showScrollbar ? 'thin' : 'none',
            }}
          />

          {/* 右侧竖线：鼠标横向拖动，调整编辑器宽度 */}
          {horizontalResizable && (
            <DraggableLine
              direction="horizontal"
              size={renderedSize.width}
              minSize={minWidth}
              maxSize={maxWidth}
              showDragIcon={showResizeIcon}
              className={styles.widthResizeHandle}
              onSizeChange={(nextWidth) => {
                onWidthChange?.(nextWidth);
              }}
            />
          )}

          {/* 底部横线：鼠标纵向拖动，调整编辑器高度 */}
          {verticalResizable && (
            <DraggableLine
              direction="vertical"
              size={renderedSize.height}
              minSize={minHeight}
              maxSize={maxHeight}
              showDragIcon={showResizeIcon}
              className={styles.heightResizeHandle}
              onSizeChange={(nextHeight) => {
                onHeightChange?.(nextHeight);
              }}
            />
          )}
        </div>

        {/* 悬浮按钮 */}
        {!readOnly && (
          <BubbleToolbarButton
            editor={editor}
            editButtons={editButtons}
          />
        )}

        {/* 表格操作手柄 */}
        {!readOnly && <TableHandle editor={editor}/>}
      </>
    );
  });

/**
 * RichTextProvider 必须位于 SimpleEditor 外层，
 * 因为 SimpleEditor 内部调用了 useRichTextData。
 */
const SimpleEditorLayout = forwardRef<SimpleEditorRef, SimpleEditorProps>(
  (props, ref) => {
    return (
      <RichTextProvider>
        <SimpleEditor ref={ref} {...props} />
      </RichTextProvider>
    );
  });

export default SimpleEditorLayout;
