import {i18nText} from '@/utils/i18n';
import {
  BookOutlined, CommentOutlined,
  DownloadOutlined,
  EditOutlined,
  FileUnknownOutlined,
  HomeOutlined,
  LayoutOutlined,
  MediumOutlined,
  PictureOutlined,
  SaveOutlined,
  SendOutlined,
  ShareAltOutlined,
  SplitCellsOutlined,
  TableOutlined,
} from '@ant-design/icons';
import type { Editor } from '@tiptap/core';
import { EditorState } from '@tiptap/pm/state';
import { history } from '@umijs/max';
import {
  Button,
  Input,
  Modal,
  message,
  Popover,
  Select,
  Slider,
  Spin,
  Splitter,
} from 'antd';
import React, {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';
import { MyColorPicker } from '@/components';
import { useArticleInfoStore, useEditorStore } from '@/components/Article';
import {
  ArticleCoverModal,
  ArticleMetaInfo,
  DropdownToolbarButton,
  RawTextArea,
  RichTextArea,
  RichTextProvider,
  ToolbarButtonGroup,
  useRichTextData,
} from '@/components/Article/components';
import { useInsertTable } from '@/components/Article/extension/table/InsertTableModal';
import {
  canUseOperationMode,
  readButtons,
  resolveInitialOperationMode,
  writeButtons,
} from '@/components/Article/utitilies';
import { useThemeContext } from '@/contexts/ThemeContext';
import {
  getEditorArticleById,
  updateArticle,
} from '@/services/ant-design-pro/richText';
import type {
  ArticleInfoType,
  EditorMode,
  OperationMode,
  PasteStyleOptions,
  PasteStyleStorage,
  ToolbarButtonItem,
} from '@/types/rt.type';
import { exportFile } from '@/utils/fileUtil';
import styles from './RichTextEditor.less';

export interface RichTextEditorRef {
  toggleShowMetaInfo?: () => void;
  confirmBeforeArticleSwitch?: (nextArticleId: number) => Promise<boolean>;
}

// ─── 富文本编辑器属性 ───
export interface RichTextEditorProps {
  onBackHome?: () => void;
  editorHeight?: number; // 编辑器高度
  onSaved?: (success: boolean) => void; // 保存完成回调
  onShareArticle?: () => void; // 分享文章回调（工具栏按钮触发）
}

/**
 * 根据编辑模式计算左右面板尺寸
 * - 始终保持两个面板都挂载在 DOM 中，通过 size 控制显隐
 * - 'raw-text'  → 左 100% / 右 0%
 * - 'rich-text' → 左 0%   / 右 100%
 * - 'split'     → 左 50%  / 右 50%
 */
function getEditorPanelSizes(mode: EditorMode): [string, string] {
  switch (mode) {
    case 'raw-text':
      return ['100%', '0%'];
    case 'rich-text':
      return ['0%', '100%'];
    default:
      return ['50%', '50%'];
  }
}

/** 元数据信息高度 */
const META_INFO_HEIGHT = 28;
const ARTICLE_POSITION_KEY_PREFIX = 'basic-writing-position-';

function getEditorScrollRoot(editor: Editor) {
  return editor.view.dom.closest<HTMLElement>('.tiptap-editor-content');
}

function saveArticlePosition(editor: Editor, articleId: number) {
  const scrollRoot = getEditorScrollRoot(editor);
  sessionStorage.setItem(
    `${ARTICLE_POSITION_KEY_PREFIX}${articleId}`,
    JSON.stringify({
      selection: editor.state.selection.anchor,
      scrollTop: scrollRoot?.scrollTop ?? 0,
    }),
  );
}

function scrollToArticleHeading(
  editor: Editor,
  headingId: string,
  behavior: ScrollBehavior,
) {
  const heading = Array.from(
    editor.view.dom.querySelectorAll<HTMLElement>('h1, h2, h3, h4, h5, h6'),
  ).find(
    (element) => element.dataset.id === headingId || element.id === headingId,
  );
  if (!heading) return false;
  heading.scrollIntoView({ behavior, block: 'start' });
  heading.setAttribute('tabindex', '-1');
  return true;
}

function restoreArticlePosition(
  editor: Editor,
  articleId: number,
  headingId?: string,
) {
  if (headingId && scrollToArticleHeading(editor, headingId, 'auto')) return;

  const savedValue = sessionStorage.getItem(
    `${ARTICLE_POSITION_KEY_PREFIX}${articleId}`,
  );
  if (!savedValue) return;
  const savedPosition = JSON.parse(savedValue) as {
    selection: number;
    scrollTop: number;
  };
  editor.commands.setTextSelection(
    Math.min(
      Math.max(savedPosition.selection, 1),
      editor.state.doc.content.size,
    ),
  );
  const scrollRoot = getEditorScrollRoot(editor);
  if (scrollRoot) scrollRoot.scrollTop = savedPosition.scrollTop;
}

const PASTE_STYLE_KEYS: Array<
  keyof Omit<PasteStyleOptions, 'customProperties'>
> = [
  'fontFamily',
  'fontSize',
  'color',
  'backgroundColor',
  'fontWeight',
  'fontStyle',
  'textDecoration',
];

function createPasteStylePatch(values: string[]): Partial<PasteStyleOptions> {
  const selected = new Set(values);
  const patch: Partial<PasteStyleOptions> = {};
  for (const key of PASTE_STYLE_KEYS) {
    patch[key] = selected.has(key);
  }
  return patch;
}

/** 获取操作模式选项 */
function getOperationModeMeta(mode: OperationMode) {
  const meta: Record<OperationMode, { label: string; icon: React.ReactNode }> =
    {
      read: { label: i18nText("app.article.article.richtexteditor.64c78d0a"), icon: <BookOutlined /> },
      revise: { label: i18nText("app.article.article.richtexteditor.4c5df57d"), icon: <CommentOutlined /> },
      edit: { label: i18nText("app.article.article.richtexteditor.1a3b2c4c"), icon: <EditOutlined /> },
    };
  return meta[mode];
}

function applyPasteStyleOptions(
  patch: Partial<PasteStyleOptions>,
  editor?: Editor,
) {
  if (!editor) return;
  const storage = editor.storage as
    | { pasteStyleHandler?: PasteStyleStorage }
    | undefined;
  storage?.pasteStyleHandler?.setOptions(patch);
}

const EditorLayout = forwardRef<RichTextEditorRef, RichTextEditorProps>(
  ({ onBackHome, editorHeight = 600, onSaved, onShareArticle }, ref) => {
    const { editorRef, editButtons } = useRichTextData();
    const editor = useEditorStore((state) => state.editor);
    const editorStyle = useEditorStore((state) => state.editorStyle);
    const editAreaHeight = useEditorStore((state) => state.editAreaHeight);
    const setEditAreaHeight = useEditorStore(
      (state) => state.setEditAreaHeight,
    );
    const setViewSize = useEditorStore((state) => state.setViewSize);
    const setEditorStyle = useEditorStore((state) => state.setEditorStyle);
    const sizes = useEditorStore((state) => state.sizes);
    const setSizes = useEditorStore((state) => state.setSizes);
    const operationMode = useEditorStore((state) => state.operationMode);
    const setOperationMode = useEditorStore((state) => state.setOperationMode);

    const saveArticle = useArticleInfoStore((state) => state.saveArticle);
    const characterCount = useArticleInfoStore((state) => state.characterCount);
    const savingState = useArticleInfoStore((state) => state.savingState);
    const activeJumpInfo = useArticleInfoStore((state) => state.activeJumpInfo);
    const articleInfo = useArticleInfoStore((state) => state.articleInfo);
    const setArticleInfo = useArticleInfoStore((state) => state.setArticleInfo);
    const setActiveJumpInfo = useArticleInfoStore(
      (state) => state.setActiveJumpInfo,
    );
    const setCharacterCount = useArticleInfoStore(
      (state) => state.setCharacterCount,
    );
    const setRawText = useArticleInfoStore((state) => state.setRawText);
    const markRichTextEdited = useArticleInfoStore(
      (state) => state.markRichTextEdited,
    );
    const markRawTextEdited = useArticleInfoStore(
      (state) => state.markRawTextEdited,
    );
    const setSavingState = useArticleInfoStore((state) => state.setSavingState);
    const rawText = useArticleInfoStore((state) => state.rawText);
    const restoringPositionRef = useRef(false);

    const { isDark } = useThemeContext();
    // 文章封面设置弹窗
    const [coverModalVisible, setCoverModalVisible] = useState(false);
    // 当前操作模式
    const [operationModeMeta, setOperationModeMeta] = useState(
      getOperationModeMeta(operationMode),
    );
    // 编辑器显示模式
    const [editorMode, setEditorMode] = useState<EditorMode>(
      'rich-text' as EditorMode,
    );
    // 是否显示底部元数据栏
    const [showMetaInfo, setShowMetaInfo] = useState<boolean>(true);
    // 工具条高度（包括间距等）
    const toolbarHeight = 48;
    // 插入表格弹窗
    const { insertTableModal, handleOpenInsertModal } =
      useInsertTable(editorRef);
    const [articleLoading, setArticleLoading] = useState<boolean>(false);
    // 编辑器面板尺寸（由 editorMode 驱动，不重新挂载组件）
    const [leftPanelSize, rightPanelSize] = useMemo(
      () => getEditorPanelSizes(editorMode),
      [editorMode],
    );
    // 粘贴文本时需要保留的样式
    const [pasteStyleOnPaste, setPasteStyleOnPaste] = useState<string[]>(['']);

    // ─── 切换文章前确认保存 ───
    const confirmBeforeArticleSwitch = useCallback(
      async (nextArticleId: number) => {
        if (
          !articleInfo?.id ||
          nextArticleId === articleInfo.id ||
          savingState !== 4
        ) {
          return true;
        }
        const isLeavingEditor = nextArticleId < 0;
        return new Promise<boolean>((resolve) => {
          let dialog: ReturnType<typeof Modal.confirm>;
          dialog = Modal.confirm({
            title: i18nText("app.article.article.richtexteditor.ea13a210"),
            content: (
              <div>
                <div style={{ marginBottom: 12 }}>
                  {isLeavingEditor
                    ? i18nText("app.article.article.richtexteditor.c03a915d")
                    : i18nText("app.article.article.richtexteditor.a6cff621")}
                </div>
                <Button
                  danger
                  size="small"
                  onClick={() => {
                    dialog.destroy();
                    resolve(true);
                  }}
                >
                  {isLeavingEditor ? i18nText("app.article.article.richtexteditor.97c58b63") : i18nText("app.article.article.richtexteditor.c201e8db")}
                </Button>
              </div>
            ),
            okText: isLeavingEditor ? i18nText("app.article.article.richtexteditor.217f82a7") : i18nText("app.article.article.richtexteditor.ef5831a5"),
            cancelText: i18nText("app.article.article.richtexteditor.6159855b"),
            onOk: async () => {
              const ok = await saveArticle(editor, 'manual');
              onSaved?.(ok);
              if (!ok) {
                message.error(i18nText("app.article.article.richtexteditor.f6ca8ac8")).then();
                return Promise.reject();
              }
              resolve(true);
            },
            onCancel: () => resolve(false),
          });
        });
      },
      [articleInfo?.id, editor, onSaved, saveArticle, savingState],
    );

    // ─── 跳转到其他路由前确认保存 ───
    useEffect(() => {
      if (!articleInfo?.id || savingState !== 4) return;

      let disposed = false;
      let confirming = false;
      let unblock = () => {};

      const registerBlocker = () => {
        unblock = history.block((transition) => {
          // 文章切换会更新查询参数，它已在调用方完成保存确认，不属于路由离开。
          if (transition.location.pathname === history.location.pathname) {
            unblock();
            transition.retry();
            queueMicrotask(() => {
              if (!disposed) registerBlocker();
            });
            return;
          }

          // 弹窗打开期间忽略后续跳转，避免重复弹出确认框。
          if (confirming) return;
          confirming = true;
          confirmBeforeArticleSwitch(-1).then((canLeave) => {
            confirming = false;
            if (!canLeave) return;
            unblock();
            transition.retry();
          });
        });
      };

      registerBlocker();
      return () => {
        disposed = true;
        unblock();
      };
    }, [articleInfo?.id, confirmBeforeArticleSwitch, savingState]);

    // ─── 所有操作类按钮 ───
    const operationButtons = useMemo<ToolbarButtonItem[]>(() => {
      return [
        {
          key: 'editor-home',
          label: i18nText("app.article.article.richtexteditor.871b7b7d"),
          icon: <HomeOutlined />,
          order: 1,
          onClick: async () => {
            const canLeave = await confirmBeforeArticleSwitch(-1);
            if (!canLeave) return;
            if (editor && articleInfo?.id) {
              saveArticlePosition(editor, articleInfo.id);
              restoringPositionRef.current = true;
            }
            setArticleInfo(undefined);
            setActiveJumpInfo(undefined);
            editor?.commands.clearContent(true);
            onBackHome?.();
          },
        },
        {
          key: 'article-cover',
          label: articleInfo?.cover ? i18nText("app.article.article.richtexteditor.6efad4b2") : i18nText("app.article.article.richtexteditor.54070f86"),
          icon: <PictureOutlined />,
          order: 2,
          onClick: () => setCoverModalVisible(true),
        },
        {
          key: 'switch-area',
          label: i18nText("app.article.article.richtexteditor.00c51681"),
          order: 3,
          renderCustom: () => (
            <DropdownToolbarButton
              icon={<SplitCellsOutlined />}
              activeValue={editorMode}
              options={[
                { label: i18nText("app.article.article.richtexteditor.a1f72f28"), value: 'raw-text' },
                { label: i18nText("app.article.article.richtexteditor.4c0f72e8"), value: 'split' },
                { label: i18nText("app.article.article.richtexteditor.085675d0"), value: 'rich-text' },
              ]}
              onSelect={(val) => setEditorMode(val as EditorMode)}
            />
          ),
        },
        {
          key: 'switch-page-size',
          label: i18nText("app.article.article.richtexteditor.0a8ad355"),
          order: 4,
          renderCustom: () => (
            <Popover
              placement="bottomLeft"
              content={
                <div>
                  <h1>{i18nText("app.article.article.richtexteditor.0a8ad355")}</h1>
                  <Slider
                    marks={{
                      400: '400',
                      794: 'A4',
                      1123: 'A3',
                      1600: '1600',
                      2000: '2000',
                    }}
                    style={{ width: 300 }}
                    min={400}
                    max={2000}
                    onChange={(val) => {
                      setViewSize(val);
                      localStorage.setItem(
                        'richText-editor-width',
                        JSON.stringify(val),
                      );
                    }}
                  />
                </div>
              }
            >
              <Button size="small" icon={<LayoutOutlined />} />
            </Popover>
          ),
        },
        {
          key: 'bg-color',
          label: i18nText("app.article.article.richtexteditor.5df51bee"),
          order: 5,
          icon: (
            <MyColorPicker
              value={editorStyle?.backgroundColor}
              notify={(color) => {
                const tempEditorStyle = {
                  ...editorStyle,
                  backgroundColor: color,
                };
                setEditorStyle(tempEditorStyle);
                // 保存到 localStorage，不需要每次刷新时手动再次设置
                localStorage.setItem(
                  'richText-editor-style',
                  JSON.stringify(tempEditorStyle),
                );
              }}
              initialColorOptions={[
                '#FFFFFF',
                '#F2F1E8',
                '#FEF7E0',
                '#E8F0E6',
                '#DBEDFA',
                '#F3E8F0',
                '#EAE4F3',
                '#FDF2E3',
              ]}
            />
          ),
        },
        {
          key: 'markdown-text',
          label: i18nText("app.article.article.richtexteditor.02d2ccab"),
          icon: <MediumOutlined />,
          order: 6,
          onClick: () => {
            if (!editor) {
              message.error(i18nText("app.article.article.richtexteditor.d560e34c")).then();
              return;
            }
            setArticleLoading(true);
            // 异步执行，否则会被 React 18+ 自动批处理（Automatic Batching）合并，只触发一次渲染，状态从 false → false
            setTimeout(() => {
              setRawText(editor.getMarkdown() || '');
              editorMode !== 'split' && setEditorMode('split');
              setArticleLoading(false);
            }, 0);
          },
        },
        {
          key: 'save-article',
          label: i18nText("app.article.article.richtexteditor.1efcfc72"),
          icon: <SaveOutlined />,
          order: 7,
          onClick: () => {
            saveArticle(editor, 'manual').then((ok) => onSaved?.(ok));
          },
        },
        {
          key: 'text-paste',
          label: i18nText("app.article.article.richtexteditor.3e2a3e24"),
          order: 8,
          renderCustom: () => (
            <Popover
              destroyOnHidden={true}
              content={
                <Select
                  style={{ width: 150, height: 30 }}
                  autoFocus={true}
                  defaultOpen={true}
                  maxTagCount={0}
                  popupMatchSelectWidth={true}
                  size="small"
                  mode="multiple"
                  defaultValue={pasteStyleOnPaste}
                  placeholder={i18nText("app.article.article.richtexteditor.5376d252")}
                  options={[
                    { label: i18nText("app.article.article.richtexteditor.ff88c1b3"), value: 'fontFamily' },
                    { label: i18nText("app.article.article.richtexteditor.dfb05fb1"), value: 'fontSize' },
                    { label: i18nText("app.article.article.richtexteditor.753f5191"), value: 'color' },
                    { label: i18nText("app.article.article.richtexteditor.5a542f71"), value: 'backgroundColor' },
                    { label: i18nText("app.article.article.richtexteditor.063fe6cb"), value: 'fontWeight' },
                    { label: i18nText("app.article.article.richtexteditor.6dca2349"), value: 'fontStyle' },
                    { label: i18nText("app.article.article.richtexteditor.b16bb3a4"), value: 'textDecoration' },
                  ]}
                  onChange={(values: string[]) => {
                    const patch = createPasteStylePatch(values);
                    setPasteStyleOnPaste(values);
                    localStorage.setItem(
                      'paste-style-on-paste',
                      JSON.stringify(values),
                    );
                    applyPasteStyleOptions(patch, editor);
                  }}
                />
              }
            >
              <Button size="small" icon={<FileUnknownOutlined />} />
            </Popover>
          ),
        },
        {
          key: 'rich-text-convert',
          label: i18nText("app.article.article.richtexteditor.a04c4cbf"),
          icon: <SendOutlined />,
          order: 9,
          onClick: () => {
            if (!editor) {
              message.error(i18nText("app.article.article.richtexteditor.d560e34c")).then();
              return;
            }
            setArticleLoading(true);
            setTimeout(() => {
              editor.commands.setContent(rawText || '', {
                contentType: 'markdown',
              });
              editorMode !== 'split' && setEditorMode('split');
              setArticleLoading(false);
            }, 0);
          },
        },
        {
          key: 'markdown-export',
          label: i18nText("app.article.article.richtexteditor.cf9fa79d"),
          icon: <DownloadOutlined />,
          order: 10,
          onClick: () =>
            exportFile(articleInfo?.title || '', editor?.getMarkdown() || ''),
        },
        {
          key: 'share-article',
          label: i18nText("app.article.article.richtexteditor.5911ae67"),
          icon: <ShareAltOutlined />,
          order: 12,
          onClick: () => {
            if (articleInfo?.id) {
              onShareArticle?.();
            } else {
              message.info(i18nText("app.article.article.richtexteditor.90976cea")).then();
            }
          },
        },
        {
          key: 'operation-mode',
          label: operationModeMeta?.label || '',
          order: 12,
          renderCustom: () => (
            <DropdownToolbarButton
              icon={operationModeMeta?.icon || <BookOutlined />}
              label={operationModeMeta?.label || ''}
              activeValue={operationMode}
              options={[
                {
                  label: i18nText("app.article.article.richtexteditor.64c78d0a"),
                  value: 'read',
                  icon: <BookOutlined />,
                  disabled: !canUseOperationMode('read', articleInfo),
                },
                {
                  label: i18nText("app.article.article.richtexteditor.4c5df57d"),
                  value: 'revise',
                  icon: <CommentOutlined />,
                  disabled: !canUseOperationMode('revise', articleInfo),
                },
                {
                  label: i18nText("app.article.article.richtexteditor.1a3b2c4c"),
                  value: 'edit',
                  icon: <EditOutlined />,
                  disabled: !canUseOperationMode('edit', articleInfo),
                },
              ]}
              onSelect={(val) => {
                setOperationMode(val as OperationMode);
                setOperationModeMeta(
                  getOperationModeMeta(val as OperationMode),
                );
              }}
            />
          ),
        },
      ];
    }, [editor, rawText, confirmBeforeArticleSwitch, setEditorMode, onShareArticle, operationMode, articleInfo, pasteStyleOnPaste, setEditorStyle, setArticleLoading, setArticleInfo, setActiveJumpInfo, onBackHome, articleInfo?.cover, setViewSize, setRawText, saveArticle, onSaved, articleInfo?.title, operationModeMeta?.icon, setOperationMode]);

    // ─── 根据权限和阅读模式过滤操作类按钮 ───
    const filteredOperationButtons = useMemo<ToolbarButtonItem[]>(() => {
      const buttonKeys =
        articleInfo?.canWrite && operationMode === 'edit'
          ? writeButtons
          : readButtons;
      return operationButtons
        .filter((item) => buttonKeys.includes(item.key))
        .sort((a, b) => (a.order || 0) - (b.order || 0));
    }, [operationButtons, operationMode, articleInfo?.canWrite]);

    // ─── 编辑类按钮 ───
    const fullEditButtons = useMemo<ToolbarButtonItem[]>(() => {
      if (operationMode !== 'edit') return [];
      return [
        {
          key: 'insert-table',
          label: i18nText("app.article.article.richtexteditor.bae6dd75"),
          icon: <TableOutlined />,
          onClick: () => handleOpenInsertModal(),
        },
        ...editButtons,
      ];
    }, [operationMode, handleOpenInsertModal, editButtons]);

    // ─── 编辑器高度自适应 ───
    useEffect(() => {
      // 文章元数据栏高度 28px + margin-top 4px，有文章时需预留空间
      const metaHeight = articleInfo?.id ? META_INFO_HEIGHT : 0;
      setEditAreaHeight(
        editorHeight -
          toolbarHeight -
          metaHeight +
          (showMetaInfo ? 0 : META_INFO_HEIGHT) +
          13,
      );
    }, [editorHeight, articleInfo?.id]);

    useEffect(() => {
      if (!editor || editor.isDestroyed || !articleInfo?.id) return;
      const scrollRoot = getEditorScrollRoot(editor);
      if (!scrollRoot) return;
      const articleId = articleInfo.id;

      let frameId: number | undefined;
      const scheduleSave = () => {
        if (restoringPositionRef.current) return;
        if (frameId) cancelAnimationFrame(frameId);
        frameId = requestAnimationFrame(() => {
          if (!restoringPositionRef.current) {
            saveArticlePosition(editor, articleId);
          }
        });
      };

      editor.on('selectionUpdate', scheduleSave);
      scrollRoot.addEventListener('scroll', scheduleSave, { passive: true });
      return () => {
        if (frameId) cancelAnimationFrame(frameId);
        editor.off('selectionUpdate', scheduleSave);
        scrollRoot.removeEventListener('scroll', scheduleSave);
      };
    }, [articleInfo?.id, editor]);

    // ─── 从主页或侧边栏点击文章信息，跳转到编辑器页面的动作 ───
    useEffect(() => {
      console.log('文章跳转信息：', JSON.stringify(activeJumpInfo));
      if (!editor || editor.isDestroyed) return;
      if (
        !activeJumpInfo?.articleId ||
        activeJumpInfo?.articleId === articleInfo?.id
      )
        return;
      let cancelled = false;
      if (articleInfo?.id) saveArticlePosition(editor, articleInfo.id);
      restoringPositionRef.current = true;
      setArticleLoading(true);
      markRichTextEdited(undefined);
      markRawTextEdited(undefined);
      // 深度清空编辑器状态 TODO
      editor.commands?.clearContent(true);
      editor.commands?.setArticleInfo({} as ArticleInfoType);
      getEditorArticleById(activeJumpInfo.articleId)
        .then((res) => {
          if (!res || cancelled || editor.isDestroyed) return;
          const newArticle = {
            id: res.id,
            author: res.author,
            title: res.title,
            summary: res.summary,
            cover: res.cover,
            accessLevel: res.accessLevel,
            articleType: res.articleType,
            wordCount: res.wordCount,
            createBy: res.createBy,
            createTime: res.createTime,
            updateBy: res.updateBy,
            updateTime: res.updateTime,
            rowVersion: res.rowVersion,
            effectivePermission: res.effectivePermission,
            canRead: res.canRead,
            canWrite: res.canWrite,
            canDelete: res.canDelete,
            canGrant: res.canGrant,
            canCreateChild: res.canCreateChild,
          };
          setArticleInfo(newArticle);
          const newOperationMode = resolveInitialOperationMode(
            res,
            operationMode,
          );
          if (newOperationMode !== operationMode) {
            setOperationMode(newOperationMode);
            setOperationModeMeta(getOperationModeMeta(newOperationMode));
          }
          const articleContent = res.contentJson
            ? JSON.parse(res.contentJson)
            : '';
          editor.commands.setContent(articleContent);
          editor.commands.setArticleInfo(res);
          // 新文章从全新的插件状态开始，避免撤销栈等状态跨文章复用。
          editor.view.updateState(
            EditorState.create({
              schema: editor.schema,
              doc: editor.state.doc,
              plugins: editor.state.plugins,
            }),
          );
          setCharacterCount(editor.storage?.characterCount?.characters() || 0);
          setRawText(editor.getMarkdown() || '');
          // 放在最后，即使前面执行了其他操作后报错，也不会呈现不完整状态下的文章
          setArticleLoading(false);
          // setContent 会触发 onUpdate → setSavingState(4)，加载完后需要重置回 0
          setSavingState(0);
          requestAnimationFrame(() => {
            requestAnimationFrame(() => {
              if (cancelled || editor.isDestroyed) return;
              restoreArticlePosition(
                editor,
                res.id,
                activeJumpInfo?.sectionHeadingId,
              );
              restoringPositionRef.current = false;
            });
          });
        })
        .catch((err) => {
          if (cancelled) return;
          restoringPositionRef.current = false;
          console.error('加载文章失败:', err);
          message.error(i18nText("app.article.article.richtexteditor.62c3f9c2")).then();
        });
      return () => {
        cancelled = true;
      };
    }, [activeJumpInfo, editor]);

    useEffect(() => {
      const headingId = activeJumpInfo?.sectionHeadingId;
      if (
        restoringPositionRef.current ||
        !headingId ||
        !editor ||
        editor.isDestroyed ||
        activeJumpInfo.articleId !== articleInfo?.id
      ) {
        return;
      }
      const frameId = requestAnimationFrame(() => {
        scrollToArticleHeading(editor, headingId, 'smooth');
      });
      return () => cancelAnimationFrame(frameId);
    }, [activeJumpInfo, articleInfo?.id, editor]);

    // ─── 编辑器暗黑模式下的背景颜色设置 ───
    useEffect(() => {
      const newBackgroundColor = isDark ? '#1f1f1f' : '#FFFFFF';
      setEditorStyle({ ...editorStyle, backgroundColor: newBackgroundColor });
    }, [isDark, setEditorStyle]);

    // ─── 编辑器暗粘贴样式设置 ───
    useEffect(() => {
      let temp: string[] = JSON.parse(
        localStorage.getItem('paste-style-on-paste') || '[]',
      );
      if (temp.length === 0) {
        temp = [];
      }
      setPasteStyleOnPaste(temp);
      applyPasteStyleOptions(createPasteStylePatch(temp), editor);
      editorRef.current = editor;
    }, [editor]);

    // ─── 自动保存定时器 ───
    useEffect(() => {
      // 每 60 秒保存一次
      const autoSaveTimer = setInterval(() => {
        if (
          !editor ||
          editor.isDestroyed ||
          !articleInfo?.id ||
          !articleInfo?.canWrite ||
          operationMode !== 'edit'
        )
          return;
        if (savingState !== 4) return;
        if (!articleInfo) return;
        // 自动保存时，不执行 onSaved 回调
        saveArticle(editor, 'auto').then();
      }, 60 * 1000);
      return () => {
        clearInterval(autoSaveTimer);
      };
    }, [articleInfo, editor, operationMode, saveArticle, savingState]);

    useImperativeHandle(
      ref,
      () => ({
        confirmBeforeArticleSwitch,
        toggleShowMetaInfo: () => {
          if (showMetaInfo) {
            setShowMetaInfo(false);
            setEditAreaHeight(editAreaHeight + META_INFO_HEIGHT);
          } else {
            setShowMetaInfo(true);
            setEditAreaHeight(editAreaHeight - META_INFO_HEIGHT);
          }
        },
      }),
      [
        confirmBeforeArticleSwitch,
        showMetaInfo,
        editAreaHeight,
        setEditAreaHeight,
      ],
    );

    return (
      <div
        className={styles.editorLayout}
        style={{ height: editorHeight, width: '100%' }}
      >
        <div className={styles.mainContent}>
          {/*
           * ── 工具条：三栏 Splitter ──
           *   Panel-1（30%）：操作类按钮，左对齐
           *   Panel-2（40%）：文章标题输入框，居中
           *   Panel-3（30%）：编辑类按钮，右对齐
           */}
          <div className={`${styles.toolbar} bg-gray-100 dark:bg-[#303030]`}>
            <Splitter style={{ height: toolbarHeight, width: '100%' }}>
              {/* Panel-1：操作类按钮（30%） */}
              <Splitter.Panel
                defaultSize="30%"
                min="10%"
                max="50%"
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  paddingInline: 4,
                  overflow: 'hidden',
                }}
              >
                <ToolbarButtonGroup
                  buttons={filteredOperationButtons}
                  justify="flex-start"
                  buttonBarType="fix"
                />
              </Splitter.Panel>

              {/* Panel-2：文章标题（40%） */}
              <Splitter.Panel
                defaultSize="40%"
                min="20%"
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  paddingInline: 8,
                  overflow: 'hidden',
                }}
              >
                <Input
                  placeholder={i18nText("app.article.article.richtexteditor.9f25c632")}
                  disabled={operationMode !== 'edit'}
                  variant="borderless"
                  style={{
                    textAlign: 'center',
                    fontWeight: 600,
                    fontSize: 26,
                    width: '100%',
                  }}
                  value={articleInfo?.title ?? ''}
                  onChange={(e) => {
                    if (articleInfo?.id) {
                      setArticleInfo({
                        ...articleInfo,
                        title: e.target.value,
                      });
                    }
                  }}
                  onBlur={() => {
                    const title = articleInfo?.title?.trim();
                    if (articleInfo?.id && title) {
                      if (title !== articleInfo.title) {
                        setArticleInfo({ ...articleInfo, title });
                      }
                      updateArticle({
                        id: articleInfo.id,
                        title,
                      }).then();
                    }
                  }}
                />
              </Splitter.Panel>

              {/* Panel-3：编辑类按钮（30%），按钮靠右 */}
              <Splitter.Panel
                min="20%"
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  paddingInline: 4,
                  overflow: 'hidden',
                }}
              >
                <ToolbarButtonGroup
                  buttons={fullEditButtons}
                  justify="flex-end"
                  buttonBarType="fix"
                />
              </Splitter.Panel>
            </Splitter>
          </div>

          {/* ── 编辑区域 ── */}
          {/*
           * 始终同时渲染 RawTextArea 和 RichTextArea，
           * 仅通过 Splitter.Panel 的 size 在 0% / 50% / 100% 之间切换，
           * 从而避免切换模式时组件卸载重建导致内容丢失。
           */}
          <div className={styles.editorContainer}>
            {articleLoading && (
              <div className={styles.articleLoadingMask}>
                <Spin description={i18nText("app.article.article.richtexteditor.9f483e3b")} size="large" />
              </div>
            )}
            <Splitter className={styles.customSplitter}>
              <Splitter.Panel
                size={leftPanelSize}
                min="0%"
                style={{
                  // 完全折叠时隐藏溢出内容，防止影响布局
                  overflow: leftPanelSize === '0%' ? 'hidden' : undefined,
                }}
              >
                <RawTextArea />
              </Splitter.Panel>
              <Splitter.Panel
                size={rightPanelSize}
                min="0%"
                style={{
                  overflow: rightPanelSize === '0%' ? 'hidden' : undefined,
                }}
              >
                <RichTextArea
                  visible={editorMode === 'rich-text'}
                  onSave={() => {
                    const currentEditor = useEditorStore.getState().editor;
                    saveArticle(currentEditor, 'manual').then((result) =>
                      onSaved?.(result),
                    );
                  }}
                  editButtons={editButtons}
                />
              </Splitter.Panel>
            </Splitter>
          </div>

          {/* ── 文章元数据 ── */}
          {showMetaInfo && articleInfo?.id && (
            <ArticleMetaInfo
              createBy={articleInfo?.createBy}
              createTime={articleInfo?.createTime}
              updateBy={articleInfo?.updateBy}
              updateTime={articleInfo?.updateTime}
              effectivePermission={articleInfo?.effectivePermission}
              savingState={savingState}
              characterCount={characterCount}
              onClose={() => {
                setShowMetaInfo(false);
                setEditAreaHeight(editAreaHeight + META_INFO_HEIGHT);
              }}
            />
          )}
        </div>

        {/* 插入表格弹窗 */}
        {insertTableModal}

        {/* 修改文章封面弹窗 */}
        <ArticleCoverModal
          articleInfo={articleInfo}
          visible={coverModalVisible}
          onClose={() => setCoverModalVisible(false)}
          onSuccess={(cover) => setArticleInfo({ ...articleInfo, cover })}
        />
      </div>
    );
  },
);

const RichTextEditor = forwardRef<RichTextEditorRef, RichTextEditorProps>(
  (props, ref) => {
    return (
      <RichTextProvider>
        <EditorLayout {...props} ref={ref} />
      </RichTextProvider>
    );
  },
);

export default RichTextEditor;
