import {useEffect, useMemo, useRef, useState} from 'react';
import {Loader2Icon} from 'lucide-react';
import type {MindElixirData} from 'mind-elixir';
import {useThemeContext} from '@/contexts/ThemeContext';
import {getI18nLocale, i18nText} from '@/utils/i18n';
import type {CanvasType} from './canvas';
import {MindMapEditor, type MindMapEditorHandle} from './mindmap-editor';

export function CanvasAiPreview({type, source, onReady, onError}: {
  type: CanvasType; source: string; onReady: (svg: Blob) => void; onError: (message: string) => void;
}) {
  const {isDark} = useThemeContext();
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const mindRef = useRef<MindMapEditorHandle>(null);
  const [previewUrl, setPreviewUrl] = useState('');
  const data = useMemo(() => type === 'mindmap' ? JSON.parse(source) as MindElixirData : undefined, [source, type]);
  const drawioUrl = `/drawio/?embed=1&ui=atlas&spin=1&proto=json&readOnly=1&noSaveBtn=1&noExitBtn=1&dark=${isDark ? 1 : 0}&lang=${getI18nLocale().startsWith('zh') ? 'zh' : 'en'}`;

  useEffect(() => {
    if (type !== 'mindmap') return;
    const frame = requestAnimationFrame(() => {
      try {
        if (mindRef.current) onReady(mindRef.current.exportSvg());
      } catch (error) { onError((error as Error).message); }
    });
    return () => cancelAnimationFrame(frame);
  }, [source, type, isDark, onReady, onError]);

  useEffect(() => {
    if (type !== 'drawio') return;
    const origin = window.location.origin;
    let active = true;
    let objectUrl = '';
    const post = (payload: object) => iframeRef.current?.contentWindow?.postMessage(JSON.stringify(payload), origin);
    const receive = async (event: MessageEvent) => {
      if (event.origin !== origin || event.source !== iframeRef.current?.contentWindow) return;
      let message;
      try { message = typeof event.data === 'string' ? JSON.parse(event.data) : event.data; } catch { return; }
      if (message?.event === 'init') post({action: 'load', xml: source});
      if (message?.event === 'load') post({action: 'export', format: 'svg', xml: source, theme: 'auto', background: 'transparent'});
      if (message?.event === 'export' && typeof message.data === 'string') {
        try {
          const svg = message.data.startsWith('data:')
            ? await fetch(message.data).then(response => response.blob())
            : new Blob([message.data], {type: 'image/svg+xml'});
          if (active) {
            if (objectUrl) URL.revokeObjectURL(objectUrl);
            objectUrl = URL.createObjectURL(svg);
            setPreviewUrl(objectUrl);
            onReady(svg);
          }
        } catch (error) { if (active) onError((error as Error).message); }
      }
      if (message?.event === 'error') onError(message.message || i18nText('app.article.canvas.ai.invalid'));
    };
    window.addEventListener('message', receive);
    return () => { active = false; window.removeEventListener('message', receive); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [source, type, drawioUrl, onReady, onError]);

  return data ? <MindMapEditor ref={mindRef} data={data} isDark={isDark} editable={false} />
    : <div className="relative size-full">
      <iframe ref={iframeRef} src={drawioUrl} tabIndex={-1} aria-hidden title={i18nText('app.article.canvas.ai.preview')} className="pointer-events-none absolute inset-0 size-full border-0 opacity-0" />
      {previewUrl ? <img src={previewUrl} alt={i18nText('app.article.canvas.ai.preview')} className="relative size-full object-contain p-4" />
        : <div className="flex size-full items-center justify-center"><Loader2Icon className="size-6 animate-spin text-primary" /></div>}
    </div>;
}
