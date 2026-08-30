import {i18nText} from '@/utils/i18n';
// ─── 编辑器显示模式：split: 分屏模式；raw-text: 原始文本模式；rich-text: 富文本模式 ───
import React from "react";

export type EditorMode = 'split' | 'raw-text' | 'rich-text';

// 文章操作模式：read-仅读模式；revise-修订模式；edit-可编辑模式
export type OperationMode = 'read' | 'revise' | 'edit';

// ─── 字体大小选项 ───
export const FONT_SIZE_OPTIONS = [
  {label: i18nText("app.common.types.rt.editor.type.55495f11"), value: 'default'},
  {label: '12px', value: '12px'},
  {label: '14px', value: '14px'},
  {label: '16px', value: '16px'},
  {label: '18px', value: '18px'},
  {label: '20px', value: '20px'},
  {label: '24px', value: '24px'},
  {label: '28px', value: '28px'},
  {label: '32px', value: '32px'},
  {label: '36px', value: '36px'},
  {label: '48px', value: '48px'},
  {label: '64px', value: '64px'},
];

// ─── 字体族选项 ───
export const FONT_FAMILY_OPTIONS = [
  {label: i18nText("app.common.types.rt.editor.type.c3d73643"), value: 'default'},
  {label: i18nText("app.common.types.rt.editor.type.b97a1e3e"), value: 'Microsoft YaHei, sans-serif'},
  {label: i18nText("app.common.types.rt.editor.type.67eec7ce"), value: 'SimSun, serif'},
  {label: i18nText("app.common.types.rt.editor.type.e4cfb332"), value: 'SimHei, sans-serif'},
  {label: i18nText("app.common.types.rt.editor.type.3b8decd9"), value: 'KaiTi, serif'},
  {label: 'Arial', value: 'Arial, sans-serif'},
  {label: 'Times NR', value: 'Times New Roman, serif'},
  {label: 'Courier', value: 'Courier New, monospace'},
  {label: 'Georgia', value: 'Georgia, serif'},
  {label: 'Verdana', value: 'Verdana, sans-serif'},
];

// ─── 行高选项 ───
export const LINE_HEIGHT_OPTIONS = [
  {label: i18nText("app.common.types.rt.editor.type.55495f11"), value: 'default'},
  {label: '1.0', value: '1'},
  {label: '1.15', value: '1.15'},
  {label: '1.25', value: '1.25'},
  {label: '1.5', value: '1.5'},
  {label: '1.75', value: '1.75'},
  {label: '2.0', value: '2'},
  {label: '2.5', value: '2.5'},
  {label: '3.0', value: '3'},
];

// ─── 工具栏按钮项类型 ───
export interface ToolbarButtonItem {
  key: string; // 按钮唯一标识
  label: string; // 按钮显示文本
  order?: number; // 按钮显示顺序
  icon?: React.ReactNode; // 按钮图标
  children?: React.ReactNode; // 按钮子元素
  onClick?: () => void; // 点击事件回调
  renderCustom?: (buttonType?: 'fix' | 'float') => React.ReactNode; // 自定义渲染，若提供则完全替换默认 <Button> 渲染
}

// ─── 可清除的样式属性配置，为 true 时在粘贴时保留该属性 ───
export interface PasteStyleOptions {
  /** font-family */
  fontFamily: boolean;
  /** font-size */
  fontSize: boolean;
  /** color（文字颜色） */
  color: boolean;
  /** background-color（背景色） */
  backgroundColor: boolean;
  /** font-weight（加粗） */
  fontWeight: boolean;
  /** font-style（斜体） */
  fontStyle: boolean;
  /** text-decoration（下划线/删除线） */
  textDecoration: boolean;
  /** 自定义需要的 CSS 属性列表（额外扩展） */
  customProperties: string[];
}

/** Storage 类型，用于运行时动态修改配置 */
export interface PasteStyleStorage {
  /** 当前激活的配置（可运行时修改） */
  activeOptions: PasteStyleOptions;
  /** 动态更新配置的方法 */
  setOptions: (patch: Partial<PasteStyleOptions>) => void;
  /** 一键开启所有清除 */
  enableAll: () => void;
  /** 一键关闭所有清除 */
  disableAll: () => void;
}
