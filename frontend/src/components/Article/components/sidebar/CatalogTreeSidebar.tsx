import {i18nText} from '@/utils/i18n';
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
import { Editor } from '@tiptap/core';
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
import JSZip from 'jszip';
import React, {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { hasAdminRole } from '@/access';
import { defaultExtensions, useArticleInfoStore } from '@/components';
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
import { uploadFile, uploadImage } from '@/services/upload';
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

const MARKDOWN_FILE_PATTERN = /\.(?:md|markdown)$/i;
const IMAGE_FILE_PATTERN = /\.(?:avif|bmp|gif|jpe?g|png|svg|webp)$/i;
const IMAGE_MIME_TYPES: Record<string, string> = {
  avif: 'image/avif',
  bmp: 'image/bmp',
  gif: 'image/gif',
  jpeg: 'image/jpeg',
  jpg: 'image/jpeg',
  png: 'image/png',
  svg: 'image/svg+xml',
  webp: 'image/webp',
};
const MARKDOWN_RESOURCE_PATTERN =
  /(!?\[[^\]]*]\()([^)\s]+)((?:\s+["'][^"']*["'])?\))/g;

function getImageMimeType(fileName: string) {
  const extension = fileName.split('.').pop()?.toLowerCase();
  return extension ? IMAGE_MIME_TYPES[extension] : undefined;
}

