import React from "react";
import {BaseParam} from "@/types";

export interface RightSiderHandleRef {
  refresh: () => Promise<void>;
  getList: () => RightSiderItem[];
  setList: (list: RightSiderItem[] | ((prev: RightSiderItem[]) => RightSiderItem[])) => void;
}

// 侧边栏属性
export interface RightSiderProps {
  header?: React.ReactNode;
  searchInputKey?: string;
  virtualItem?: RightSiderItem;
  virtualTip?: string; // 虚拟区域标题
  items?: RightSiderItem[];
  size?: number;
  loadFunc?: <T extends BaseParam>(param: T) => Promise<LoadFuncResult>;
  activeKey?: number | string | undefined;
  onItemClick?: (item: RightSiderItem) => void;
  onItemDoubleClick?: (item: RightSiderItem) => void;
  emptyRender?: React.ReactNode;
}

export interface LoadFuncResult {
  total: number;
  current?: number;
  size?: number;
  records?: RightSiderItem[];
}

// 侧边栏列表项属性
export interface RightSiderItem {
  origin?: any; // 原始数据
  id: number | string;
  key: number | string;
  title: string;
  extraRender?: (item: RightSiderItem) => React.ReactNode;
  icon?: string;
  abstractInfo?: string;
  backgroundColor?: string; // 背景颜色
  pinFlag?: boolean; // 是否置顶
  disabled?: boolean; // 是否禁用
  operations?: RightSiderItemOption[];
  itemRender?: () => React.ReactNode;
}

// 侧边栏列表项操作属性
export interface RightSiderItemOption {
  key: string;
  label: string;
  order?: number;
  type?: string; // divider 表示分隔线
  isDanger?: boolean;
  icon?: React.ReactNode;
  onClick?: (item: RightSiderItem) => void;
  onPin?: (item: RightSiderItem) => void; // 置顶/取消置顶
  optionRender?: () => React.ReactNode;
}
