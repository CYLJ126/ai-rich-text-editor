import {i18nText} from '@/utils/i18n';
import MindElixir, {
  DARK_THEME,
  type MindElixirData,
  type MindElixirInstance,
  SIDE,
  THEME,
} from 'mind-elixir';
import { en, zh_CN } from 'mind-elixir/i18n';
import 'mind-elixir/style.css';
import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';

export interface MindMapEditorHandle {
  addChild: () => Promise<void>;
  addSibling: () => Promise<void>;
  editSelected: () => Promise<void>;
  exportData: () => MindElixirData;
  exportSvg: () => Blob;
  redo: () => void;
  removeSelected: () => Promise<void>;
  undo: () => void;
}

interface MindMapEditorProps {
  data: MindElixirData;
  isDark: boolean;
}

function getLocale() {
  const language = navigator.languages?.[0] ?? navigator.language ?? 'zh-CN';
  return language.toLowerCase().startsWith('zh') ? zh_CN : en;
}

export const MindMapEditor = forwardRef<
  MindMapEditorHandle,
  MindMapEditorProps
>(({ data, isDark }, ref) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const instanceRef = useRef<MindElixirInstance | undefined>(undefined);

  useImperativeHandle(
    ref,
    () => ({
      addChild: async () => {
        if (!instanceRef.current) throw new Error(i18nText("app.article.canvas.mindmapeditor.978b1c69"));
        const mind = instanceRef.current;
        await mind.addChild(mind.currentNode ?? mind.findEle(mind.nodeData.id));
      },
      addSibling: async () => {
        if (!instanceRef.current) throw new Error(i18nText("app.article.canvas.mindmapeditor.978b1c69"));
        const mind = instanceRef.current;
        const current = mind.currentNode;
        const root = mind.findEle(mind.nodeData.id);
        if (!current || current === root) {
          await mind.addChild(root);
          return;
        }
        await mind.insertSibling('after', current);
      },
      editSelected: async () => {
        if (!instanceRef.current) throw new Error(i18nText("app.article.canvas.mindmapeditor.978b1c69"));
        const mind = instanceRef.current;
        await mind.beginEdit(
          mind.currentNode ?? mind.findEle(mind.nodeData.id),
        );
      },
      exportData: () => {
        if (!instanceRef.current) throw new Error(i18nText("app.article.canvas.mindmapeditor.978b1c69"));
        return instanceRef.current.getData();
      },
      exportSvg: () => {
        if (!instanceRef.current) throw new Error(i18nText("app.article.canvas.mindmapeditor.978b1c69"));
        const mind = instanceRef.current;
        if (isDark) mind.changeTheme(THEME);
        const svg = mind.exportSvg(false);
        if (isDark) mind.changeTheme(DARK_THEME);
        return svg;
      },
      redo: () => instanceRef.current?.redo(),
      removeSelected: async () => {
        if (!instanceRef.current) throw new Error(i18nText("app.article.canvas.mindmapeditor.978b1c69"));
        const mind = instanceRef.current;
        const current = mind.currentNode;
        if (!current || current === mind.findEle(mind.nodeData.id)) {
          throw new Error(i18nText("app.article.canvas.mindmapeditor.4013c274"));
        }
        await mind.removeNodes([current]);
      },
      undo: () => instanceRef.current?.undo(),
    }),
    [isDark],
  );

  useEffect(() => {
    if (!containerRef.current) return;

    const mind = new MindElixir({
      el: containerRef.current,
      direction: SIDE,
      editable: true,
      keypress: true,
      toolBar: true,
      contextMenu: { locale: getLocale() },
      overflowHidden: false,
      theme: isDark ? DARK_THEME : THEME,
    });
    const error = mind.init({ ...data, theme: isDark ? DARK_THEME : THEME });
    if (error) throw error;
    instanceRef.current = mind;
    const frame = window.requestAnimationFrame(() => mind.scaleFit());

    return () => {
      window.cancelAnimationFrame(frame);
      mind.destroy();
      instanceRef.current = undefined;
    };
  }, [data, isDark]);

  return <div ref={containerRef} className="size-full overflow-hidden" />;
});

MindMapEditor.displayName = 'MindMapEditor';