function resolveZipResourcePath(markdownPath: string, resourcePath: string) {
  const normalizedPath = resourcePath.replace(/\\/g, '/');
  if (/^(?:[a-z][a-z\d+.-]*:|#|\/)/i.test(normalizedPath)) {
    return undefined;
  }
  try {
    return decodeURIComponent(
      new URL(
        normalizedPath,
        `https://zip.local/${markdownPath}`,
      ).pathname.slice(1),
    );
  } catch {
    return undefined;
  }
}

async function replaceZipResourceUrls(
  zip: JSZip,
  markdownPath: string,
  markdown: string,
  uploadedUrls: Map<string, string>,
) {
  let result = markdown;
  const resourcePaths = Array.from(
    new Set(
      Array.from(
        markdown.matchAll(MARKDOWN_RESOURCE_PATTERN),
        (match) => match[2],
      ),
    ),
  );

  for (const resourcePath of resourcePaths) {
    const zipPath = resolveZipResourcePath(markdownPath, resourcePath);
    if (!zipPath) continue;
    const entry = zip.file(zipPath);
    if (!entry) continue;

    let uploadedUrl = uploadedUrls.get(zipPath);
    if (!uploadedUrl) {
      const blob = await entry.async('blob');
      const fileName = zipPath.split('/').pop() || 'file';
      const isImage = IMAGE_FILE_PATTERN.test(zipPath);
      const file = new File([blob], fileName, {
        type: isImage ? getImageMimeType(fileName) : blob.type,
      });
      uploadedUrl = isImage ? await uploadImage(file) : await uploadFile(file);
      uploadedUrls.set(zipPath, uploadedUrl);
    }
    result = result.split(resourcePath).join(uploadedUrl);
  }
  return result;
}

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
      message.error(i18nText("app.article.sidebar.catalogtreesidebar.4e2e8c7a")).then();
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
        message.warning(i18nText("app.article.sidebar.catalogtreesidebar.01fb9674")).then();
        return;
      }
      openTextConfirm({
        title: i18nText("app.article.sidebar.catalogtreesidebar.820a16e1"),
        placeholder: i18nText("app.article.sidebar.catalogtreesidebar.89801373"),
        onSubmit: async (name) => {
          if (!name) {
            message.warning(i18nText("app.article.sidebar.catalogtreesidebar.0211f860")).then();
            return false;
          }
          try {
            await addCatalog({ name, isPublic });
            message.success(i18nText("app.article.sidebar.catalogtreesidebar.c0034784")).then();
            await fetchAllSpaces();
            return true;
          } catch {
            message.error(i18nText("app.article.sidebar.catalogtreesidebar.a03ad4ba")).then();
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
        title: i18nText("app.article.sidebar.catalogtreesidebar.eb13ae75"),
        placeholder: i18nText("app.article.sidebar.catalogtreesidebar.2f5c8c67"),
        onSubmit: async (title) => {
          if (!title) {
            message.warning(i18nText("app.article.sidebar.catalogtreesidebar.db2ac64b")).then();
            return false;
          }
          try {
            const newArticle = await addArticle({
              title,
              isPublic,
            });
            message.success(i18nText("app.article.sidebar.catalogtreesidebar.6b1d69c4", {value0: newArticle.title})).then();
            onArticleSelect({
              articleId: newArticle.id,
            });
            await fetchAllSpaces();
            return true;
          } catch {
            message.error(i18nText("app.article.sidebar.catalogtreesidebar.d4fac406")).then();
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
        title: i18nText("app.article.sidebar.catalogtreesidebar.4da89de3"),
        placeholder: i18nText("app.article.sidebar.catalogtreesidebar.7021bd0c"),
        onSubmit: async (name) => {
          if (!name) {
            message.warning(i18nText("app.article.sidebar.catalogtreesidebar.0211f860")).then();
            return false;
          }
          try {
            await addCatalog({ name, fatherId });
            message.success(i18nText("app.article.sidebar.catalogtreesidebar.5ae3afc5")).then();
            await fetchAllSpaces();
            return true;
          } catch {
            message.error(i18nText("app.article.sidebar.catalogtreesidebar.85eef335")).then();
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
          message.warning(i18nText("app.article.sidebar.catalogtreesidebar.0211f860")).then();
          return;
        }
        try {
          await updateCatalog({ id: catalogId, name: name.trim() });
          message.success(i18nText("app.article.sidebar.catalogtreesidebar.d3e3a6f3")).then();
          await fetchAllSpaces();
        } catch {
          message.error(i18nText("app.article.sidebar.catalogtreesidebar.98a6be5b")).then();
        }
      }

      Modal.confirm({
        title: i18nText("app.article.sidebar.catalogtreesidebar.325c7985"),
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
        title: i18nText("app.article.sidebar.catalogtreesidebar.645da5fa"),
        content: i18nText("app.article.sidebar.catalogtreesidebar.544545a8", {value0: catalogName}),
        okText: i18nText("app.article.sidebar.catalogtreesidebar.a424aa2f"),
        okType: 'danger',
        cancelText: i18nText("app.article.sidebar.catalogtreesidebar.70367c34"),
        onOk: async () => {
          try {
            await deleteCatalog(catalogId);
            message.success(i18nText("app.article.sidebar.catalogtreesidebar.06b6ac39")).then();
            onArticleDeselect();
            await fetchAllSpaces();
          } catch {
            message.error(i18nText("app.article.sidebar.catalogtreesidebar.ac8cee4e")).then();
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
        title: i18nText("app.article.sidebar.catalogtreesidebar.eb13ae75"),
        placeholder: i18nText("app.article.sidebar.catalogtreesidebar.2f5c8c67"),
        onSubmit: async (title) => {
          if (!title) {
            message.warning(i18nText("app.article.sidebar.catalogtreesidebar.db2ac64b")).then();
            return false;
          }
          try {
            const newArticle = await addArticle({
              title,
              catalogId,
            });
            message.success(i18nText("app.article.sidebar.catalogtreesidebar.6b1d69c4", {value0: newArticle.title})).then();
            onArticleSelect({ articleId: newArticle.id });
            await fetchAllSpaces();
            return true;
          } catch {
            message.error(i18nText("app.article.sidebar.catalogtreesidebar.d4fac406")).then();
            return false;
          }
        },
      });
    },
    [fetchAllSpaces, message, onArticleSelect, openTextConfirm],
  );

  const saveImportedMarkdown = useCallback(
    async (catalogId: number, title: string, markdown: string) => {
      const markdownEditor = new Editor({
        content: markdown,
        contentType: 'markdown',
        extensions: defaultExtensions,
      });
      try {
        const contentJson = JSON.stringify(markdownEditor.getJSON());
        const contentText = markdownEditor.getText();
        const characterCount =
          markdownEditor.storage.characterCount.characters();
        const newArticle = await addArticle({
          title,
          catalogId,
          contentJson,
          contentMd: markdown,
          contentText,
        });
        await updateArticle({
          ...newArticle,
          contentJson,
          contentMd: markdown,
          contentText,
          characterCount,
        });
        return newArticle;
      } finally {
        markdownEditor.destroy();
      }
    },
    [],
  );

  const handleImportMarkdown = useCallback(
    (catalogId: number) => {
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = '.md,.markdown,text/markdown';
      input.onchange = async () => {
        const file = input.files?.[0];
        if (!file) return;

        try {
          const markdown = await file.text();
          const title =
            file.name.replace(MARKDOWN_FILE_PATTERN, '') || i18nText("app.article.sidebar.catalogtreesidebar.f8e63045");
          const newArticle = await saveImportedMarkdown(
            catalogId,
            title,
            markdown,
          );
          message.success(i18nText("app.article.sidebar.catalogtreesidebar.792ec85d", {value0: title}));
          await fetchAllSpaces();
          onArticleSelect({ articleId: newArticle.id });
        } catch {
          message.error(i18nText("app.article.sidebar.catalogtreesidebar.b08df0b3"));
        }
      };
      input.click();
    },
    [fetchAllSpaces, message, onArticleSelect, saveImportedMarkdown],
  );

  const handleImportMarkdownZip = useCallback(
    (catalogId: number) => {
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = '.zip,application/zip,application/x-zip-compressed';
      input.onchange = async () => {
        const file = input.files?.[0];
        if (!file) return;

        const messageKey = 'markdown-zip-import';
        let importedCount = 0;
        message.loading({
          key: messageKey,
          content: i18nText("app.article.sidebar.catalogtreesidebar.3a8a9393"),
          duration: 0,
        });
        try {
          const zip = await JSZip.loadAsync(file);
          const markdownEntries = Object.values(zip.files)
            .filter(
              (entry) =>
                !entry.dir &&
                MARKDOWN_FILE_PATTERN.test(entry.name) &&
                !entry.name.startsWith('__MACOSX/'),
            )
            .sort((first, second) => first.name.localeCompare(second.name));
          if (markdownEntries.length === 0) {
            message.warning({
              key: messageKey,
              content: i18nText("app.article.sidebar.catalogtreesidebar.3ad589f1"),
            });
            return;
          }

          const catalogIds = new Map<string, number>([['', catalogId]]);
          const uploadedUrls = new Map<string, string>();
          for (const entry of markdownEntries) {
            const pathParts = entry.name.split('/').filter(Boolean);
            const fileName = pathParts.pop() as string;
            let parentId = catalogId;
            let catalogPath = '';
            for (const folderName of pathParts) {
              catalogPath = catalogPath
                ? `${catalogPath}/${folderName}`
                : folderName;
              let nextCatalogId = catalogIds.get(catalogPath);
              if (!nextCatalogId) {
                const catalog = await addCatalog({
                  name: folderName,
                  fatherId: parentId,
                });
                nextCatalogId = catalog.id;
                catalogIds.set(catalogPath, nextCatalogId);
              }
              parentId = nextCatalogId;
            }

            const sourceMarkdown = await entry.async('string');
            const markdown = await replaceZipResourceUrls(
              zip,
              entry.name,
              sourceMarkdown,
              uploadedUrls,
            );
            const title =
              fileName.replace(MARKDOWN_FILE_PATTERN, '') || i18nText("app.article.sidebar.catalogtreesidebar.f8e63045");
            await saveImportedMarkdown(parentId, title, markdown);
            importedCount += 1;
          }

          await fetchAllSpaces();
          message.success({
            key: messageKey,
            content: i18nText("app.article.sidebar.catalogtreesidebar.18c9d0ce", {value0: importedCount}),
          });
        } catch {
          await fetchAllSpaces();
          message.error({
            key: messageKey,
            content:
              importedCount > 0
                ? i18nText("app.article.sidebar.catalogtreesidebar.c9315cff", {value0: importedCount})
                : i18nText("app.article.sidebar.catalogtreesidebar.4b407ec6"),
          });
        }
      };
      input.click();
    },
    [fetchAllSpaces, message, saveImportedMarkdown],
  );

  const handleDeleteArticle = useCallback(
    (articleId: number, articleTitle: string) => {
      modal.confirm({
        title: i18nText("app.article.sidebar.catalogtreesidebar.1ee37afb"),
        content: i18nText("app.article.sidebar.catalogtreesidebar.6d740c66", {value0: articleTitle}),
        okText: i18nText("app.article.sidebar.catalogtreesidebar.a424aa2f"),
        okType: 'danger',
        cancelText: i18nText("app.article.sidebar.catalogtreesidebar.70367c34"),
        onOk: async () => {
          try {
            await deleteArticle(articleId);
            message.success(i18nText("app.article.sidebar.catalogtreesidebar.9a21ac76"));
            onArticleDeselect();
            await fetchAllSpaces();
          } catch {
            message.error(i18nText("app.article.sidebar.catalogtreesidebar.ebc0b300"));
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
        title: i18nText("app.article.sidebar.catalogtreesidebar.2b756ee2"),
        content: (
          <Select
            className="w-full"
            placeholder={i18nText("app.article.sidebar.catalogtreesidebar.f978a3e4")}
            options={catalogOptions}
            onChange={(val) => {
              targetCatalogId = val;
            }}
          />
        ),
        onOk: async () => {
          if (targetCatalogId === null) {
            message.warning(i18nText("app.article.sidebar.catalogtreesidebar.240fb806"));
            return;
          }
          try {
            await moveToCatalog(articleId, targetCatalogId);
            message.success(i18nText("app.article.sidebar.catalogtreesidebar.419b3908"));
            await fetchAllSpaces();
          } catch {
            message.error(i18nText("app.article.sidebar.catalogtreesidebar.f731ddbc"));
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
        message.warning(i18nText("app.article.sidebar.catalogtreesidebar.240fb806"));
        return;
      }
      try {
        await batchMoveToCatalogByIds(batchMove.articleIds, targetCatalogId);
        message.success(i18nText("app.article.sidebar.catalogtreesidebar.afb24336", {value0: batchMove.articleIds.length}));
        clearBatchMove();
        await fetchAllSpaces();
      } catch {
        message.error(i18nText("app.article.sidebar.catalogtreesidebar.3828305e"));
      }
    },
    [batchMove.articleIds, clearBatchMove, fetchAllSpaces, message],
  );

  const handleBatchDeleteArticles = useCallback(
    (articleIds: number[]) => {
      modal.confirm({
        title: i18nText("app.article.sidebar.catalogtreesidebar.1b387b9e"),
        content: i18nText("app.article.sidebar.catalogtreesidebar.a57d8df5", {value0: articleIds.length}),
        okText: i18nText("app.article.sidebar.catalogtreesidebar.a424aa2f"),
        okType: 'danger',
        cancelText: i18nText("app.article.sidebar.catalogtreesidebar.70367c34"),
        onOk: async () => {
          try {
            await batchDeleteArticles(articleIds);
            message.success(i18nText("app.article.sidebar.catalogtreesidebar.7f6d4b42", {value0: articleIds.length}));
            onArticleDeselect();
            await fetchAllSpaces();
          } catch {
            message.error(i18nText("app.article.sidebar.catalogtreesidebar.7ab460dd"));
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
            return `# ${article.title || i18nText("app.article.sidebar.catalogtreesidebar.27ff4125", {value0: article.id})}\n\n${body}`;
          })
          .join('\n\n---\n\n');
        exportFile(`articles-${Date.now()}.md`, markdown);
        message.success(i18nText("app.article.sidebar.catalogtreesidebar.be63f4fa", {value0: articleIds.length}));
      } catch {
        message.error(i18nText("app.article.sidebar.catalogtreesidebar.fb9c9ede"));
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
          message.warning(i18nText("app.article.sidebar.catalogtreesidebar.db2ac64b"));
          return;
        }
        try {
          await updateArticle({ id: articleId, title: nextTitle });
          const currentArticleInfo = useArticleInfoStore.getState().articleInfo;
          if (currentArticleInfo?.id === articleId) {
            setArticleInfo({ ...currentArticleInfo, title: nextTitle });
          }
          message.success(i18nText("app.article.sidebar.catalogtreesidebar.5ca16f2e"));
          await fetchAllSpaces();
        } catch {
          message.error(i18nText("app.article.sidebar.catalogtreesidebar.bb1317b8"));
        }
      }

      Modal.confirm({
        title: i18nText("app.article.sidebar.catalogtreesidebar.0827a64a"),
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
          message.success(i18nText("app.article.sidebar.catalogtreesidebar.c7b1216b"));
          await fetchAllSpaces();
        } catch {
          message.error(i18nText("app.article.sidebar.catalogtreesidebar.d413cdcc"));
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
          message.warning(i18nText("app.article.sidebar.catalogtreesidebar.98bcffda"));
          return;
        }
        try {
          await moveToCatalog(article.id, catalog.id);
          message.success(i18nText("app.article.sidebar.catalogtreesidebar.419b3908"));
          await fetchAllSpaces();
        } catch {
          message.error(i18nText("app.article.sidebar.catalogtreesidebar.f731ddbc"));
        }
        return;
      }

      if (dragNode.type !== 'catalog' || dropNode.type !== 'catalog') return;
      const source = dragNode.data as CatalogType;
      const target = dropNode.data as CatalogType;
      if (source.id === target.id) return;
      if (sourceSpace === 'public' && !target.canDelete) {
        message.warning(i18nText("app.article.sidebar.catalogtreesidebar.98bcffda"));
        return;
      }
      const isDescendant = (node: CatalogType, id: number): boolean =>
        (node.children ?? []).some(
          (child) => child.id === id || isDescendant(child, id),
        );
      if (isDescendant(source, target.id)) {
        message.warning(i18nText("app.article.sidebar.catalogtreesidebar.dac7b73b"));
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
        message.success(i18nText("app.article.sidebar.catalogtreesidebar.29f43e74"));
        await fetchAllSpaces();
      } catch {
        message.error(i18nText("app.article.sidebar.catalogtreesidebar.11b4a1d0"));
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
          ? i18nText("app.article.sidebar.catalogtreesidebar.edc62e31", {value0: resourceId})
          : i18nText("app.article.sidebar.catalogtreesidebar.23142b3c", {value0: resourceId});
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
        message.warning(i18nText("app.article.sidebar.catalogtreesidebar.ab9f1b64"));
        return;
      }
      try {
        if (resourceType === 'CATALOG') {
          await copyCatalogToMySpace(resourceId, targetCatalogId);
        } else {
          await copyArticleToMySpace(resourceId, targetCatalogId);
        }
        message.success(i18nText("app.article.sidebar.catalogtreesidebar.5febc6e1"));
        await fetchAllSpaces();
      } catch {
        message.error(i18nText("app.article.sidebar.catalogtreesidebar.7c224c9b"));
      }
    },
    [copyModal, fetchAllSpaces, message],
  );

  // ── 从公共空间撤回（需选择目标目录）──
  const handleRevokeFromPublic = useCallback(
    (resourceType: string, resourceId: number) => {
      const name =
        resourceType === 'CATALOG'
          ? i18nText("app.article.sidebar.catalogtreesidebar.edc62e31", {value0: resourceId})
          : i18nText("app.article.sidebar.catalogtreesidebar.23142b3c", {value0: resourceId});
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
            message.warning(i18nText("app.article.sidebar.catalogtreesidebar.f2eb2c96"));
            return;
          }
          await toggleArticlePublic(resourceId, false, targetCatalogId);
        }
        message.success(i18nText("app.article.sidebar.catalogtreesidebar.80381937"));
        await fetchAllSpaces();
      } catch {
        message.error(i18nText("app.article.sidebar.catalogtreesidebar.a8739d38"));
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
        message.success(isPublic ? i18nText("app.article.sidebar.catalogtreesidebar.e288954c") : i18nText("app.article.sidebar.catalogtreesidebar.80381937"));
        await fetchAllSpaces();
      } catch {
        message.error(i18nText("app.article.sidebar.catalogtreesidebar.2c103490"));
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
                  label: isBatchMode ? i18nText("app.article.sidebar.catalogtreesidebar.347cd9d3") : i18nText("app.article.sidebar.catalogtreesidebar.fbb37314"),
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
          {i18nText("app.article.sidebar.catalogtreesidebar.eccd2f1e")}
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
          onImportMarkdown={handleImportMarkdown}
          onImportMarkdownZip={handleImportMarkdownZip}
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
          {i18nText("app.article.sidebar.catalogtreesidebar.1250a36b")}
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
          onImportMarkdown={handleImportMarkdown}
          onImportMarkdownZip={handleImportMarkdownZip}
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
          {i18nText("app.article.sidebar.catalogtreesidebar.17318c0b")}
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
          onImportMarkdown={handleImportMarkdown}
          onImportMarkdownZip={handleImportMarkdownZip}
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
            placeholder={i18nText("app.article.sidebar.catalogtreesidebar.65fe10a7")}
            value={treeSearchKeyword}
            onChange={(event) => setTreeSearchKeyword(event.target.value)}
            className="flex-1 rounded-md"
          />
        </div>
        {normalizedTreeSearchKeyword && (
          <Typography.Text type="secondary" className="mt-1.5 block text-xs">
            {i18nText("app.article.sidebar.catalogtreesidebar.183b962e")} {treeSearchResultCount}{' '}
            {i18nText("app.article.sidebar.catalogtreesidebar.55e850ef")}
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
                    label: i18nText("app.article.sidebar.catalogtreesidebar.820a16e1"),
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
        title={i18nText("app.article.sidebar.catalogtreesidebar.ed355c6e")}
        instruction={i18nText("app.article.sidebar.catalogtreesidebar.9c31861b")}
        resourceName={copyModal.resourceName}
        onClose={() => setCopyModal((prev) => ({ ...prev, open: false }))}
        onSubmit={handleCopyToPrivateSubmit}
      />

      {/* 撤回公共状态弹窗 */}
      <SelectTargetCatalogModal
        open={batchMove.articleIds.length > 0}
        title={i18nText("app.article.sidebar.catalogtreesidebar.c896d7d9")}
        instruction={i18nText("app.article.sidebar.catalogtreesidebar.180cab67")}
        resourceName={i18nText("app.article.sidebar.catalogtreesidebar.33a7e8cc", {value0: batchMove.articleIds.length})}
        targetSpace={batchMove.sourceSpace}
        onClose={clearBatchMove}
        onSubmit={handleBatchMoveSubmit}
      />

      <SelectTargetCatalogModal
        open={revokeModal.open}
        title={i18nText("app.article.sidebar.catalogtreesidebar.b93805da")}
        instruction={i18nText("app.article.sidebar.catalogtreesidebar.f424c6c4")}
        resourceName={revokeModal.resourceName}
        onClose={() => setRevokeModal((prev) => ({ ...prev, open: false }))}
        onSubmit={handleRevokeSubmit}
      />
    </div>
  );
}
