import {
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  FileAddOutlined,
  FolderAddOutlined,
  FolderTwoTone,
  GlobalOutlined,
  ShareAltOutlined,
  StopOutlined,
  SwapOutlined,
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
    title: article.title || '(无标题)',
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
  if (!permission) return '未知';
  const labels: Record<string, string> = {
    READ: '可读',
    COMMENT: '可批注',
    READ_WRITE: '可编辑',
    ACCESS: '可访问',
    CREATE_CHILD: '可新建子内容',
    FULL_CONTROL: '完全控制',
  };
  if (permission === 'READ' && type === 'catalog') {
    return '可访问';
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
      label: `当前权限：${getPermissionLabel(permission, node.type)}`,
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

  // 点击外部关闭右键菜单
  useEffect(() => {
    if (!contextMenu) return;
    const close = () => setContextMenu(null);
    document.addEventListener('click', close);
    return () => document.removeEventListener('click', close);
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
              label: '新建子目录',
              icon: <FolderAddOutlined />,
              onClick: () => onAddSubCatalog?.(catalog.id),
            },
            {
              key: 'create-article',
              label: '在此目录下新建文章',
              icon: <FileAddOutlined />,
              onClick: () => onCreateArticle?.(catalog.id),
            },
            { type: 'divider' },
            {
              key: 'rename-catalog',
              label: '重命名',
              icon: <EditOutlined />,
              onClick: () => onRenameCatalog?.(catalog.id, catalog.name),
            },
            {
              key: 'share-setting',
              label: '分享设置',
              icon: <ShareAltOutlined />,
              onClick: () => onShareSetting?.('CATALOG', catalog.id),
            },
            ...(catalog.isPublic
              ? [
                  {
                    key: 'toggle-public',
                    label: '撤回公共状态',
                    icon: <StopOutlined />,
                    onClick: () =>
                      onTogglePublic?.('CATALOG', catalog.id, false),
                  },
                ]
              : [
                  {
                    key: 'publish-to-public',
                    label: '发布至公共空间',
                    icon: <GlobalOutlined />,
                    onClick: () =>
                      onPublishToPublic?.('CATALOG', catalog.id, catalog.name),
                  },
                ]),
            { type: 'divider' },
            {
              key: 'delete-catalog',
              label: '删除目录',
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
                label: '新建子目录',
                icon: <FolderAddOutlined />,
                onClick: () => onAddSubCatalog?.(catalog.id),
              },
              {
                key: 'create-article',
                label: '在此目录下新建文章',
                icon: <FileAddOutlined />,
                onClick: () => onCreateArticle?.(catalog.id),
              },
              { type: 'divider' },
            );
          }
          items.push({
            key: 'leave-share-catalog',
            label: '退出共享',
            icon: <StopOutlined />,
            danger: true,
            onClick: async () => {
              try {
                await leaveShare('CATALOG', catalog.id);
                message.success('已退出共享');
                onDataChange();
              } catch {
                message.error('退出共享失败');
              }
            },
          });
        } else if (space === 'public') {
          if (hasFullControl) {
            items.push(
              {
                key: 'add-sub-catalog',
                label: '新建子目录',
                icon: <FolderAddOutlined />,
                onClick: () => onAddSubCatalog?.(catalog.id),
              },
              {
                key: 'create-article',
                label: '在此目录下新建文章',
                icon: <FileAddOutlined />,
                onClick: () => onCreateArticle?.(catalog.id),
              },
              {
                key: 'share-setting',
                label: '分享设置',
                icon: <ShareAltOutlined />,
                onClick: () => onShareSetting?.('CATALOG', catalog.id),
              },
              { type: 'divider' },
              {
                key: 'toggle-public',
                label: '撤回公共状态',
                icon: <StopOutlined />,
                onClick: () => onTogglePublic?.('CATALOG', catalog.id, false),
              },
            );
            items.splice(3, 0, {
              key: 'rename-catalog',
              label: '重命名',
              icon: <EditOutlined />,
              onClick: () => onRenameCatalog?.(catalog.id, catalog.name),
            });
            items.push(
              { type: 'divider' },
              {
                key: 'delete-catalog',
                label: '删除目录',
                icon: <DeleteOutlined />,
                danger: true,
                onClick: () => onDeleteCatalog?.(catalog.id, catalog.name),
              },
            );
          } else {
            items.push({
              key: 'copy-catalog',
              label: '复制到我的空间',
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
            label: '修改标题',
            icon: <EditOutlined />,
            onClick: () =>
              onRenameArticle?.(article.id, article.title || '(无标题)'),
          },
          {
            key: 'move-article',
            label: '移动到目录',
            icon: <SwapOutlined />,
            onClick: () => onMoveArticle?.(article.id),
          },
          {
            key: 'share-setting',
            label: '分享设置',
            icon: <ShareAltOutlined />,
            onClick: () => onShareSetting?.('ARTICLE', article.id),
          },
          ...(article.isPublic
            ? [
                {
                  key: 'toggle-public',
                  label: '撤回公共状态',
                  icon: <StopOutlined />,
                  onClick: () => onTogglePublic?.('ARTICLE', article.id, false),
                },
              ]
            : [
                {
                  key: 'publish-to-public',
                  label: '发布至公共空间',
                  icon: <GlobalOutlined />,
                  onClick: () =>
                    onPublishToPublic?.(
                      'ARTICLE',
                      article.id,
                      article.title || '(无标题)',
                    ),
                },
              ]),
          { type: 'divider' },
          {
            key: 'delete-article',
            label: '删除文章',
            icon: <DeleteOutlined />,
            danger: true,
            onClick: () =>
              onDeleteArticle?.(article.id, article.title || '(无标题)'),
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
              label: '修改标题',
              icon: <EditOutlined />,
              onClick: () =>
                onRenameArticle?.(article.id, article.title || '(无标题)'),
            },
            { type: 'divider' },
          );
        }
        items.push({
          key: 'leave-share-article',
          label: '退出共享',
          icon: <StopOutlined />,
          danger: true,
          onClick: async () => {
            try {
              await leaveShare('ARTICLE', article.id);
              message.success('已退出共享');
              onDataChange();
            } catch {
              message.error('退出共享失败');
            }
          },
        });
      } else if (space === 'public') {
        if (hasFullControl) {
          items.push({
            key: 'toggle-public',
            label: '撤回公共状态',
            icon: <StopOutlined />,
            onClick: () => onTogglePublic?.('ARTICLE', article.id, false),
          });
          items.unshift({
            key: 'rename-article',
            label: '修改标题',
            icon: <EditOutlined />,
            onClick: () =>
              onRenameArticle?.(article.id, article.title || '(无标题)'),
          });
          items.splice(1, 0, {
            key: 'move-article',
            label: '移动到目录',
            icon: <SwapOutlined />,
            onClick: () => onMoveArticle?.(article.id, 'public'),
          });
          items.splice(2, 0, {
            key: 'share-setting',
            label: '分享设置',
            icon: <ShareAltOutlined />,
            onClick: () => onShareSetting?.('ARTICLE', article.id),
          });
          items.push(
            { type: 'divider' },
            {
              key: 'delete-article',
              label: '删除文章',
              icon: <DeleteOutlined />,
              danger: true,
              onClick: () =>
                onDeleteArticle?.(article.id, article.title || '(无标题)'),
            },
          );
        } else {
          items.push({
            key: 'copy-article',
            label: '复制到我的空间',
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
    <div className="overflow-auto py-1">
      {batchMode && selectedArticleIds.length > 0 && (
        <div className="sticky top-0 z-[2] mb-1.5 flex items-center justify-between gap-2 border-b border-[#f0f0f0] bg-white px-2.5 py-2">
          <Typography.Text type="secondary" className="text-xs">
            已选择 {selectedArticleIds.length} 篇文章
          </Typography.Text>
          <Space size={4}>
            {canBatchMutate && (
              <>
                <Button
                  size="small"
                  icon={<SwapOutlined />}
                  onClick={handleBatchMoveClick}
                >
                  移动
                </Button>
                <Button
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  onClick={handleBatchDeleteClick}
                >
                  删除
                </Button>
              </>
            )}
            <Button
              size="small"
              icon={<DownloadOutlined />}
              onClick={handleExportClick}
            >
              导出
            </Button>
            <Button size="small" type="link" onClick={() => setCheckedKeys([])}>
              清空
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
              className="fixed inset-0 z-[999]"
              onClick={() => setContextMenu(null)}
            />
            <div
              ref={contextMenuRef}
              className="fixed z-[1000]"
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
                className="[&_.ant-menu-title-content]:!overflow-visible [&_.ant-menu-title-content]:!text-clip [&_.ant-menu-title-content]:!whitespace-normal"
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
