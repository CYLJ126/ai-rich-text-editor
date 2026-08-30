import {create} from 'zustand';
import {ArticlePanelType, OperationMode} from "@/types/rt.type";
import {Editor} from "@tiptap/core";
import type {FloatingResultState} from "@/components/Article/extension";

// ---------- 状态类型 ----------
interface EditorState {
  editor: Editor | undefined; // 编辑器实例
  editorStyle: any; // 编辑器样式配置
  activePanel: ArticlePanelType | undefined; // 当前激活的面板
  editAreaHeight: number; // 编辑区域高度
  sizes: number[]; // 编辑区域与侧边栏比例
  viewSize: number; // 页面视图大小，只在单屏模式（即非 split 下）生效
  operationMode: OperationMode; // 操作模式：打开已有文章默认阅读，新建文章打开后默认编辑
  floatingState: FloatingResultState; // 浮动结果展示状态

  // 动作
  setEditor: (editor: Editor | undefined) => void; // 设置编辑器实例
  setEditorStyle: (editorStyle: any) => void; // 设置编辑器样式配置
  setActivePanel: (activePanel: ArticlePanelType | undefined) => void; // 设置当前激活的面板
  setOperationMode: (mode: OperationMode | undefined) => void; // 设置操作模式
  setEditAreaHeight: (editAreaHeight: number) => void; // 设置编辑区域高度
  setSizes: (sizes: number[]) => void; // 设置左右面板尺寸
  setViewSize: (viewSize: number) => void; // 设置页面视图大小
  setFloatingState: (floatingState: FloatingResultState | undefined) => void; // 设置浮动结果展示状态
}

export const useEditorStore = create<EditorState>((set, get) => ({
  editor: undefined,
  editorStyle: {backgroundColor: '#FFFFFF'},
  activePanel: 'catalog',
  operationMode: 'edit',
  editAreaHeight: window.innerHeight - 118,
  sizes: [80, 20],
  viewSize: 0,
  floatingState: {
    visible: false,
    content: '',
    position: null,
    selectionFrom: 0,
    selectionTo: 0,
  },

  // 动作
  setEditor: (editor: Editor | undefined) => {
    set(() => ({editor}));
  },
  setEditorStyle: (editorStyle: any) => {
    set(() => ({editorStyle}));
  },
  setActivePanel: (panel: ArticlePanelType | undefined) => {
    set(() => ({activePanel: panel}));
  },
  setOperationMode: (mode: OperationMode | undefined) => {
    set(() => ({operationMode: mode}));
  },
  setEditAreaHeight: (editAreaHeight: number) => {
    set(() => ({editAreaHeight}));
  },
  setSizes: (sizes: number[]) => {
    set(() => ({sizes}));
  },
  setViewSize: (viewSize: number) => {
    set(() => ({viewSize}));
  },
  setFloatingState: (floatingState: FloatingResultState | undefined) => {
    set(() => ({floatingState}));
  },
}));
