import {
  ArticleInfoType,
  ArticlePermission,
  CatalogPermission,
  CatalogType,
  OperationMode,
  SpaceCatalogsDto
} from "@/types/rt.type";

// ─── 只读按钮 ───
export const readButtons = ['editor-home', 'switch-area', 'switch-page-size', 'bg-color', 'markdown-export', 'operation-mode'];

// ─── 编辑按钮 ───
export const writeButtons = [...readButtons, 'article-cover', 'markdown-text', 'save-article', 'text-paste', 'rich-text-convert', 'share-article'];

/** 是否可以编辑文章 */
export function canEditArticle(permission?: ArticlePermission | null) {
  if (!permission) return false;
  return ['READ_WRITE', 'FULL_CONTROL'].includes(permission);
}

/** 是否可以评注文章 */
export function canCommentArticle(permission?: ArticlePermission | null) {
  if (!permission) return false;
  return ['COMMENT', 'READ_WRITE', 'FULL_CONTROL'].includes(permission);
}

/** 是否可以授权文章 */
export function canGrantArticle(permission?: ArticlePermission | null) {
  if (!permission) return false;
  return permission === 'FULL_CONTROL';
}

/** 是否可以创建子目录 */
export function canCreateChildInCatalog(permission?: CatalogPermission | null) {
  if (!permission) return false;
  return ['CREATE_CHILD', 'FULL_CONTROL'].includes(permission);
}

/** 是否可以控制目录 */
export function canControlCatalog(permission?: CatalogPermission | null) {
  if (!permission) return false;
  return permission === 'FULL_CONTROL';
}

/** 为文章添加权限标志 */
export function withArticlePermissionFlags<T extends ArticleInfoType | null | undefined>(article: T): T {
  if (!article) return article;
  article.canRead = true;
  article.canComment = canCommentArticle(article.effectivePermission);
  article.canWrite = canEditArticle(article.effectivePermission);
  article.canDelete = canGrantArticle(article.effectivePermission);
  article.canGrant = canGrantArticle(article.effectivePermission);
  article.canCreateChild = false;
  return article;
}

/** 为目录添加权限标志 */
export function withCatalogPermissionFlags<T extends CatalogType | null | undefined>(catalog: T): T {
  if (!catalog) return catalog;
  catalog.canRead = true;
  catalog.canWrite = canControlCatalog(catalog.effectivePermission);
  catalog.canDelete = canControlCatalog(catalog.effectivePermission);
  catalog.canGrant = canControlCatalog(catalog.effectivePermission);
  catalog.canCreateChild = canCreateChildInCatalog(catalog.effectivePermission);
  catalog.articles?.forEach(withArticlePermissionFlags);
  catalog.children?.forEach(withCatalogPermissionFlags);
  return catalog;
}

/**目录添 为空间加权限标志 */
export function withSpaceCatalogPermissionFlags<T extends SpaceCatalogsDto | null | undefined>(spaces: T): T {
  spaces?.mySpace?.forEach(withCatalogPermissionFlags);
  spaces?.sharedWithMe?.forEach(withCatalogPermissionFlags);
  spaces?.publicSpace?.forEach(withCatalogPermissionFlags);
  return spaces;
}

/**
 * 根据文章权限信息，判断是否可适用指定的模式
 * @param operationMode 模式
 * @param articleInfo 文章信息
 */
export function canUseOperationMode(operationMode: OperationMode, articleInfo: ArticleInfoType | undefined) {
  if (!articleInfo) return false;
  // 当前用户对该文章的有效权限
  const articlePermission = articleInfo.effectivePermission;
  switch (articlePermission) {
    case 'READ_WRITE':
    case 'FULL_CONTROL':
      return true;
    case 'COMMENT':
      return ['read', 'revise'].includes(operationMode);
    case 'READ':
      return operationMode === 'read';
    default:
      return false;
  }
}

/**
 * 加载文章时，判断此时应该显示哪个模式
 * @param article
 * @param currentMode
 */
export function resolveInitialOperationMode(article: ArticleInfoType, currentMode?: OperationMode): OperationMode {
  // 当前用户对该文章的有效权限
  const articlePermission = article.effectivePermission;
  // 后端未返回有效权限，则为只读
  if (!articlePermission) return 'read';
  // 如果当前模式为编辑，且用户有编辑权限，则为编辑模式
  if (currentMode === 'edit' && article.canWrite) return 'edit';
  // 如果当前模式为评注，且用户有评注权限，则为评注模式
  if (currentMode === 'revise' && (article.canWrite || articlePermission === 'COMMENT')) return 'revise';
  // 其他情况，默认为只读模式
  return 'read';
}
