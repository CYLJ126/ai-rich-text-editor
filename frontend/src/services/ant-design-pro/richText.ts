import {jsonPost} from './api';
import {withArticlePermissionFlags, withCatalogPermissionFlags} from '@/components/Article/utitilies/permissionUtil';
import {ArticleHistoryVersion} from "@/types/rt.article.type";

/**  ----------------- ArticleController start ----------------- */

/**
 * 获取文章内容
 *
 * @param id 文章 ID
 */
export async function getArticleById(id: number) {
  return jsonPost('/richText/article/getArticleById', {id})
    .then(withArticlePermissionFlags);
}

/** 获取编辑器所需的文章内容 */
export async function getEditorArticleById(id: number) {
  return jsonPost('/richText/article/getEditorArticleById', {id})
    .then(withArticlePermissionFlags);
}

/**
 * 创建新文章
 *
 * @param param 文章信息
 */
export async function addArticle(param: any) {
  return jsonPost('/richText/article/addArticle', param)
    .then(withArticlePermissionFlags);
}

/**
 * 更新文章内容
 *
 * @param param 文章内容
 */
export async function updateArticle(param: any) {
  return jsonPost('/richText/article/updateArticle', param);
}

/**
 * 更新文章批注
 * @param param
 */
export async function updateArticleCommentMarks(param: {
  id: number;
  contentJson: string;
}) {
  return jsonPost('/richText/article/updateArticleCommentMarks', param);
}

/**
 * 删除文章
 *
 * @param id 文章 ID
 */
export async function deleteArticle(id: number) {
  return jsonPost('/richText/article/deleteArticle', {id});
}

/**
 * 批量删除文章
 *
 * @param ids 文章 ID 列表
 */
export async function batchDeleteArticles(ids: number[]) {
  return jsonPost('/richText/article/batchDeleteArticles', ids);
}

/**
 * 分页查询文章列表
 *
 * @param param 查询参数（含分页信息）
 */
export async function listArticles(param: any) {
  return jsonPost('/richText/article/listArticles', param);
}

/**
 * 获取最近访问的文章列表
 */
export async function listRecentAccessibleArticles() {
  return jsonPost('/richText/article/listRecentAccessibleArticles', {})
    .then((articles: any[]) => articles?.map(withArticlePermissionFlags) ?? articles);
}

/**
 * 搜索文章 ES 分块
 * @param param
 */
export async function searchArticlesChunks(param: any) {
  return jsonPost('/richText/article/searchArticlesChunks', param);
}

/**
 * 获取指定目录下的文章列表
 *
 * @param catalogId 目录 ID
 */
export async function listByCatalog(catalogId: number) {
  return jsonPost('/richText/article/listByCatalog', {catalogId})
    .then((articles: any[]) => articles?.map(withArticlePermissionFlags) ?? articles);
}

/**
 * 移动文章到指定目录
 *
 * @param id 文章 ID
 * @param catalogId 目标目录 ID
 */
export async function moveToCatalog(id: number, catalogId: number) {
  return jsonPost('/richText/article/moveToCatalog', {id, catalogId});
}

/**
 * 批量移动文章到指定目录
 *
 * @param articleIds 文章 ID 列表
 * @param catalogId 目标目录 ID
 */
export async function batchMoveToCatalogByIds(articleIds: number[], catalogId: number) {
  return jsonPost('/richText/article/batchMoveToCatalogByIds', {articleIds, catalogId});
}

/**
 * 保存文章总结/摘要
 * @param id 文章 ID
 * @param summary 摘要内容
 */
export async function saveArticleSummary(id: number, summary: string) {
  return jsonPost('/richText/article/saveArticleSummary', {id, summary});
}

/**
 * 获取文章历史版本
 * @param id 文章 ID
 */
export async function listArticleHistory(id: number) {
  return jsonPost('/richText/article/listHistory', {id}) as Promise<ArticleHistoryVersion[]>;
}

/**
 * 获取指定文章历史版本明细
 * @param id 历史版本 ID
 */
export async function getArticleHistoryById(id: number) {
  return jsonPost('/richText/article/getHistoryById', {id}) as Promise<ArticleHistoryVersion>;
}

/**  ----------------- ArticleController end ----------------- */

/**  ----------------- ArticleCommentController start ----------------- */

export async function listCommentThreads(articleId: number) {
  return jsonPost('/richText/comment/listThreads', {articleId});
}

export async function createCommentThread(param: {
  articleId: number;
  threadId?: string;
  content: string;
}) {
  return jsonPost('/richText/comment/createThread', param);
}

export async function addComment(param: {
  articleId: number;
  threadId: string;
  content: string;
}) {
  return jsonPost('/richText/comment/addComment', param);
}

export async function updateComment(param: {
  articleId: number;
  threadId: string;
  commentId: string;
  content: string;
}) {
  return jsonPost('/richText/comment/updateComment', param);
}

export async function deleteComment(param: {
  articleId: number;
  threadId: string;
  commentId: string;
  deleteContent?: boolean;
}) {
  return jsonPost('/richText/comment/deleteComment', param);
}

export async function resolveCommentThread(articleId: number, threadId: string) {
  return jsonPost('/richText/comment/resolveThread', {articleId, threadId});
}

export async function unresolveCommentThread(
  articleId: number,
  threadId: string,
) {
  return jsonPost('/richText/comment/unresolveThread', {articleId, threadId});
}

export async function deleteCommentThread(articleId: number, threadId: string) {
  return jsonPost('/richText/comment/deleteThread', {articleId, threadId});
}

/**  ----------------- ArticleCommentController end ----------------- */

/**  ----------------- CatalogController start ----------------- */

/**
 * 获取目录树
 *
 * @param param 查询参数（可选 createBy）
 */
export async function listCatalogs(param?: any) {
  return jsonPost('/richText/catalog/listCatalogs', param ?? {})
    .then((catalogs: any[]) => catalogs?.map(withCatalogPermissionFlags) ?? catalogs);
}

/**
 * 新增目录节点
 *
 * @param param 目录信息（name 必填，fatherId 可选）
 */
export async function addCatalog(param: any) {
  return jsonPost('/richText/catalog/addCatalog', param);
}

/**
 * 更新目录节点（重命名等）
 *
 * @param param 目录信息（id 必填）
 */
export async function updateCatalog(param: any) {
  return jsonPost('/richText/catalog/updateCatalog', param);
}

/**
 * 删除目录节点（递归删除所有子目录）
 *
 * @param id 目录 ID
 */
export async function deleteCatalog(id: number) {
  return jsonPost('/richText/catalog/deleteCatalog', {id});
}

/**
 * 拖拽排序/移动层级
 *
 * @param list 排序后的目录列表
 */
export async function reorderCatalogs(list: any[]) {
  return jsonPost('/richText/catalog/reorderCatalogs', list);
}

/**  ----------------- CatalogController end ----------------- */
