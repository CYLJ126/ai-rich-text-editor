import {
  CheckSquareOutlined,
  CloseOutlined,
  FolderAddOutlined,
  GlobalOutlined,
  LockOutlined,
  MoreOutlined,
  SearchOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import { useModel } from '@umijs/max';
import type { CollapseProps } from 'antd';
import {
  App,
  Button,
  Collapse,
  Dropdown,
  Input,
  Menu,
  Modal,
  Select,
  Typography,
} from 'antd';
import React, {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { hasAdminRole } from '@/access';
import { useArticleInfoStore } from '@/components';
import { AutoFocusInput } from '@/components/ui/autofucus-input';
import {
  addArticle,
  addCatalog,
  batchDeleteArticles,
  batchMoveToCatalogByIds,
  deleteArticle,
  deleteCatalog,
  getArticleById,
  moveToCatalog,
  reorderCatalogs,
  updateArticle,
  updateCatalog,
} from '@/services/ant-design-pro/richText';
import {
  copyArticleToMySpace,
  copyCatalogToMySpace,
  listSpaceCatalogs,
  publishArticleToPublic,
  publishCatalogToPublic,
  reorderArticles,
  toggleArticlePublic,
  toggleCatalogPublic,
} from '@/services/share';
import type {
  ActiveSelectedInfo,
  ArticleInfoType,
  ArticleSpace,
  CatalogType,
} from '@/types/rt.type';
import { exportFile } from '@/utils/fileUtil';
import PublishToPublicModal from './PublishToPublicModal';
import SelectTargetCatalogModal from './SelectTargetCatalogModal';
import SpaceTree from './SpaceTree';

// ── 组件 Props ──
interface CatalogTreeSidebarProps {
  activeArticleId?: number;
  onArticleSelect: (activeJumpInfo: ActiveSelectedInfo) => void;
  onArticleDeselect: () => void;
  onShareSetting?: (resourceType: string, resourceId: number) => void;
}

function containsArticle(catalogs: CatalogType[], articleId: number): boolean {
  return catalogs.some(
    (catalog) =>
      catalog.articles?.some((article) => article.id === articleId) ||
      containsArticle(catalog.children ?? [], articleId),
  );
}

function updateArticleTitle(
  catalogs: CatalogType[],
  articleId: number,
  title: string,
): CatalogType[] {
  let changed = false;
  const nextCatalogs = catalogs.map((catalog) => {
    const children = catalog.children
      ? updateArticleTitle(catalog.children, articleId, title)
      : catalog.children;
    let articles = catalog.articles;
    const articleIndex = articles?.findIndex(
      (article) => article.id === articleId && article.title !== title,
    );
    if (articles && articleIndex !== undefined && articleIndex >= 0) {
      articles = articles.map((article, index) =>
        index === articleIndex ? { ...article, title } : article,
      );
    }

    if (children !== catalog.children || articles !== catalog.articles) {
      changed = true;
      return { ...catalog, children, articles };
    }
    return catalog;
  });

  return changed ? nextCatalogs : catalogs;
}

// ── 文章目录树组件 ──
export default function CatalogTreeSidebar({
  onArticleSelect,
  onArticleDeselect,
  onShareSetting,
}: CatalogTreeSidebarProps) {
  const { message, modal } = App.useApp();
  const { initialState } = useModel('@@initialState');
  const canCreatePublicRootCatalog = hasAdminRole(initialState?.currentUser);
  const activeJumpInfo = useArticleInfoStore((state) => state.activeJumpInfo);
  const currentArticleId = useArticleInfoStore(
    (state) => state.articleInfo?.id,
  );
  const currentArticleTitle = useArticleInfoStore(
    (state) => state.articleInfo?.title,
  );
  const setArticleInfo = useArticleInfoStore((state) => state.setArticleInfo);

  const [myCatalogs, setMyCatalogs] = useState<CatalogType[]>([]);
  const [sharedCatalogs, setSharedCatalogs] = useState<CatalogType[]>([]);
  const [publicCatalogs, setPublicCatalogs] = useState<CatalogType[]>([]);
  const [treeSearchKeyword, setTreeSearchKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [activeKeys, setActiveKeys] = useState<string[]>(['my']);
  const [batchModeSpace, setBatchModeSpace] = useState<'my' | 'public' | null>(
    null,
  );
  const hasInitializedActiveSpace = useRef(false);

  const activeArticleSpace = useMemo<ArticleSpace | undefined>(() => {
    const articleId = activeJumpInfo?.articleId;
    if (!articleId) return undefined;
    if (activeJumpInfo.space) return activeJumpInfo.space;

    // URL 直接打开文章时没有空间来源，按公共、共享、我的顺序唯一定位。
    if (containsArticle(publicCatalogs, articleId)) return 'public';
    if (containsArticle(sharedCatalogs, articleId)) return 'shared';
    if (containsArticle(myCatalogs, articleId)) return 'my';
    return undefined;
  }, [activeJumpInfo, myCatalogs, publicCatalogs, sharedCatalogs]);

  const activeArticleIdForSpace = useCallback(
    (space: ArticleSpace) =>
      activeArticleSpace === space ? activeJumpInfo?.articleId : undefined,
    [activeArticleSpace, activeJumpInfo],
  );

  useEffect(() => {
    // URL 直接进入文章时，首次只展开定位到的空间；目录树点击则保留用户的面板展开状态。
    if (
      hasInitializedActiveSpace.current ||
      !activeArticleSpace ||
      activeJumpInfo?.space
    ) {
      return;
    }
    setActiveKeys([activeArticleSpace]);
    hasInitializedActiveSpace.current = true;
  }, [activeArticleSpace, activeJumpInfo?.space]);

  // ── 发布至公共空间弹窗 ──
  const [publishModal, setPublishModal] = useState<{
    open: boolean;
    resourceType: 'CATALOG' | 'ARTICLE';
    resourceId: number;
    resourceName: string;
  }>({ open: false, resourceType: 'CATALOG', resourceId: 0, resourceName: '' });

  // ── 复制到我的空间弹窗 ──
  const [copyModal, setCopyModal] = useState<{
    open: boolean;
    resourceType: 'CATALOG' | 'ARTICLE';
    resourceId: number;
    resourceName: string;
  }>({ open: false, resourceType: 'CATALOG', resourceId: 0, resourceName: '' });

  const [batchMove, setBatchMove] = useState<{
    articleIds: number[];
    sourceSpace: 'my' | 'public';
  }>({ articleIds: [], sourceSpace: 'my' });

  // ── 撤回公共状态弹窗 ──
  const [revokeModal, setRevokeModal] = useState<{
    open: boolean;
    resourceType: 'CATALOG' | 'ARTICLE';
    resourceId: number;
    resourceName: string;
  }>({ open: false, resourceType: 'CATALOG', resourceId: 0, resourceName: '' });

  // ── 空间面板右键菜单 ──
  const [spaceMenu, setSpaceMenu] = useState<{
    space: string;
    x: number;
    y: number;
  } | null>(null);
  const spaceMenuRef = useRef<HTMLDivElement>(null);
  const [spaceMenuPosition, setSpaceMenuPosition] = useState<{
    left: number;
    top: number;
  } | null>(null);

  useEffect(() => {
    if (!spaceMenu) return;
    const close = () => setSpaceMenu(null);
    document.addEventListener('click', close);
    document.addEventListener('contextmenu', close, true);
    return () => {
      document.removeEventListener('click', close);
      document.removeEventListener('contextmenu', close, true);
    };
  }, [spaceMenu]);

  useLayoutEffect(() => {
    if (!spaceMenu || !spaceMenuRef.current) return;
    const { width, height } = spaceMenuRef.current.getBoundingClientRect();
    const viewportPadding = 8;
    setSpaceMenuPosition({
      left: Math.max(
        viewportPadding,
        Math.min(spaceMenu.x, window.innerWidth - width - viewportPadding),
      ),
      top: Math.max(
        viewportPadding,
        Math.min(spaceMenu.y, window.innerHeight - height - viewportPadding),
      ),
    });
  }, [spaceMenu]);

  const openSpaceMenu = useCallback(
    (space: 'my' | 'public', event: React.MouseEvent) => {
      event.preventDefault();
      event.stopPropagation();
      setSpaceMenuPosition(null);
      setSpaceMenu({ space, x: event.clientX, y: event.clientY });
    },
    [],
  );

  const fetchAllSpaces = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listSpaceCatalogs();
      setMyCatalogs(data?.mySpace ?? []);
      setSharedCatalogs(data?.sharedWithMe ?? []);
      setPublicCatalogs(data?.publicSpace ?? []);
    } catch {
      message.error('获取目录树失败').then();
    } finally {
      setLoading(false);
    }
  }, [message]);

  useEffect(() => {
    fetchAllSpaces().then();
  }, [fetchAllSpaces]);

  useEffect(() => {
    if (!currentArticleId || currentArticleTitle === undefined) return;
    setMyCatalogs((catalogs) =>
      updateArticleTitle(catalogs, currentArticleId, currentArticleTitle),
    );
    setSharedCatalogs((catalogs) =>
      updateArticleTitle(catalogs, currentArticleId, currentArticleTitle),
    );
    setPublicCatalogs((catalogs) =>
      updateArticleTitle(catalogs, currentArticleId, currentArticleTitle),
    );
  }, [currentArticleId, currentArticleTitle]);

  const normalizedTreeSearchKeyword = treeSearchKeyword.trim().toLowerCase();

  const filterCatalogsByKeyword = useCallback(
    (catalogs: CatalogType[]): CatalogType[] => {
      if (!normalizedTreeSearchKeyword) {
        return catalogs;
      }

      return catalogs.reduce<CatalogType[]>((result, catalog) => {
        const matchedChildren = filterCatalogsByKeyword(catalog.children ?? []);
        const matchedArticles = (catalog.articles ?? []).filter((article) => {
          const title = article.title?.toLowerCase() ?? '';
          const summary = article.summary?.toLowerCase() ?? '';
          return (
            title.includes(normalizedTreeSearchKeyword) ||
            summary.includes(normalizedTreeSearchKeyword)
          );
        });
        const catalogName = catalog.name?.toLowerCase() ?? '';
        const catalogDescription = catalog.description?.toLowerCase() ?? '';
        const isCatalogMatched =
          catalogName.includes(normalizedTreeSearchKeyword) ||
          catalogDescription.includes(normalizedTreeSearchKeyword);

        if (
          isCatalogMatched ||
          matchedChildren.length > 0 ||
          matchedArticles.length > 0
        ) {
          result.push({
            ...catalog,
            children: isCatalogMatched ? catalog.children : matchedChildren,
            articles: isCatalogMatched ? catalog.articles : matchedArticles,
          });
        }

        return result;
      }, []);
    },
    [normalizedTreeSearchKeyword],
  );

  const filteredMyCatalogs = useMemo(
    () => filterCatalogsByKeyword(myCatalogs),
    [filterCatalogsByKeyword, myCatalogs],
  );
  const filteredSharedCatalogs = useMemo(
    () => filterCatalogsByKeyword(sharedCatalogs),
    [filterCatalogsByKeyword, sharedCatalogs],
  );
  const filteredPublicCatalogs = useMemo(
    () => filterCatalogsByKeyword(publicCatalogs),
    [filterCatalogsByKeyword, publicCatalogs],
  );

  const treeSearchResultCount = useMemo(() => {
    if (!normalizedTreeSearchKeyword) {
      return 0;
    }

    const countNodes = (catalogs: CatalogType[]): number =>
      catalogs.reduce(
        (total, catalog) =>
          total +
          1 +
          (catalog.articles?.length ?? 0) +
          countNodes(catalog.children ?? []),
        0,
      );

    return (
      countNodes(filteredMyCatalogs) +
      countNodes(filteredSharedCatalogs) +
      countNodes(filteredPublicCatalogs)
    );
  }, [
    filteredMyCatalogs,
    filteredPublicCatalogs,
    filteredSharedCatalogs,
    normalizedTreeSearchKeyword,
  ]);

  // ── 目录 CRUD（仅我的空间）──
  const openTextConfirm = useCallback(
    (options: {
      title: string;
      placeholder: string;
      onSubmit: (value: string) => Promise<boolean | undefined>;
    }) => {
      let value = '';
      let submitting = false;
      let dialog: ReturnType<typeof Modal.confirm>;

      const submit = async () => {
        if (submitting) {
          return false;
        }
        submitting = true;
        try {
          const result = await options.onSubmit(value.trim());
          return result !== false;
        } finally {
          submitting = false;
        }
      };

      dialog = Modal.confirm({
        // todo 这个主题背景色是应该怎么设置的？
        // className: 'dark:bg-white',
        title: options.title,
        content: (
          <AutoFocusInput
            placeholder={options.placeholder}
            onChange={(e) => {
              value = e.target.value;
            }}
            onPressEnter={async () => {
              const shouldClose = await submit();
              if (shouldClose) dialog.destroy();
            }}
          />
        ),
        onOk: async () => {
          const shouldClose = await submit();
          if (!shouldClose) {
            return Promise.reject();
          }
        },
      });
    },
    [],
  );

  const handleAddRootCatalog = useCallback(
    (isPublic = false) => {
      if (isPublic && !canCreatePublicRootCatalog) {
        message.warning('只有管理员可以在公共空间中新建根目录').then();
        return;
      }
      openTextConfirm({
        title: '新建根目录',
        placeholder: '目录名称',
        onSubmit: async (name) => {
          if (!name) {
            message.warning('目录名称不能为空').then();
            return false;
          }
          try {
            await addCatalog({ name, isPublic });
            message.success('目录创建成功').then();
            await fetchAllSpaces();
            return true;
          } catch {
            message.error('创建目录失败').then();
            return false;
          }
        },
      });
    },
    [canCreatePublicRootCatalog, fetchAllSpaces, message, openTextConfirm],
  );

  useCallback(
    (isPublic = false) => {
      openTextConfirm({
        title: '新建文章',
        placeholder: '文章标题',
        onSubmit: async (title) => {
          if (!title) {
            message.warning('文章标题不能为空').then();
            return false;
          }
          try {
            const newArticle = await addArticle({
              title,
              isPublic,
            });
            message.success(`文章"${newArticle.title}"创建成功`).then();
            onArticleSelect({
              articleId: newArticle.id,
            });
            await fetchAllSpaces();
            return true;
          } catch {
            message.error('创建文章失败').then();
            return false;
          }
        },
      });
    },
    [fetchAllSpaces, message, onArticleSelect, openTextConfirm],
  );

  const handleAddSubCatalog = useCallback(
    (fatherId: number) => {
      openTextConfirm({
        title: '新建子目录',
        placeholder: '子目录名称',
        onSubmit: async (name) => {
          if (!name) {
            message.warning('目录名称不能为空').then();
            return false;
          }
          try {
            await addCatalog({ name, fatherId });
            message.success('子目录创建成功').then();
            await fetchAllSpaces();
            return true;
          } catch {
            message.error('创建子目录失败').then();
            return false;
          }
        },
      });
    },
    [fetchAllSpaces, message, openTextConfirm],
  );

  const handleRenameCatalog = useCallback(
    (catalogId: number, currentName: string) => {
      let name = currentName;

      async function rename() {
        if (!name.trim()) {
          message.warning('目录名称不能为空').then();
          return;
        }
        try {
          await updateCatalog({ id: catalogId, name: name.trim() });
          message.success('目录已重命名').then();
          await fetchAllSpaces();
        } catch {
          message.error('重命名失败').then();
        }
      }

      Modal.confirm({
        title: '重命名目录',
        content: (
          <AutoFocusInput
            defaultValue={currentName}
            onChange={(e) => {
              name = e.target.value;
            }}
            onPressEnter={async () => {
              await rename();
            }}
          />
        ),
        onOk: async () => {
          await rename();
        },
      });
    },
    [fetchAllSpaces, message],
  );

  const handleDeleteCatalog = useCallback(
    (catalogId: number, catalogName: string) => {
      modal.confirm({
        title: '删除目录',
        content: `确定要删除目录"${catalogName}"吗？其下所有子目录和文章将被一并删除，此操作不可恢复。`,
        okText: '确认删除',
        okType: 'danger',
        cancelText: '取消',
        onOk: async () => {
          try {
            await deleteCatalog(catalogId);
            message.success('目录已删除').then();
            onArticleDeselect();
            await fetchAllSpaces();
          } catch {
            message.error('删除目录失败').then();
          }
        },
      });
    },
    [fetchAllSpaces, message, modal, onArticleDeselect],
  );

  // ── 文章 CRUD ──

  const handleCreateArticle = useCallback(
    async (catalogId: number) => {
      openTextConfirm({
        title: '新建文章',
        placeholder: '文章标题',
        onSubmit: async (title) => {
          if (!title) {
            message.warning('文章标题不能为空').then();
            return false;
          }
          try {
            const newArticle = await addArticle({
              title,
              catalogId,
            });
            message.success(`文章"${newArticle.title}"创建成功`).then();
            onArticleSelect({ articleId: newArticle.id });
            await fetchAllSpaces();
            return true;
          } catch {
            message.error('创建文章失败').then();
            return false;
          }
        },
      });
    },
    [fetchAllSpaces, message, onArticleSelect, openTextConfirm],
  );

  const handleDeleteArticle = useCallback(
    (articleId: number, articleTitle: string) => {
      modal.confirm({
        title: '删除文章',
        content: `确定要删除文章"${articleTitle}"吗？此操作不可恢复。`,
        okText: '确认删除',
        okType: 'danger',
        cancelText: '取消',
        onOk: async () => {
          try {
            await deleteArticle(articleId);
            message.success('文章已删除');
            onArticleDeselect();
            await fetchAllSpaces();
          } catch {
            message.error('删除文章失败');
          }
        },
      });
    },
    [fetchAllSpaces, message, modal, onArticleDeselect],
  );

  const handleMoveArticle = useCallback(
    (articleId: number, sourceSpace: 'my' | 'public' = 'my') => {
      const collectCatalogs = (
        nodes: CatalogType[],
      ): { label: string; value: number }[] => {
        const result: { label: string; value: number }[] = [];
        for (const node of nodes) {
          if (sourceSpace !== 'public' || node.canDelete) {
            result.push({ label: node.name, value: node.id });
          }
          if (node.children) result.push(...collectCatalogs(node.children));
        }
        return result;
      };
      const catalogOptions = collectCatalogs(
        sourceSpace === 'public' ? publicCatalogs : myCatalogs,
      );
      let targetCatalogId: number | null = null;

      Modal.confirm({
        title: '移动文章到目录',
        content: (
          <Select
            className="w-full"
            placeholder="选择目标目录"
            options={catalogOptions}
            onChange={(val) => {
              targetCatalogId = val;
            }}
          />
        ),
        onOk: async () => {
          if (targetCatalogId === null) {
            message.warning('请选择目标目录');
            return;
          }
          try {
            await moveToCatalog(articleId, targetCatalogId);
            message.success('文章已移动');
            await fetchAllSpaces();
          } catch {
            message.error('移动文章失败');
          }
        },
      });
    },
    [fetchAllSpaces, message, myCatalogs, publicCatalogs],
  );

  const handleBatchMoveArticles = useCallback(
    (articleIds: number[], sourceSpace: 'my' | 'public' = 'my') => {
      setBatchMove({ articleIds, sourceSpace });
    },
    [],
  );

  const clearBatchMove = useCallback(() => {
    setBatchMove({ articleIds: [], sourceSpace: 'my' });
  }, []);

  const handleBatchMoveSubmit = useCallback(
    async (targetCatalogId: number | null) => {
      if (targetCatalogId === null) {
        message.warning('请选择目标目录');
        return;
      }
      try {
        await batchMoveToCatalogByIds(batchMove.articleIds, targetCatalogId);
        message.success(`已移动 ${batchMove.articleIds.length} 篇文章`);
        clearBatchMove();
        await fetchAllSpaces();
      } catch {
        message.error('批量移动失败');
      }
    },
    [batchMove.articleIds, clearBatchMove, fetchAllSpaces, message],
  );

  const handleBatchDeleteArticles = useCallback(
    (articleIds: number[]) => {
      modal.confirm({
        title: '批量删除文章',
        content: `确定删除选中的 ${articleIds.length} 篇文章吗？此操作不可恢复。`,
        okText: '确认删除',
        okType: 'danger',
        cancelText: '取消',
        onOk: async () => {
          try {
            await batchDeleteArticles(articleIds);
            message.success(`已删除 ${articleIds.length} 篇文章`);
            onArticleDeselect();
            await fetchAllSpaces();
          } catch {
            message.error('批量删除失败');
          }
        },
      });
    },
    [fetchAllSpaces, message, modal, onArticleDeselect],
  );

  const handleExportArticles = useCallback(
    async (articleIds: number[]) => {
      try {
        const articles = await Promise.all(
          articleIds.map((id) => getArticleById(id)),
        );
        const markdown = articles
          .filter(Boolean)
          .map((article: any) => {
            const body = article.contentMd || article.contentText || '';
            return `# ${article.title || `文章 ${article.id}`}\n\n${body}`;
          })
          .join('\n\n---\n\n');
        exportFile(`articles-${Date.now()}.md`, markdown);
        message.success(`已导出 ${articleIds.length} 篇文章`);
      } catch {
        message.error('导出文章失败');
      }
    },
    [message],
  );

  const handleRenameArticle = useCallback(
    (articleId: number, currentTitle: string) => {
      let title = currentTitle;

      async function rename() {
        const nextTitle = title.trim();
        if (!nextTitle) {
          message.warning('文章标题不能为空');
          return;
        }
        try {
          await updateArticle({ id: articleId, title: nextTitle });
          const currentArticleInfo = useArticleInfoStore.getState().articleInfo;
          if (currentArticleInfo?.id === articleId) {
            setArticleInfo({ ...currentArticleInfo, title: nextTitle });
          }
          message.success('标题修改成功');
          await fetchAllSpaces();
        } catch {
          message.error('修改标题失败');
        }
      }

      Modal.confirm({
        title: '修改文章标题',
        content: (
          <AutoFocusInput
            defaultValue={currentTitle}
            onChange={(e) => {
              title = e.target.value;
            }}
            onPressEnter={() => rename()}
          />
        ),
        onOk: async () => {
          await rename();
        },
      });
    },
    [fetchAllSpaces, message, setArticleInfo],
  );

  // ── 发布至公共空间 / 撤回 ──

  const handleTreeDropV2 = useCallback(
    async (info: any, sourceSpace: 'my' | 'public' = 'my') => {
      const dragNode = info.dragNode;
      const dropNode = info.node;
      if (!dragNode || !dropNode) return;

      if (dragNode.type === 'article' && info.dropToGap) {
        const source = dragNode.data as ArticleInfoType;
        if (!source.catalogId) return;

        const findCatalog = (
          catalogs: CatalogType[],
          catalogId: number,
        ): CatalogType | null => {
          for (const catalog of catalogs) {
            if (catalog.id === catalogId) return catalog;
            const found = findCatalog(catalog.children ?? [], catalogId);
            if (found) return found;
          }
          return null;
        };
        const catalog = findCatalog(
          sourceSpace === 'public' ? publicCatalogs : myCatalogs,
          source.catalogId,
        );
        if (!catalog?.articles) return;

        const articles = [...catalog.articles];
        const sourceIndex = articles.findIndex((item) => item.id === source.id);
        if (sourceIndex < 0) return;
        const relativePosition =
          info.dropPosition - Number(String(dropNode.pos).split('-').pop());

        let insertIndex: number;
        if (dropNode.type === 'article') {
          const target = dropNode.data as ArticleInfoType;
          if (
            source.id === target.id ||
            source.catalogId !== target.catalogId
          ) {
            return;
          }
          const targetIndex = articles.findIndex(
            (item) => item.id === target.id,
          );
          if (targetIndex < 0) return;
          articles.splice(sourceIndex, 1);
          const nextTargetIndex = articles.findIndex(
            (item) => item.id === target.id,
          );
          insertIndex =
            relativePosition < 0 ? nextTargetIndex : nextTargetIndex + 1;
        } else if (dropNode.type === 'catalog') {
          const target = dropNode.data as CatalogType;
          const childCatalogs = catalog.children ?? [];
          const lastChildCatalog = childCatalogs[childCatalogs.length - 1];
          if (lastChildCatalog?.id !== target.id || relativePosition <= 0) {
            return;
          }
          articles.splice(sourceIndex, 1);
          insertIndex = 0;
        } else {
          return;
        }

        articles.splice(insertIndex, 0, source);

        try {
          await reorderArticles(
            articles.map((article, index) => ({
              id: article.id as number,
              orderId: index + 1,
            })),
          );
          message.success('文章顺序已更新');
          await fetchAllSpaces();
        } catch {
          message.error('调整文章顺序失败');
        }
        return;
      }

      if (
        dragNode.type === 'article' &&
        dropNode.type === 'catalog' &&
        !info.dropToGap
      ) {
        const article = dragNode.data as any;
        const catalog = dropNode.data as CatalogType;
        if (sourceSpace === 'public' && !catalog.canDelete) {
          message.warning('只能移动到自己创建的公共目录');
          return;
        }
        try {
          await moveToCatalog(article.id, catalog.id);
          message.success('文章已移动');
          await fetchAllSpaces();
        } catch {
          message.error('移动文章失败');
        }
        return;
      }

      if (dragNode.type !== 'catalog' || dropNode.type !== 'catalog') return;
      const source = dragNode.data as CatalogType;
      const target = dropNode.data as CatalogType;
      if (source.id === target.id) return;
      if (sourceSpace === 'public' && !target.canDelete) {
        message.warning('只能移动到自己创建的公共目录');
        return;
      }
      const isDescendant = (node: CatalogType, id: number): boolean =>
        (node.children ?? []).some(
          (child) => child.id === id || isDescendant(child, id),
        );
      if (isDescendant(source, target.id)) {
        message.warning('不能把目录移动到自己的子目录中');
        return;
      }

      const cloneCatalogs = (nodes: CatalogType[]): CatalogType[] =>
        nodes.map((node) => ({
          ...node,
          children: node.children ? cloneCatalogs(node.children) : [],
        }));
      const detach = (nodes: CatalogType[], id: number): CatalogType | null => {
        const index = nodes.findIndex((node) => node.id === id);
        if (index >= 0) {
          const [removed] = nodes.splice(index, 1);
          return removed;
        }
        for (const node of nodes) {
          const removed = detach(node.children ?? [], id);
          if (removed) return removed;
        }
        return null;
      };
      const findNode = (
        nodes: CatalogType[],
        id: number,
      ): CatalogType | null => {
        for (const node of nodes) {
          if (node.id === id) return node;
          const found = findNode(node.children ?? [], id);
          if (found) return found;
        }
        return null;
      };
      const findParentChildren = (
        nodes: CatalogType[],
        childId: number,
      ): { children: CatalogType[] } | null => {
        if (nodes.some((node) => node.id === childId))
          return { children: nodes };
        for (const node of nodes) {
          const children = node.children ?? [];
          if (children.some((child) => child.id === childId))
            return { children };
          const found = findParentChildren(children, childId);
          if (found) return found;
        }
        return null;
      };
      const flattenUpdates = (
        nodes: CatalogType[],
        fatherId: number | null,
        updates: any[] = [],
      ) => {
        nodes.forEach((node, index) => {
          updates.push({ id: node.id, fatherId, orderId: index + 1 });
          flattenUpdates(node.children ?? [], node.id, updates);
        });
        return updates;
      };

      const nextTree = cloneCatalogs(
        sourceSpace === 'public' ? publicCatalogs : myCatalogs,
      );
      const moved = detach(nextTree, source.id);
      if (!moved) return;
      if (info.dropToGap) {
        const parent = findParentChildren(nextTree, target.id);
        if (!parent) return;
        const targetIndex = parent.children.findIndex(
          (node) => node.id === target.id,
        );
        const relativePosition =
          info.dropPosition - Number(String(dropNode.pos).split('-').pop());
        parent.children.splice(
          relativePosition < 0 ? targetIndex : targetIndex + 1,
          0,
          moved,
        );
      } else {
        const targetCatalog = findNode(nextTree, target.id);
        if (!targetCatalog) return;
        targetCatalog.children = targetCatalog.children ?? [];
        targetCatalog.children.push(moved);
      }
      try {
        await reorderCatalogs(flattenUpdates(nextTree, null));
        message.success('目录顺序已更新');
        await fetchAllSpaces();
      } catch {
        message.error('移动目录失败');
      }
    },
    [fetchAllSpaces, message, myCatalogs, publicCatalogs],
  );

  const handlePublishToPublic = useCallback(
    (
      resourceType: 'CATALOG' | 'ARTICLE',
      resourceId: number,
      resourceName: string,
    ) => {
      setPublishModal({ open: true, resourceType, resourceId, resourceName });
    },
    [],
  );

  // ── 复制到我的空间 ──
  const handleCopyToMySpace = useCallback(
    (resourceType: string, resourceId: number) => {
      const name =
        resourceType === 'CATALOG'
          ? `目录 #${resourceId}`
          : `文章 #${resourceId}`;
      setCopyModal({
        open: true,
        resourceType: resourceType as 'CATALOG' | 'ARTICLE',
        resourceId,
        resourceName: name,
      });
    },
    [],
  );

  const handleCopyToPrivateSubmit = useCallback(
    async (targetCatalogId: number | null) => {
      const { resourceType, resourceId } = copyModal;
      if (targetCatalogId === null) {
        message.warning('复制操作需要选择目标目录');
        return;
      }
      try {
        if (resourceType === 'CATALOG') {
          await copyCatalogToMySpace(resourceId, targetCatalogId);
        } else {
          await copyArticleToMySpace(resourceId, targetCatalogId);
        }
        message.success('已复制到我的空间');
        await fetchAllSpaces();
      } catch {
        message.error('复制失败');
      }
    },
    [copyModal, fetchAllSpaces, message],
  );

  // ── 从公共空间撤回（需选择目标目录）──
  const handleRevokeFromPublic = useCallback(
    (resourceType: string, resourceId: number) => {
      const name =
        resourceType === 'CATALOG'
          ? `目录 #${resourceId}`
          : `文章 #${resourceId}`;
      setRevokeModal({
        open: true,
        resourceType: resourceType as 'CATALOG' | 'ARTICLE',
        resourceId,
        resourceName: name,
      });
    },
    [],
  );

  const handleRevokeSubmit = useCallback(
    async (targetCatalogId: number | null) => {
      const { resourceType, resourceId } = revokeModal;
      try {
        if (resourceType === 'CATALOG') {
          await toggleCatalogPublic(
            resourceId,
            false,
            targetCatalogId ?? undefined,
          );
        } else {
          if (targetCatalogId === null) {
            message.warning('请选择撤回后的私有目录');
            return;
          }
          await toggleArticlePublic(resourceId, false, targetCatalogId);
        }
        message.success('已撤回公共状态');
        await fetchAllSpaces();
      } catch {
        message.error('撤回失败');
      }
    },
    [revokeModal, fetchAllSpaces, message],
  );

  const handleTogglePublic = useCallback(
    async (resourceType: string, resourceId: number, isPublic: boolean) => {
      try {
        if (resourceType === 'CATALOG') {
          await toggleCatalogPublic(resourceId, isPublic);
        } else {
          await toggleArticlePublic(resourceId, isPublic);
        }
        message.success(isPublic ? '已发布至公共空间' : '已撤回公共状态');
        await fetchAllSpaces();
      } catch {
        message.error('操作失败');
      }
    },
    [fetchAllSpaces, message],
  );

  const renderBatchMenu = useCallback(
    (space: 'my' | 'public') => {
      const isBatchMode = batchModeSpace === space;
      return (
        <span
          onClick={(e) => e.stopPropagation()}
          onMouseDown={(e) => e.stopPropagation()}
          onPointerDown={(e) => e.stopPropagation()}
        >
          <Dropdown
            trigger={['click']}
            menu={{
              onClick: ({ domEvent }) => {
                domEvent.stopPropagation();
              },
              items: [
                {
                  key: 'batch',
                  icon: isBatchMode ? (
                    <CloseOutlined />
                  ) : (
                    <CheckSquareOutlined />
                  ),
                  label: isBatchMode ? '退出批量' : '批量操作',
                  onClick: () => {
                    setBatchModeSpace(isBatchMode ? null : space);
                  },
                },
              ],
            }}
          >
            <Button
              type="text"
              size="small"
              icon={<MoreOutlined />}
              onClick={(e) => e.stopPropagation()}
              onMouseDown={(e) => e.stopPropagation()}
              onPointerDown={(e) => e.stopPropagation()}
            />
          </Dropdown>
        </span>
      );
    },
    [batchModeSpace],
  );

  // ── Collapse 面板 ──

  const collapseItems: CollapseProps['items'] = [
    {
      key: 'my',
      label: (
        <span
          onContextMenu={(e) => openSpaceMenu('my', e)}
          className="block w-full"
        >
          <LockOutlined className="mr-1.5" />
          我的空间（私有）
        </span>
      ),
      extra: renderBatchMenu('my'),
      children: (
        <SpaceTree
          space="my"
          catalogs={filteredMyCatalogs}
          loading={loading}
          activeArticleId={activeArticleIdForSpace('my')}
          onArticleSelect={onArticleSelect}
          onArticleDeselect={onArticleDeselect}
          onDataChange={fetchAllSpaces}
          onAddSubCatalog={handleAddSubCatalog}
          onRenameCatalog={handleRenameCatalog}
          onDeleteCatalog={handleDeleteCatalog}
          onCreateArticle={handleCreateArticle}
          onMoveArticle={handleMoveArticle}
          onRenameArticle={handleRenameArticle}
          onDeleteArticle={handleDeleteArticle}
          onBatchDeleteArticles={handleBatchDeleteArticles}
          onBatchMoveArticles={handleBatchMoveArticles}
          onExportArticles={handleExportArticles}
          onShareSetting={onShareSetting}
          onTogglePublic={handleTogglePublic}
          onPublishToPublic={handlePublishToPublic}
          onTreeDrop={(info) => handleTreeDropV2(info, 'my')}
          batchMode={batchModeSpace === 'my'}
          searchKeyword={treeSearchKeyword}
          onBlankContextMenu={(event) => openSpaceMenu('my', event)}
        />
      ),
    },
    {
      key: 'shared',
      label: (
        <span>
          <TeamOutlined className="mr-1.5" />
          与我分享
        </span>
      ),
      children: (
        <SpaceTree
          space="shared"
          catalogs={filteredSharedCatalogs}
          loading={loading}
          activeArticleId={activeArticleIdForSpace('shared')}
          onArticleSelect={onArticleSelect}
          onArticleDeselect={onArticleDeselect}
          onDataChange={fetchAllSpaces}
          onAddSubCatalog={handleAddSubCatalog}
          onCreateArticle={handleCreateArticle}
          onExportArticles={handleExportArticles}
          searchKeyword={treeSearchKeyword}
        />
      ),
    },
    {
      key: 'public',
      label: (
        <span
          onContextMenu={(e) => openSpaceMenu('public', e)}
          className="block w-full"
        >
          <GlobalOutlined className="mr-1.5" />
          公共空间
        </span>
      ),
      extra: renderBatchMenu('public'),
      children: (
        <SpaceTree
          space="public"
          catalogs={filteredPublicCatalogs}
          loading={loading}
          activeArticleId={activeArticleIdForSpace('public')}
          onArticleSelect={onArticleSelect}
          onArticleDeselect={onArticleDeselect}
          onDataChange={fetchAllSpaces}
          onAddSubCatalog={handleAddSubCatalog}
          onRenameCatalog={handleRenameCatalog}
          onDeleteCatalog={handleDeleteCatalog}
          onCreateArticle={handleCreateArticle}
          onMoveArticle={handleMoveArticle}
          onRenameArticle={handleRenameArticle}
          onDeleteArticle={handleDeleteArticle}
          onBatchDeleteArticles={handleBatchDeleteArticles}
          onBatchMoveArticles={handleBatchMoveArticles}
          onShareSetting={onShareSetting}
          onTogglePublic={handleRevokeFromPublic}
          onCopyToMySpace={handleCopyToMySpace}
          onExportArticles={handleExportArticles}
          onTreeDrop={(info) => handleTreeDropV2(info, 'public')}
          batchMode={batchModeSpace === 'public'}
          searchKeyword={treeSearchKeyword}
          onBlankContextMenu={(event) => openSpaceMenu('public', event)}
        />
      ),
    },
  ];

  return (
    <div className="flex h-full flex-col overflow-hidden">
      <div className="border-b border-[#f0f0f0] px-3 pb-2 pt-2.5">
        <div className="flex items-center gap-2">
          <Input
            allowClear
            prefix={<SearchOutlined className="text-[#8c8c8c]" />}
            placeholder="搜索目录"
            value={treeSearchKeyword}
            onChange={(event) => setTreeSearchKeyword(event.target.value)}
            className="flex-1 rounded-md"
          />
        </div>
        {normalizedTreeSearchKeyword && (
          <Typography.Text type="secondary" className="mt-1.5 block text-xs">
            已在本地匹配 {treeSearchResultCount}{' '}
            个目录/文章，点击“搜索正文”进入主页深度搜索
          </Typography.Text>
        )}
      </div>

      {/* 三大空间面板 */}
      <div className="flex-1 overflow-auto scrollbar-thin">
        <Collapse
          activeKey={activeKeys}
          onChange={(keys) =>
            setActiveKeys(
              Array.isArray(keys) ? (keys as string[]) : [keys as string],
            )
          }
          items={collapseItems}
          bordered={false}
          className="bg-transparent"
        />
      </div>

      {/* 空间面板右键菜单 */}
      {spaceMenu &&
        (spaceMenu.space === 'my' || spaceMenu.space === 'public') && (
          <>
            <div
              ref={spaceMenuRef}
              className="fixed z-[1000]"
              style={
                spaceMenuPosition
                  ? spaceMenuPosition
                  : {
                      left: spaceMenu.x,
                      top: spaceMenu.y,
                      visibility: 'hidden',
                    }
              }
              onContextMenu={(event) => event.preventDefault()}
            >
              <Menu
                className="rounded-lg border border-[#e5e7eb] shadow-lg"
                items={[
                  {
                    key: 'add-root-catalog',
                    label: '新建根目录',
                    icon: <FolderAddOutlined />,
                    disabled:
                      spaceMenu.space === 'public' &&
                      !canCreatePublicRootCatalog,
                    onClick: () => {
                      setSpaceMenu(null);
                      handleAddRootCatalog(spaceMenu.space === 'public');
                    },
                  },
                ]}
              />
            </div>
          </>
        )}

      {/* 发布至公共空间弹窗 */}
      <PublishToPublicModal
        open={publishModal.open}
        resourceType={publishModal.resourceType}
        resourceId={publishModal.resourceId}
        resourceName={publishModal.resourceName}
        onClose={() => setPublishModal((prev) => ({ ...prev, open: false }))}
        onPublished={fetchAllSpaces}
        publishApi={(id, targetCatalogId) =>
          publishModal.resourceType === 'CATALOG'
            ? publishCatalogToPublic(id, targetCatalogId)
            : publishArticleToPublic(id, targetCatalogId as number)
        }
      />

      {/* 复制到我的空间弹窗 */}
      <SelectTargetCatalogModal
        open={copyModal.open}
        title="复制到我的空间"
        instruction="请选择复制到您的私有空间中的目标目录："
        resourceName={copyModal.resourceName}
        onClose={() => setCopyModal((prev) => ({ ...prev, open: false }))}
        onSubmit={handleCopyToPrivateSubmit}
      />

      {/* 撤回公共状态弹窗 */}
      <SelectTargetCatalogModal
        open={batchMove.articleIds.length > 0}
        title="批量移动文章"
        instruction="选择这些文章要移动到的目标目录。"
        resourceName={`${batchMove.articleIds.length} 篇文章`}
        targetSpace={batchMove.sourceSpace}
        onClose={clearBatchMove}
        onSubmit={handleBatchMoveSubmit}
      />

      <SelectTargetCatalogModal
        open={revokeModal.open}
        title="撤回公共状态"
        instruction="请选择撤回后的目标目录（不选则放在根目录下）："
        resourceName={revokeModal.resourceName}
        onClose={() => setRevokeModal((prev) => ({ ...prev, open: false }))}
        onSubmit={handleRevokeSubmit}
      />
    </div>
  );
}
