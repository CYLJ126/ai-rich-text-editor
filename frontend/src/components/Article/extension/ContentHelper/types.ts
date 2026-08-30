import type {JSONContent} from '@tiptap/core';

/** 内容类型 */
export type ContentLevel = 'block' | 'inline';

/** 插入位置 */
export type InsertPosition = 'before' | 'after';

export interface Range {
  /** 范围起始位置 */
  from: number;
  /** 范围结束位置 */
  to: number
}

/** 选中内容信息 */
export interface SelectionInfo {
  /** 是否有选中内容 */
  hasSelection: boolean;
  /** 选中的文本 */
  text: string;
  /** 选中的 JSON 内容 */
  content: JSONContent | null;
  /** 选中的 HTML */
  html: string;
  /** 选中内容的层级类型 */
  level: ContentLevel | null;
  /** 选中范围 */
  range: Range | null;
}

/** 块级内容信息 */
export interface BlockInfo {
  /** 块级节点类型 */
  type: string;
  /** 块级内容文本 */
  text: string;
  /** 块级内容 JSON */
  content: JSONContent;
  /** 块级内容 HTML */
  html: string;
  /** 块级节点在文档中的位置范围 */
  range: Range;
  /** 节点深度 */
  depth: number;
}

/** 行级内容信息 */
export interface InlineInfo {
  /** 行级内容文本 */
  text: string;
  /** 行级内容 JSON */
  content: JSONContent;
  /** 行级内容 HTML */
  html: string;
  /** 行级内容位置范围（相对于文档） */
  range: Range;
}
