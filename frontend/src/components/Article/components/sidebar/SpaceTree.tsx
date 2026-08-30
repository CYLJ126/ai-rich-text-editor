import {i18nText} from '@/utils/i18n';
import {
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  FileAddOutlined,
  FileZipOutlined,
  FolderAddOutlined,
  FolderTwoTone,
  GlobalOutlined,
  ShareAltOutlined,
  StopOutlined,
  SwapOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import { App, Button, Menu, Space, Spin, Tree, Typography } from 'antd';
import React, {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { createPortal } from 'react-dom';
import { leaveShare } from '@/services/share';
import type {
  ActiveSelectedInfo,
  ArticleInfoType,
  CatalogTreeNode,
  CatalogType,
} from '@/types/rt.type';

// ── 工具函数 ──

type MenuItems = NonNullable<MenuProps['items']>;

function buildTreeNodes(catalogs: CatalogType[]): CatalogTreeNode[] {
  return catalogs.map((cat) => {
    const childCatalogs =
      cat.children && cat.children.length > 0
        ? buildTreeNodes(cat.children)
        : [];
    const articleNodes =
      cat.articles && cat.articles.length > 0
        ? buildArticleNodes(cat.articles)
        : [];
    return {
      key: `catalog-${cat.id}`,
      title: cat.name,
      isLeaf: childCatalogs.length + articleNodes.length === 0,
      children:
        childCatalogs.length + articleNodes.length > 0
          ? [...childCatalogs, ...articleNodes]
          : undefined,
      data: cat,
      type: 'catalog' as const,
    };
  });
}

function buildArticleNodes(articles: ArticleInfoType[]): CatalogTreeNode[] {
  return articles.map((article) => ({
    key: `article-${article.id}`,
    title: article.title || i18nText("app.article.sidebar.spacetree.f817a1f4"),
    isLeaf: true,
    children: undefined,
    data: article,
    type: 'article' as const,
  }));
}

function findArticlePath(
  nodes: CatalogTreeNode[],
  articleId: number,
  parentKeys: string[] = [],
): { articleKey: string; parentKeys: string[] } | null {
  for (const node of nodes) {
    if (node.type === 'article' && (node.data as any).id === articleId) {
      return { articleKey: `article-${articleId}`, parentKeys };
    }
    if (node.children) {
      const found = findArticlePath(node.children, articleId, [
        ...parentKeys,
        node.key as string,
      ]);
      if (found) return found;
    }
  }
  return null;
}

function collectExpandableKeys(nodes: CatalogTreeNode[]): React.Key[] {
  const keys: React.Key[] = [];
  const walk = (items: CatalogTreeNode[]) => {
    items.forEach((node) => {
      if (node.children?.length) {
        keys.push(node.key);
        walk(node.children);
      }
    });
  };
  walk(nodes);
  return keys;
}

function getPermissionLabel(permission?: string, type?: 'catalog' | 'article') {
  if (!permission) return i18nText("app.article.sidebar.spacetree.26e7e3fb");
  const labels: Record<string, string> = {
    READ: i18nText("app.article.sidebar.spacetree.5a5ff0ed"),
    COMMENT: i18nText("app.article.sidebar.spacetree.7d722924"),
    READ_WRITE: i18nText("app.article.sidebar.spacetree.40d0beb3"),
    ACCESS: i18nText("app.article.sidebar.spacetree.1166af46"),
    CREATE_CHILD: i18nText("app.article.sidebar.spacetree.5a99b362"),
    FULL_CONTROL: i18nText("app.article.sidebar.spacetree.6f09acef"),
  };
  if (permission === 'READ' && type === 'catalog') {
    return i18nText("app.article.sidebar.spacetree.1166af46");
  }
  return labels[permission] ?? permission;
}

function permissionMenuItems(node: CatalogTreeNode): MenuItems {
  const permission =
    node.type === 'catalog'
      ? (node.data as CatalogType).effectivePermission
      : (node.data as ArticleInfoType).effectivePermission;
  return [
    {
      key: 'current-permission',
      label: i18nText("app.article.sidebar.spacetree.dd47a0d8", {value0: getPermissionLabel(permission, node.type)}),
      disabled: true,
    },
    { type: 'divider' },
  ];
}

// ── 组件 Props ──

export interface SpaceTreeProps {
  /** 空间标识 */
  space: 'my' | 'shared' | 'public';
  /** 目录数据（从父组件传入） */
  catalogs: CatalogType[];
  /** 加载状态 */
  loading?: boolean;
  /** 当前选中的文章ID */
  activeArticleId?: number;
  /** 文章选中回调 */
  onArticleSelect: (activeJumpInfo: ActiveSelectedInfo) => void;
  /** 文章取消选中回调 */
  onArticleDeselect: () => void;
  /** 树数据变更回调 */
  onDataChange: () => void;
  /** 新建根目录（仅 my space） */
  onAddRootCatalog?: () => void;
  /** 新建子目录 */
  onAddSubCatalog?: (fatherId: number) => void;
  /** 重命名目录 */
  onRenameCatalog?: (catalogId: number, currentName: string) => void;
  /** 删除目录 */
  onDeleteCatalog?: (catalogId: number, catalogName: string) => void;
  /** 新建文章 */
  onCreateArticle?: (catalogId: number) => void;
  /** 导入 Markdown 文章 */
  onImportMarkdown?: (catalogId: number) => void;
  /** 导入 Markdown 压缩包 */
  onImportMarkdownZip?: (catalogId: number) => void;
  /** 移动文章 */
  onMoveArticle?: (articleId: number, space?: 'my' | 'public') => void;
  /** 重命名文章 */
  onRenameArticle?: (articleId: number, currentTitle: string) => void;
  /** 删除文章 */
  onDeleteArticle?: (articleId: number, articleTitle: string) => void;
  /** 批量删除文章 */
  onBatchDeleteArticles?: (articleIds: number[]) => void;
  /** 批量移动文章 */
  onBatchMoveArticles?: (articleIds: number[], space?: 'my' | 'public') => void;
  /** 批量导出文章 */
  onExportArticles?: (articleIds: number[]) => void;
  /** 分享设置 */
  onShareSetting?: (resourceType: string, resourceId: number) => void;
  /** 发布至公共空间 / 撤回公共 */
  onTogglePublic?: (
    resourceType: string,
    resourceId: number,
    isPublic: boolean,
  ) => void;
  /** 发布至公共空间（弹出目录选择） */
  onPublishToPublic?: (
    resourceType: 'CATALOG' | 'ARTICLE',
    resourceId: number,
    resourceName: string,
  ) => void;
  /** 复制到我的空间 */
  onCopyToMySpace?: (resourceType: string, resourceId: number) => void;
  /** 拖拽放置回调 */
  onTreeDrop?: (info: any) => void;
  /** 树节点外空白区域右键回调 */
  onBlankContextMenu?: (event: React.MouseEvent<HTMLDivElement>) => void;
  batchMode?: boolean;
  searchKeyword?: string;
}

export default function SpaceTree({
  space,
  catalogs,
  loading,
  activeArticleId,
  onArticleSelect,
  onDataChange,
  onAddSubCatalog,
  onRenameCatalog,
  onDeleteCatalog,
  onCreateArticle,
  onImportMarkdown,
  onImportMarkdownZip,
  onMoveArticle,
  onRenameArticle,
  onDeleteArticle,
  onBatchDeleteArticles,
  onBatchMoveArticles,
  onExportArticles,
  onShareSetting,
  onTogglePublic,
  onPublishToPublic,
  onCopyToMySpace,
  onTreeDrop,
  onBlankContextMenu,
  batchMode = false,
  searchKeyword = '',
}: SpaceTreeProps) {
  const { message } = App.useApp();

  const [treeData, setTreeData] = useState<CatalogTreeNode[]>([]);
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [checkedKeys, setCheckedKeys] = useState<React.Key[]>([]);
  const [contextMenu, setContextMenu] = useState<{
    node: CatalogTreeNode;
    x: number;
    y: number;
  } | null>(null);
  const contextMenuRef = useRef<HTMLDivElement>(null);
  const [contextMenuPosition, setContextMenuPosition] = useState<{
    left: number;
    top: number;
  } | null>(null);

  // 点击外部或再次右键时关闭当前菜单
  useEffect(() => {
    if (!contextMenu) return;
    const close = () => setContextMenu(null);
    document.addEventListener('click', close);
    document.addEventListener('contextmenu', close, true);
    return () => {
      document.removeEventListener('click', close);
      document.removeEventListener('contextmenu', close, true);
    };
  }, [contextMenu]);

  useLayoutEffect(() => {
    if (!contextMenu || !contextMenuRef.current) return;

    const { width, height } = contextMenuRef.current.getBoundingClientRect();
    const viewportPadding = 8;
    setContextMenuPosition({
      left: Math.max(
        viewportPadding,
        Math.min(contextMenu.x, window.innerWidth - width - viewportPadding),
      ),
      top:
        contextMenu.y + height > window.innerHeight - viewportPadding
          ? Math.max(viewportPadding, contextMenu.y - height)
          : contextMenu.y,
    });
  }, [contextMenu]);

  useEffect(() => {
    setTreeData(buildTreeNodes(catalogs ?? []));
  }, [catalogs]);

  useEffect(() => {
    if (searchKeyword.trim()) {
      setExpandedKeys(collectExpandableKeys(treeData));
    }
  }, [searchKeyword, treeData]);

  useEffect(() => {
    if (!batchMode) {
      setCheckedKeys([]);
    }
  }, [batchMode]);

  // 当 activeArticleId 变化时自动展开并选中
  useEffect(() => {
    if (searchKeyword.trim()) return;
    if (!activeArticleId || treeData.length === 0) {
      setSelectedKeys([]);
      return;
    }
    const result = findArticlePath(treeData, activeArticleId);
    if (result) {
      setExpandedKeys(result.parentKeys);
      setSelectedKeys([result.articleKey]);
    } else {
      setSelectedKeys([]);
    }
  }, [activeArticleId, searchKeyword, treeData]);

  const handleSelect = useCallback(
    (_keys: React.Key[], info: { node: CatalogTreeNode }) => {
      const { node } = info;
      if (node.type === 'article') {
        const article = node.data as any;
        onArticleSelect({ articleId: article.id, space });
        setSelectedKeys([node.key]);
      }
    },
    [onArticleSelect, space],
  );

  const selectedArticleIds = useMemo(
    () =>
      checkedKeys
        .map((key) => String(key))
        .filter((key) => key.startsWith('article-'))
        .map((key) => Number(key.replace('article-', '')))
        .filter((id) => !Number.isNaN(id)),
    [checkedKeys],
  );

  const selectedArticleNodes = useMemo(() => {
    const selectedIds = new Set(selectedArticleIds);
    const result: ArticleInfoType[] = [];
    const walk = (nodes: CatalogTreeNode[]) => {
      nodes.forEach((node) => {
        const article = node.data as ArticleInfoType;
        if (
          node.type === 'article' &&
          article.id != null &&
          selectedIds.has(article.id)
        ) {
          result.push(article);
        }
        if (node.children) walk(node.children);
      });
    };
    walk(treeData);
    return result;
  }, [selectedArticleIds, treeData]);

  const canBatchMutate =
    space === 'my' ||
    (space === 'public' &&
      selectedArticleNodes.length > 0 &&
      selectedArticleNodes.every((article) => article.canDelete));

  const canDragNode = useCallback(
    (node: any) => {
      if (space === 'my') return true;
      if (space !== 'public') return false;
      return Boolean(node?.data?.canDelete);
    },
    [space],
  );

  const handleCheck = useCallback((keys: any) => {
    const nextKeys = Array.isArray(keys) ? keys : keys?.checked;
    setCheckedKeys(nextKeys ?? []);
  }, []);

  const handleBatchMoveClick = useCallback(() => {
    onBatchMoveArticles?.(
      [...selectedArticleIds],
      space === 'public' ? 'public' : 'my',
    );
    setCheckedKeys([]);
  }, [onBatchMoveArticles, selectedArticleIds, space]);

  const handleBatchDeleteClick = useCallback(() => {
    onBatchDeleteArticles?.([...selectedArticleIds]);
    setCheckedKeys([]);
  }, [onBatchDeleteArticles, selectedArticleIds]);

  const handleExportClick = useCallback(() => {
    onExportArticles?.([...selectedArticleIds]);
    setCheckedKeys([]);
  }, [onExportArticles, selectedArticleIds]);

  const handleRightClick = useCallback(
    ({ event, node }: { event: React.MouseEvent; node: CatalogTreeNode }) => {
      event.preventDefault();
      event.stopPropagation();
      setContextMenuPosition(null);
      setContextMenu({ node, x: event.clientX, y: event.clientY });
    },
    [],
  );

  const renderMatchedTitle = useCallback(
    (title: React.ReactNode) => {
      const content = String(title ?? '');
      const keyword = searchKeyword.trim();
      if (!keyword) {
        return content;
      }

      const matchIndex = content.toLowerCase().indexOf(keyword.toLowerCase());
      if (matchIndex < 0) {
        return content;
      }

      return (
        <>
          {content.slice(0, matchIndex)}
          <mark className="rounded-[3px] bg-[#fff1b8] px-0.5 text-[#ad4e00]">
            {content.slice(matchIndex, matchIndex + keyword.length)}
          </mark>
          {content.slice(matchIndex + keyword.length)}
        </>
      );
    },
    [searchKeyword],
  );

  // ── 右键菜单构建（根据 space + effectivePermission 动态生成）──

  const buildContextMenu = useCallback(
    (node: CatalogTreeNode): MenuProps['items'] => {
      const hasFullControl = Boolean((node.data as CatalogType)?.canDelete);
      const hasCreateChild = Boolean(
        (node.data as CatalogType)?.canCreateChild,
      );

      if (node.type === 'catalog') {
        const catalog = node.data as CatalogType;
        const items: MenuItems = permissionMenuItems(node);
        if (catalog.id < 0) {
          return items;
        }

        if (space === 'my') {
          items.push(
            {
              key: 'add-sub-catalog',
              label: i18nText("app.article.sidebar.spacetree.ce3a66c8"),
              icon: <FolderAddOutlined />,
              onClick: () => onAddSubCatalog?.(catalog.id),
            },
            {
              key: 'create-article',
              label: i18nText("app.article.sidebar.spacetree.bd3ed3d7"),
              icon: <FileAddOutlined />,
              onClick: () => onCreateArticle?.(catalog.id),
            },
            {
              key: 'import-markdown',
              label: i18nText("app.article.sidebar.spacetree.625e893f"),
              icon: <UploadOutlined />,
              onClick: () => onImportMarkdown?.(catalog.id),
            },
            {
              key: 'import-markdown-zip',
              label: i18nText("app.article.sidebar.spacetree.50034169"),
              icon: <FileZipOutlined />,
              onClick: () => onImportMarkdownZip?.(catalog.id),
            },
            { type: 'divider' },
            {
              key: 'rename-catalog',
              label: i18nText("app.article.sidebar.spacetree.36e5a20b"),
              icon: <EditOutlined />,
              onClick: () => onRenameCatalog?.(catalog.id, catalog.name),
            },
            {
              key: 'share-setting',
              label: i18nText("app.article.sidebar.spacetree.5b56d2a6"),
              icon: <ShareAltOutlined />,
              onClick: () => onShareSetting?.('CATALOG', catalog.id),
            },
            ...(catalog.isPublic
              ? [
                  {
                    key: 'toggle-public',
                    label: i18nText("app.article.sidebar.spacetree.5867a669"),
                    icon: <StopOutlined />,
                    onClick: () =>
                      onTogglePublic?.('CATALOG', catalog.id, false),
                  },
                ]
              : [
                  {
                    key: 'publish-to-public',
                    label: i18nText("app.article.sidebar.spacetree.84483664"),
                    icon: <GlobalOutlined />,
                    onClick: () =>
                      onPublishToPublic?.('CATALOG', catalog.id, catalog.name),
                  },
                ]),
            { type: 'divider' },
            {
              key: 'delete-catalog',
              label: i18nText("app.article.sidebar.spacetree.171ba34e"),
              icon: <DeleteOutlined />,
              danger: true,
              onClick: () => onDeleteCatalog?.(catalog.id, catalog.name),
            },
          );
        } else if (space === 'shared') {
          if (hasCreateChild) {
            items.push(
              {
                key: 'add-sub-catalog',
                label: i18nText("app.article.sidebar.spacetree.ce3a66c8"),
                icon: <FolderAddOutlined />,
                onClick: () => onAddSubCatalog?.(catalog.id),
              },
              {
                key: 'create-article',
                label: i18nText("app.article.sidebar.spacetree.bd3ed3d7"),
                icon: <FileAddOutlined />,
                onClick: () => onCreateArticle?.(catalog.id),
              },
              {
                key: 'import-markdown',
                label: i18nText("app.article.sidebar.spacetree.625e893f"),
                icon: <UploadOutlined />,
                onClick: () => onImportMarkdown?.(catalog.id),
              },
              {
                key: 'import-markdown-zip',
                label: i18nText("app.article.sidebar.spacetree.50034169"),
                icon: <FileZipOutlined />,
                onClick: () => onImportMarkdownZip?.(catalog.id),
              },
              { type: 'divider' },
            );
          }
          items.push({
            key: 'leave-share-catalog',
            label: i18nText("app.article.sidebar.spacetree.aab54257"),
            icon: <StopOutlined />,
            danger: true,
            onClick: async () => {
              try {
                await leaveShare('CATALOG', catalog.id);
                message.success(i18nText("app.article.sidebar.spacetree.66797756"));
                onDataChange();
              } catch {
                message.error(i18nText("app.article.sidebar.spacetree.df1c9393"));
              }
            },
          });
        } else if (space === 'public') {
          if (hasFullControl) {
            items.push(
              {
                key: 'add-sub-catalog',
                label: i18nText("app.article.sidebar.spacetree.ce3a66c8"),
                icon: <FolderAddOutlined />,
                onClick: () => onAddSubCatalog?.(catalog.id),
              },
              {
                key: 'create-article',
                label: i18nText("app.article.sidebar.spacetree.bd3ed3d7"),
                icon: <FileAddOutlined />,
                onClick: () => onCreateArticle?.(catalog.id),
              },
              {
                key: 'import-markdown',
                label: i18nText("app.article.sidebar.spacetree.625e893f"),
                icon: <UploadOutlined />,
                onClick: () => onImportMarkdown?.(catalog.id),
              },
              {
                key: 'import-markdown-zip',
                label: i18nText("app.article.sidebar.spacetree.50034169"),
                icon: <FileZipOutlined />,
                onClick: () => onImportMarkdownZip?.(catalog.id),
              },
              {
                key: 'share-setting',
                label: i18nText("app.article.sidebar.spacetree.5b56d2a6"),
                icon: <ShareAltOutlined />,
                onClick: () => onShareSetting?.('CATALOG', catalog.id),
              },
              { type: 'divider' },
              {
                key: 'toggle-public',
                label: i18nText("app.article.sidebar.spacetree.5867a669"),
                icon: <StopOutlined />,
                onClick: () => onTogglePublic?.('CATALOG', catalog.id, false),
              },
            );
            items.splice(3, 0, {
              key: 'rename-catalog',
              label: i18nText("app.article.sidebar.spacetree.36e5a20b"),
              icon: <EditOutlined />,
              onClick: () => onRenameCatalog?.(catalog.id, catalog.name),
            });
            items.push(
              { type: 'divider' },
              {
                key: 'delete-catalog',
                label: i18nText("app.article.sidebar.spacetree.171ba34e"),
                icon: <DeleteOutlined />,
                danger: true,
                onClick: () => onDeleteCatalog?.(catalog.id, catalog.name),
              },
            );
          } else {
            items.push({
              key: 'copy-catalog',
              label: i18nText("app.article.sidebar.spacetree.b8cb3d2b"),
              icon: <CopyOutlined />,
              onClick: () => onCopyToMySpace?.('CATALOG', catalog.id),
            });
          }
        }

        return items;
      }

      // ── 文章节点菜单 ──
      const article = node.data as any;
      const items: MenuItems = permissionMenuItems(node);

      if (space === 'my') {
        items.push(
          {
            key: 'rename-article',
            label: i18nText("app.article.sidebar.spacetree.d40c4e29"),
            icon: <EditOutlined />,
            onClick: () =>
              onRenameArticle?.(article.id, article.title || i18nText("app.article.sidebar.spacetree.f817a1f4")),
          },
          {
            key: 'move-article',
            label: i18nText("app.article.sidebar.spacetree.2fdd9f34"),
            icon: <SwapOutlined />,
            onClick: () => onMoveArticle?.(article.id),
          },
          {
            key: 'share-setting',
            label: i18nText("app.article.sidebar.spacetree.5b56d2a6"),
            icon: <ShareAltOutlined />,
            onClick: () => onShareSetting?.('ARTICLE', article.id),
          },
          ...(article.isPublic
            ? [
                {
                  key: 'toggle-public',
                  label: i18nText("app.article.sidebar.spacetree.5867a669"),
                  icon: <StopOutlined />,
                  onClick: () => onTogglePublic?.('ARTICLE', article.id, false),
                },
              ]
            : [
                {
                  key: 'publish-to-public',
                  label: i18nText("app.article.sidebar.spacetree.84483664"),
                  icon: <GlobalOutlined />,
                  onClick: () =>
                    onPublishToPublic?.(
                      'ARTICLE',
                      article.id,
                      article.title || i18nText("app.article.sidebar.spacetree.f817a1f4"),
                    ),
                },
              ]),
          { type: 'divider' },
          {
            key: 'delete-article',
            label: i18nText("app.article.sidebar.spacetree.96e52f5b"),
            icon: <DeleteOutlined />,
            danger: true,
            onClick: () =>
              onDeleteArticle?.(article.id, article.title || i18nText("app.article.sidebar.spacetree.f817a1f4")),
          },
        );
      } else if (space === 'shared') {
        // 文章权限直接从后端返回的 effectivePermission 获取
        const articleCanWrite = Boolean(
          (node.data as ArticleInfoType)?.canWrite,
        );
        if (articleCanWrite) {
          items.push(
            {
              key: 'rename-article',
              label: i18nText("app.article.sidebar.spacetree.d40c4e29"),
              icon: <EditOutlined />,
              onClick: () =>
                onRenameArticle?.(article.id, article.title || i18nText("app.article.sidebar.spacetree.f817a1f4")),
            },
            { type: 'divider' },
          );
        }
        items.push({
          key: 'leave-share-article',
          label: i18nText("app.article.sidebar.spacetree.aab54257"),
          icon: <StopOutlined />,
          danger: true,
          onClick: async () => {
            try {
              await leaveShare('ARTICLE', article.id);
              message.success(i18nText("app.article.sidebar.spacetree.66797756"));
              onDataChange();
            } catch {
              message.error(i18nText("app.article.sidebar.spacetree.df1c9393"));
            }
          },
        });
      } else if (space === 'public') {
        if (hasFullControl) {
          items.push({
            key: 'toggle-public',
            label: i18nText("app.article.sidebar.spacetree.5867a669"),
            icon: <StopOutlined />,
            onClick: () => onTogglePublic?.('ARTICLE', article.id, false),
          });
          items.unshift({
            key: 'rename-article',
            label: i18nText("app.article.sidebar.spacetree.d40c4e29"),
            icon: <EditOutlined />,
            onClick: () =>
              onRenameArticle?.(article.id, article.title || i18nText("app.article.sidebar.spacetree.f817a1f4")),
          });
          items.splice(1, 0, {
            key: 'move-article',
            label: i18nText("app.article.sidebar.spacetree.2fdd9f34"),
            icon: <SwapOutlined />,
            onClick: () => onMoveArticle?.(article.id, 'public'),
          });
          items.splice(2, 0, {
            key: 'share-setting',
            label: i18nText("app.article.sidebar.spacetree.5b56d2a6"),
            icon: <ShareAltOutlined />,
            onClick: () => onShareSetting?.('ARTICLE', article.id),
          });
          items.push(
            { type: 'divider' },
            {
              key: 'delete-article',
              label: i18nText("app.article.sidebar.spacetree.96e52f5b"),
              icon: <DeleteOutlined />,
              danger: true,
              onClick: () =>
                onDeleteArticle?.(article.id, article.title || i18nText("app.article.sidebar.spacetree.f817a1f4")),
            },
          );
        } else {
          items.push({
            key: 'copy-article',
            label: i18nText("app.article.sidebar.spacetree.b8cb3d2b"),
            icon: <CopyOutlined />,
            onClick: () => onCopyToMySpace?.('ARTICLE', article.id),
          });
        }
      }

      return items;
    },
    [
      space,
      onAddSubCatalog,
      onCreateArticle,
      onImportMarkdown,
      onImportMarkdownZip,
      onRenameCatalog,
      onRenameArticle,
      onMoveArticle,
      onDeleteCatalog,
      onDeleteArticle,
      onShareSetting,
      onTogglePublic,
      onPublishToPublic,
      onCopyToMySpace,
      onDataChange,
      message,
    ],
  );

  return (
    <div
      className="overflow-auto py-1"
      onContextMenu={(event) => {
        event.preventDefault();
        onBlankContextMenu?.(event);
      }}
    >
      {batchMode && selectedArticleIds.length > 0 && (
        <div className="sticky top-0 z-[2] mb-1.5 flex items-center justify-between gap-2 border-b border-[#f0f0f0] bg-white px-2.5 py-2">
          <Typography.Text type="secondary" className="text-xs">
            {i18nText("app.article.sidebar.spacetree.720fa76a")} {selectedArticleIds.length} {i18nText("app.article.sidebar.spacetree.db52c85e")}
          </Typography.Text>
          <Space size={4}>
            {canBatchMutate && (
              <>
                <Button
                  size="small"
                  icon={<SwapOutlined />}
                  onClick={handleBatchMoveClick}
                >
                  {i18nText("app.article.sidebar.spacetree.2498f80b")}
                </Button>
                <Button
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  onClick={handleBatchDeleteClick}
                >
                  {i18nText("app.article.sidebar.spacetree.b04574e1")}
                </Button>
              </>
            )}
            <Button
              size="small"
              icon={<DownloadOutlined />}
              onClick={handleExportClick}
            >
              {i18nText("app.article.sidebar.spacetree.546c963a")}
            </Button>
            <Button size="small" type="link" onClick={() => setCheckedKeys([])}>
              {i18nText("app.article.sidebar.spacetree.3b1a521f")}
            </Button>
          </Space>
        </div>
      )}
      {loading ? (
        <div className="p-6 text-center">
          <Spin />
        </div>
      ) : (
        <Tree.DirectoryTree
          expandedKeys={expandedKeys}
          selectedKeys={selectedKeys}
          onSelect={handleSelect}
          onExpand={(keys) => setExpandedKeys(keys)}
          onRightClick={handleRightClick}
          checkable={batchMode}
          checkedKeys={checkedKeys}
          onCheck={handleCheck}
          treeData={treeData as any}
          titleRender={(node: CatalogTreeNode) => {
            return (
              <span
                className={`inline-flex items-center gap-1.5 max-w-full overflow-hidden whitespace-nowrap
                ${node.type === 'catalog' ? 'text-[14px] font-medium' : 'text-[13px] font-normal'}`}
              >
                <span className="flex-shrink overflow-hidden text-ellipsis whitespace-nowrap">
                  {renderMatchedTitle(node.title)}
                </span>
              </span>
            );
          }}
          draggable={
            space === 'my' || space === 'public'
              ? { icon: false, nodeDraggable: canDragNode }
              : false
          }
          onDrop={space === 'my' || space === 'public' ? onTreeDrop : undefined}
          blockNode
          showIcon
          icon={(props: any) => {
            if (props.type === 'article') return null;
            return <FolderTwoTone />;
          }}
        />
      )}
      {contextMenu &&
        createPortal(
          <>
            <div
              ref={contextMenuRef}
              className="fixed z-[1000]"
              onContextMenu={(event) => event.preventDefault()}
              style={
                contextMenuPosition
                  ? contextMenuPosition
                  : {
                      left: contextMenu.x,
                      top: contextMenu.y,
                      visibility: 'hidden',
                    }
              }
            >
              <Menu
                className="rounded-lg border border-[#e5e7eb] shadow-lg [&_.ant-menu-title-content]:!overflow-visible [&_.ant-menu-title-content]:!text-clip [&_.ant-menu-title-content]:!whitespace-normal"
                style={{ minWidth: 220, maxWidth: 'calc(100vw - 16px)' }}
                items={buildContextMenu(contextMenu.node)}
              />
            </div>
          </>,
          document.body,
        )}
    </div>
  );
}
