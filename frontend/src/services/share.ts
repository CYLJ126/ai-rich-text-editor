import {request} from '@umijs/max';
import type {ShareRecord, ShareRequest, SpaceCatalogsDto} from '@/types/RichTextType';
import {
  ArticlePermission,
  CatalogPermission,
  withArticlePermissionFlags,
  withCatalogPermissionFlags,
  withSpaceCatalogPermissionFlags,
} from '@/components/Article/utitilies/permissionUtil';

const PATH_PREFIX = '/arte';

/** 分享资源给用户 */
export async function share(params: {
  resourceType: 'CATALOG' | 'ARTICLE';
  resourceId: number;
  targetType?: 'USER' | 'ROLE';
  targetUsers?: string[];
  targetRoles?: string[];
  permission: ArticlePermission | CatalogPermission;
  articlePermission?: ArticlePermission;
} | ShareRequest): Promise<void> {
  return request(`${PATH_PREFIX}/richText/share/share`, {
    method: 'POST',
    data: params,
  });
}

/** 取消分享 */
export async function unshare(
  resourceType: string,
  resourceId: number,
  targetType: 'USER' | 'ROLE',
  target: string,
): Promise<void> {
  return request(`${PATH_PREFIX}/richText/share/unshare`, {
    method: 'POST',
    data: {
      resourceType,
      resourceId,
      targetType,
      targetUser: targetType === 'USER' ? target : undefined,
      targetRole: targetType === 'ROLE' ? target : undefined,
    },
  });
}

/** 获取资源的分享列表 */
export async function listShares(
  resourceType: string,
  resourceId: number,
): Promise<ShareRecord[]> {
  return request(`${PATH_PREFIX}/richText/share/listShares`, {
    method: 'POST',
    data: { resourceType, resourceId },
  }).then((res: any) => res?.data ?? res);
}

/** 用户退出共享 */
export async function leaveShare(
  resourceType: string,
  resourceId: number,
): Promise<void> {
  return request(`${PATH_PREFIX}/richText/share/leave`, {
    method: 'POST',
    data: { resourceType, resourceId },
  });
}

/** 获取三大空间目录树 */
export async function listSpaceCatalogs(): Promise<SpaceCatalogsDto> {
  return request(`${PATH_PREFIX}/richText/catalog/listSpaceCatalogs`, {
    method: 'POST',
  }).then((res: any) => withSpaceCatalogPermissionFlags(res?.data ?? res));
}

/** 更新同目录内的文章顺序 */
export async function reorderArticles(
  list: Array<{id: number; orderId: number}>,
): Promise<boolean> {
  return request(`${PATH_PREFIX}/richText/article/reorderArticles`, {
    method: 'POST',
    data: list,
  }).then((res: any) => res?.data ?? res);
}

/** 切换目录公共状态 */
export async function toggleCatalogPublic(
  id: number,
  isPublic: boolean,
  targetCatalogId?: number,
): Promise<boolean> {
  const data: Record<string, any> = { id, isPublic };
  if (targetCatalogId !== undefined) data.targetCatalogId = targetCatalogId;
  return request(`${PATH_PREFIX}/richText/catalog/togglePublic`, {
    method: 'POST',
    data,
  }).then((res: any) => res?.data ?? res);
}

/** 切换文章公共状态 */
export async function toggleArticlePublic(
  id: number,
  isPublic: boolean,
  targetCatalogId?: number,
): Promise<boolean> {
  const data: Record<string, any> = { id, isPublic };
  if (targetCatalogId !== undefined) data.targetCatalogId = targetCatalogId;
  return request(`${PATH_PREFIX}/richText/article/togglePublic`, {
    method: 'POST',
    data,
  }).then((res: any) => res?.data ?? res);
}

/** 搜索用户 */
export async function searchUsers(keyword: string): Promise<{ userName: string; email?: string }[]> {
  return request(`${PATH_PREFIX}/richText/share/searchUsers`, {
    method: 'POST',
    data: { keyword },
  }).then((res: any) => res?.data ?? res);
}

/** 发布目录到公共空间 */
export async function publishCatalogToPublic(
  id: number,
  targetCatalogId: number | null,
): Promise<void> {
  return request(`${PATH_PREFIX}/richText/catalog/publishToPublic`, {
    method: 'POST',
    data: { id, targetCatalogId },
  });
}

/** 复制目录到我的空间 */
export async function copyCatalogToMySpace(
  id: number,
  targetCatalogId: number,
): Promise<any> {
  return request(`${PATH_PREFIX}/richText/catalog/copyToMySpace`, {
    method: 'POST',
    data: { id, targetCatalogId },
  }).then((res: any) => withCatalogPermissionFlags(res?.data ?? res));
}

/** 搜索角色 */
export async function searchRoles(keyword: string): Promise<{ roleCode: string; roleName?: string }[]> {
  return request(`${PATH_PREFIX}/richText/share/searchRoles`, {
    method: 'POST',
    data: { keyword },
  }).then((res: any) => res?.data ?? res);
}

/** 复制文章到我的空间 */
export async function copyArticleToMySpace(
  id: number,
  targetCatalogId: number,
): Promise<any> {
  return request(`${PATH_PREFIX}/richText/article/copyToMySpace`, {
    method: 'POST',
    data: { id, targetCatalogId },
  }).then((res: any) => withArticlePermissionFlags(res?.data ?? res));
}

/** 发布文章到公共空间 */
export async function publishArticleToPublic(
  id: number,
  targetCatalogId: number,
): Promise<void> {
  return request(`${PATH_PREFIX}/richText/article/publishToPublic`, {
    method: 'POST',
    data: { id, targetCatalogId },
  });
}
