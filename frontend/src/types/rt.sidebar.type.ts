// 右侧面板类型：catalog-目录；comments-评注；

import type {Key} from 'react';
import type {ArticleInfoType, CatalogPermission, PermissionFlags,} from '@/types/rt.type';

// 文章侧边栏面板类型：catalog-目录；comments-评注；aiChat-AI 会话；
export type ArticlePanelType =
  | 'toc'
  | 'catalog'
  | 'aiChat'
  | 'comments'
  | 'writeManage'
  | 'tagManage'
  | 'versionManage';

// 目录节点类型（对应 CatalogDto）
export interface CatalogType extends PermissionFlags {
  id: number; // 目录 ID
  name: string; // 目录名称
  fatherId: number | null; // 父目录 ID
  orderId: number; // 目录排序 ID
  description: string | null; // 目录描述
  createBy: string; // 创建人
  updateBy: string; // 更新人
  createTime: string; // 创建时间
  updateTime: string; // 更新时间
  children: CatalogType[] | null; // 子目录
  articleCount: number | null; // 文章数量
  articles: ArticleInfoType[] | null; // 文章列表
  isPublic?: boolean; // 是否公共
  effectivePermission?: CatalogPermission; // 当前用户对该节点的有效权限
}

// 目录树节点（antd Tree 需要的格式）
export interface CatalogTreeNode {
  key: Key; // 节点唯一标识
  title: string; // 节点显示文本
  isLeaf?: boolean; // 是否为叶子节点
  children?: CatalogTreeNode[]; // 子节点
  data: CatalogType | ArticleInfoType; // 节点数据
  type: 'catalog' | 'article'; // 节点类型
}

// 文章目录空间
export interface SpaceCatalogsDto {
  mySpace: CatalogType[];
  sharedWithMe: CatalogType[];
  publicSpace: CatalogType[];
}
