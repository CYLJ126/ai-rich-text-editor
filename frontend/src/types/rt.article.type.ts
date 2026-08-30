import type {Dayjs} from 'dayjs';
import type {TagNode} from '@/components/MyTagTree';
import type {BaseParam} from '@/types/base.type';

// 文章类型
export type ArticleType = 'note' | 'blog' | 'novel' | 'essay' | 'generic';

// 文章保存状态：0-没有变化；1-保存中；2-保存成功；3-保存失败；4-未保存
export type ArticleSaveStatus = 0 | 1 | 2 | 3 | 4;

export const articleTypeOptions = [
  { value: 'note', label: '笔记', color: '#ce2416' },
  { value: 'blog', label: '博客', color: '#f78922' },
  { value: 'novel', label: '小说', color: '#f6c114' },
  { value: 'essay', label: '散文', color: '#64bd89' },
  { value: 'generic', label: '通用类型', color: '#59aec6' },
];

// 页面显示类型：ArticleHome-编辑器首页；RichTextEditor-富文本编辑器
export type PageShowType = 'ArticleHome' | 'RichTextEditor';

// ─── 文章权限：READ-仅读；COMMENT-评注；READ_WRITE-可编辑；FULL_CONTROL-完全控制 ───
export type ArticlePermission =
  | 'READ'
  | 'COMMENT'
  | 'READ_WRITE'
  | 'FULL_CONTROL';

// ─── 目录权限：ACCESS-访问；CREATE_CHILD-创建子目录；FULL_CONTROL-完全控制 ───
export type CatalogPermission = 'ACCESS' | 'CREATE_CHILD' | 'FULL_CONTROL';
export type ArticleSpace = 'my' | 'shared' | 'public';

/**
 * 文章查询参数接口
 * 对应 Java 类 ArticleParam
 */
export interface ArticleSearchParam extends BaseParam {
  /** 查询关键字 */
  searchBingoText?: string;

  /** 作者 */
  authors?: string[];

  /** 所属目录 ID */
  catalogIds?: number[];

  /** 文章字数上限 */
  characterCountCeil?: number;

  /** 文章字数下限 */
  characterCountFloor?: number;

  /** 访问等级 TODO */
  accessLevel?: string; // 对应 ArticleAccessLevelEnum 的值

  /** 文章类型 TODO */
  articleType?: string; // 对应 ArticleTypeEnum 的值

  /** 是否语义搜索 */
  semanticSearch?: boolean;
}

// 文章被选择后提供的信息，可用于跳转到文章页面，或自定义操作
export interface ActiveSelectedInfo {
  checked?: boolean; // 是选中还是取消选中
  articleId: number; // 文章 ID
  chunkId?: string; // 文章分块 ID
  space?: ArticleSpace; // 文章所在空间
  nodeId?: string; // 节点 ID
  sectionHeading?: string; // 章节标题
  sectionHeadingId?: string; // 章节 ID
}

// 文章章节结构
export interface ArticleHeading {
  id: string;
  level: number;
  text: string;
  /** 该标题在文档中的字符偏移 */
  offset?: number;
}

// ─── 文章额外信息 ───
export interface ArticleTipInfo {
  tags?: TagNode[];
  headings?: ArticleHeading[]; // 目录/大纲
  includeSummary?: boolean; // AI 补全时是否上传总结，默认 true
  characterCount?: number; // 文章字符数
}

export interface PermissionFlags {
  canRead?: boolean;
  canComment?: boolean;
  canWrite?: boolean;
  canDelete?: boolean;
  canGrant?: boolean;
  canCreateChild?: boolean;
}

// ─── 文章属性，文章内容单独存储 ───
export interface ArticleInfoType extends ArticleTipInfo, PermissionFlags {
  id?: number | undefined; // 文章 ID
  title?: string | undefined; // 文章标题
  author?: string | undefined; // 文章作者
  summary?: string | undefined; // 文章摘要
  cover?: string | undefined; // 文章封面
  catalogId?: number | undefined; // 文章目录 ID
  orderId?: number | undefined; // 文章排序 ID
  accessLevel?: string | undefined; // 文章访问等级
  articleType?: string | undefined; // 文章类型
  wordCount?: number | undefined; // 文章字数
  contentJson?: string | undefined; // 文章内容 JSON 字符串
  contentMd?: string | undefined; // 文章内容 Markdown 字符串
  contentText?: string | undefined; // 文章内容纯文本
  createTime?: Dayjs | string | undefined; // 创建时间
  updateTime?: Dayjs | string | undefined; // 更新时间
  createBy?: string | undefined; // 创建人
  updateBy?: string | undefined; // 更新人
  rowVersion?: number | undefined; // 文章版本号
  isPublic?: boolean; // 是否公共
  effectivePermission?: ArticlePermission; // 当前用户对该文章的有效权限
  searchBingoText?: string | undefined;
}

// ─── 分享相关类型 ───
export interface ShareRecord {
  id: number;
  resourceType: 'CATALOG' | 'ARTICLE';
  resourceId: number;
  targetType?: 'USER' | 'ROLE';
  targetUser?: string;
  targetRole?: string;
  permission: ArticlePermission | CatalogPermission;
  articlePermission?: ArticlePermission;
  createBy: string;
  createTime: string;
}

export interface ShareRequest {
  resourceType: 'CATALOG' | 'ARTICLE';
  resourceId: number;
  targetType?: 'USER' | 'ROLE';
  targetUsers?: string[];
  targetRoles?: string[];
  permission: ArticlePermission | CatalogPermission;
  articlePermission?: ArticlePermission;
}

export interface ArticleHistoryVersion {
  id: number;
  articleId: number;
  versionNo: number;
  title: string;
  content?: string;
  modifiedBy: string;
  modifiedTime: string;
}
