import {i18nText} from '@/utils/i18n';
import {NodeViewWrapper, type ReactNodeViewProps} from '@tiptap/react';
import {
  BrainCircuitIcon,
  CircleHelpIcon,
  EditIcon,
  GitBranchPlusIcon,
  Maximize2Icon,
  Minimize2Icon,
  NetworkIcon,
  PencilRulerIcon,
  PlusIcon,
  Redo2Icon,
  RotateCcwIcon,
  SaveIcon,
  SparklesIcon,
  Trash2Icon,
  Undo2Icon,
  XIcon,
  ZoomInIcon,
  ZoomOutIcon,
} from 'lucide-react';
import MindElixir, {type MindElixirData} from 'mind-elixir';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useArticleInfoStore} from '@/components/Article/stores/articleInfoStore';
import {useEditorStore} from '@/components/Article/stores/editorStore';
import {simpleRequest} from '@/components/Article/utitilies/ai-adapter';
import {Button} from '@/components/ui/button';
import {Dialog, DialogContent, DialogHeader, DialogTitle,} from '@/components/ui/dialog';
import {useThemeContext} from '@/contexts/ThemeContext';
import {cn} from '@/lib/utils';
import {deleteUploadedFile, readUploadedText, uploadFile, uploadImage,} from '@/services/upload';
import type {CanvasBlockAttrs, CanvasType} from './canvas';
import {MindMapEditor, type MindMapEditorHandle} from './mindmap-editor';

const DRAWIO_BASE_URL =
  '/drawio/?embed=1&ui=atlas&spin=1&proto=json&saveAndExit=1&noExitBtn=1';

function getDrawioLanguage() {
  const language = navigator.languages?.[0] ?? navigator.language ?? 'zh-CN';
  const normalizedLanguage = language.toLowerCase();

  if (normalizedLanguage.startsWith('zh')) return 'zh';
  return normalizedLanguage.split('-')[0] || 'zh';
}

const canvasMeta: Record<
  CanvasType,
  { description: string; icon: typeof NetworkIcon; label: string }
> = {
  drawio: {
    label: i18nText("app.article.canvas.canvasblockview.9abf51ff"),
    description: i18nText("app.article.canvas.canvasblockview.dc071f65"),
    icon: NetworkIcon,
  },
  mindmap: {
    label: i18nText("app.article.canvas.canvasblockview.9442c76b"),
    description: i18nText("app.article.canvas.canvasblockview.1ed0ebcf"),
    icon: BrainCircuitIcon,
  },
  whiteboard: {
    label: i18nText("app.article.canvas.canvasblockview.2b59a543"),
    description: i18nText("app.article.canvas.canvasblockview.04628a98"),
    icon: PencilRulerIcon,
  },
};

const decodeCanvasData = (value: string) => {
  if (!value) return '';

  try {
    return decodeURIComponent(escape(window.atob(value)));
  } catch {
    return '';
  }
};

async function svgToFile(svg: string) {
  const blob = svg.startsWith('data:')
    ? await fetch(svg).then((response) => response.blob())
    : new Blob([svg], { type: 'image/svg+xml' });

  return normalizeSvgFile(blob, `drawio-${crypto.randomUUID()}.svg`);
}

async function normalizeSvgFile(blob: Blob, filename: string) {
  const document = new DOMParser().parseFromString(
    await blob.text(),
    'image/svg+xml',
  );
  if (document.querySelector('parsererror'))
    throw new Error(i18nText("app.article.canvas.canvasblockview.614295ff"));
  const root = document.documentElement;
  if (root.tagName.toLowerCase() !== 'svg')
    throw new Error(i18nText("app.article.canvas.canvasblockview.a1370d69"));

  if (!root.hasAttribute('viewBox')) {
    const width = Number.parseFloat(root.getAttribute('width') ?? '');
    const height = Number.parseFloat(root.getAttribute('height') ?? '');
    if (
      !Number.isFinite(width) ||
      !Number.isFinite(height) ||
      width <= 0 ||
      height <= 0
    ) {
      throw new Error(i18nText("app.article.canvas.canvasblockview.e16c9016"));
    }
    root.setAttribute('viewBox', `0 0 ${width} ${height}`);
  }

  root.setAttribute('width', '100%');
  root.setAttribute('height', '100%');
  root.setAttribute('preserveAspectRatio', 'xMidYMid meet');
  const normalizedSvg = new XMLSerializer().serializeToString(root);
  return new File([normalizedSvg], filename, { type: 'image/svg+xml' });
}

function xmlToFile(xml: string) {
  return new File([xml], `drawio-${crypto.randomUUID()}.drawio`, {
    type: 'application/xml',
  });
}

function mindMapToFile(data: MindElixirData) {
  return new File(
    [JSON.stringify(data)],
    `mindmap-${crypto.randomUUID()}.json`,
    {
      type: 'application/json',
    },
  );
}

function mindMapSvgToFile(blob: Blob) {
  return normalizeSvgFile(blob, `mindmap-${crypto.randomUUID()}.svg`);
}

function readMessage(data: unknown): Record<string, any> | undefined {
  if (typeof data === 'object' && data !== null)
    return data as Record<string, any>;
  if (typeof data !== 'string') return undefined;

  try {
    return JSON.parse(data);
  } catch {
    return undefined;
  }
}

function MindMapShortcutGroup({
  title,
  shortcuts,
}: {
  title: string;
  shortcuts: Array<[string, string]>;
}) {
  return (
    <section>
      <h4 className="m-0 mb-3 font-medium">{title}</h4>
      <dl className="m-0 space-y-2">
        {shortcuts.map(([key, description]) => (
          <div key={key} className="flex items-center justify-between gap-4">
            <dt className="text-sm text-slate-600 dark:text-slate-300">
              {description}
            </dt>
            <dd className="m-0 shrink-0 rounded border border-slate-200 bg-slate-50 px-2 py-1 font-mono text-xs dark:border-slate-700 dark:bg-slate-800">
              {key}
            </dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export function CanvasBlockView({ editor, getPos, node }: ReactNodeViewProps) {
  const operationMode = useEditorStore((state) => state.operationMode);
  const saveArticle = useArticleInfoStore((state) => state.saveArticle);
  const { isDark } = useThemeContext();
  const [open, setOpen] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [uploadingPreview, setUploadingPreview] = useState(false);
  const [uploadingSource, setUploadingSource] = useState(false);
  const [loadingSource, setLoadingSource] = useState(false);
  const [mindMapData, setMindMapData] = useState<MindElixirData>();
  const [showMindMapHelp, setShowMindMapHelp] = useState(false);
  const [previewScale, setPreviewScale] = useState(1);
  const [isPreviewFullscreen, setIsPreviewFullscreen] = useState(false);
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const mindMapEditorRef = useRef<MindMapEditorHandle>(null);
  const previewContainerRef = useRef<HTMLDivElement>(null);
  const pendingXmlRef = useRef('');
  const pendingMindMapRef = useRef<MindElixirData | undefined>(undefined);
  const loadedXmlRef = useRef('');
  const newSourceUrlRef = useRef('');
  const previousSourceUrlRef = useRef('');
  const previousPreviewUrlRef = useRef('');
  const savingSourceRef = useRef(false);
  const awaitingPreviewRef = useRef(false);
  const closeAfterPreviewRef = useRef(false);
  const attrs = node.attrs;
  const canvasType = attrs.canvasType as CanvasType;
  const meta = canvasMeta[canvasType] ?? canvasMeta.drawio;
  const Icon = meta.icon;
  const editable = operationMode === 'edit';
  const canEdit = canvasType === 'drawio' || canvasType === 'mindmap';
  const storedPreview = attrs.preview as string;
  const preview =
    Number(attrs.schemaVersion) >= 3 && !storedPreview.startsWith('data:')
      ? storedPreview
      : '';
  const drawioUrl = useMemo(() => {
    const url = new URL(DRAWIO_BASE_URL, window.location.origin);
    if (!url.searchParams.has('lang'))
      url.searchParams.set('lang', getDrawioLanguage());
    url.searchParams.set('dark', isDark ? '1' : '0');
    return url.toString();
  }, [isDark]);
  const drawioOrigin = useMemo(() => new URL(drawioUrl).origin, [drawioUrl]);

  const updateAttrs = useCallback(
    (nextAttrs: Partial<CanvasBlockAttrs>) => {
      const position = getPos();
      if (position === undefined) return;

      editor
        .chain()
        .command(({ tr }) => {
          const currentNode = tr.doc.nodeAt(position);
          if (!currentNode || currentNode.type.name !== 'canvasBlock')
            return false;

          tr.setNodeMarkup(position, undefined, {
            ...currentNode.attrs,
            ...nextAttrs,
          });
          return true;
        })
        .run();
    },
    [editor, getPos],
  );

  useEffect(() => {
    const handleFullscreenChange = () => {
      setIsPreviewFullscreen(
        document.fullscreenElement === previewContainerRef.current,
      );
    };

    document.addEventListener('fullscreenchange', handleFullscreenChange);
    return () =>
      document.removeEventListener('fullscreenchange', handleFullscreenChange);
  }, []);

  const handleOpenChange = (nextOpen: boolean) => {
    if (nextOpen) {
      awaitingPreviewRef.current = false;
      closeAfterPreviewRef.current = false;
      loadedXmlRef.current = '';
      newSourceUrlRef.current = '';
    } else {
      pendingXmlRef.current = '';
      pendingMindMapRef.current = undefined;
      setMindMapData(undefined);
      setShowMindMapHelp(false);
    }
    setOpen(nextOpen);
  };

  const editExistingCanvas = () => {
    pendingXmlRef.current = '';
    pendingMindMapRef.current = undefined;
    handleOpenChange(true);
  };

  useEffect(() => {
    if (!open || canvasType !== 'drawio') return;

    const requestPreview = (xml: string) => {
      iframeRef.current?.contentWindow?.postMessage(
        JSON.stringify({
          action: 'export',
          format: 'svg',
          xml,
          theme: 'auto',
          background: 'transparent',
        }),
        drawioOrigin,
      );
    };

    const receiveMessage = async (event: MessageEvent) => {
      if (event.origin !== drawioOrigin) return;
      if (event.source !== iframeRef.current?.contentWindow) return;

      const message = readMessage(event.data);
      if (!message) return;

      if (message.event === 'init') {
        setLoadingSource(true);
        try {
          let xml = pendingXmlRef.current;
          if (!xml && attrs.sourceUrl)
            xml = await readUploadedText(attrs.sourceUrl as string);
          if (!xml && attrs.data) xml = decodeCanvasData(attrs.data as string);
          loadedXmlRef.current = xml;
          iframeRef.current?.contentWindow?.postMessage(
            JSON.stringify({ action: 'load', xml }),
            drawioOrigin,
          );
        } catch (error: any) {
          console.error('Draw.io source load failed:', error);
          window.alert(error?.message ?? i18nText("app.article.canvas.canvasblockview.c40ac313"));
          setOpen(false);
        } finally {
          setLoadingSource(false);
        }
        return;
      }

      if (
        message.event === 'load' &&
        !preview &&
        (pendingXmlRef.current || loadedXmlRef.current)
      ) {
        requestPreview(pendingXmlRef.current || loadedXmlRef.current);
        return;
      }

      if (message.event === 'save' && typeof message.xml === 'string') {
        if (savingSourceRef.current) return;
        savingSourceRef.current = true;
        pendingXmlRef.current = message.xml;
        closeAfterPreviewRef.current = Boolean(message.exit);
        previousSourceUrlRef.current = attrs.sourceUrl as string;
        previousPreviewUrlRef.current = preview;
        setUploadingSource(true);
        try {
          const sourceUrl = await uploadFile(
            xmlToFile(message.xml),
            'files/article/drawio',
          );
          newSourceUrlRef.current = sourceUrl;
          updateAttrs({ sourceUrl, data: '', schemaVersion: 4 });
          awaitingPreviewRef.current = true;
          requestPreview(message.xml);
        } catch (error: any) {
          console.error('Draw.io source upload failed:', error);
          window.alert(
            error?.message ?? i18nText("app.article.canvas.canvasblockview.d9c0be0d"),
          );
        } finally {
          savingSourceRef.current = false;
          setUploadingSource(false);
        }
        return;
      }

      if (message.event === 'export' && typeof message.data === 'string') {
        const shouldSaveArticle = awaitingPreviewRef.current;
        setUploadingPreview(true);
        try {
          const previewFile = await svgToFile(message.data);
          const previewUrl = await uploadImage(
            previewFile,
            'images/article/drawio',
          );
          const sourceUrl =
            newSourceUrlRef.current || (attrs.sourceUrl as string);
          awaitingPreviewRef.current = false;
          updateAttrs(
            sourceUrl
              ? { sourceUrl, data: '', preview: previewUrl, schemaVersion: 4 }
              : { preview: previewUrl, schemaVersion: 3 },
          );
          if (shouldSaveArticle) {
            const articleSaved = await saveArticle(editor, 'manual');
            if (!articleSaved) {
              window.alert(
                i18nText("app.article.canvas.canvasblockview.5f84bc55"),
              );
              return;
            }
            if (
              previousSourceUrlRef.current &&
              previousSourceUrlRef.current !== sourceUrl
            ) {
              deleteUploadedFile(previousSourceUrlRef.current).catch(
                (error) => {
                  console.warn('Draw.io old source deletion failed:', error);
                },
              );
            }
            if (
              previousPreviewUrlRef.current &&
              previousPreviewUrlRef.current !== previewUrl
            ) {
              deleteUploadedFile(previousPreviewUrlRef.current).catch(
                (error) => {
                  console.warn('Draw.io old preview deletion failed:', error);
                },
              );
            }
          }
          if (closeAfterPreviewRef.current) {
            pendingXmlRef.current = '';
            setOpen(false);
          }
        } catch (error: any) {
          awaitingPreviewRef.current = false;
          console.error('Draw.io preview upload failed:', error);
          window.alert(
            error?.message ??
              i18nText("app.article.canvas.canvasblockview.3b4b04ec"),
          );
        } finally {
          setUploadingPreview(false);
        }
        return;
      }

      if (message.error && awaitingPreviewRef.current) {
        awaitingPreviewRef.current = false;
        if (closeAfterPreviewRef.current) {
          pendingXmlRef.current = '';
          setOpen(false);
        }
        return;
      }
    };

    window.addEventListener('message', receiveMessage);
    return () => window.removeEventListener('message', receiveMessage);
  }, [
    attrs.data,
    attrs.sourceUrl,
    canvasType,
    drawioOrigin,
    editor,
    open,
    preview,
    saveArticle,
    updateAttrs,
  ]);

  useEffect(() => {
    if (!open || canvasType !== 'mindmap') return;
    let cancelled = false;

    const loadMindMap = async () => {
      setLoadingSource(true);
      try {
        let data = pendingMindMapRef.current;
        if (!data && attrs.sourceUrl) {
          const source = await readUploadedText(attrs.sourceUrl as string);
          data = JSON.parse(source) as MindElixirData;
        }
        if (!data) data = MindElixir.new(i18nText("app.article.canvas.canvasblockview.b709a807"));
        if (!cancelled) setMindMapData(data);
      } catch (error: any) {
        console.error('Mind map source load failed:', error);
        window.alert(error?.message ?? i18nText("app.article.canvas.canvasblockview.e2f35955"));
        if (!cancelled) setOpen(false);
      } finally {
        if (!cancelled) setLoadingSource(false);
      }
    };

    loadMindMap();
    return () => {
      cancelled = true;
    };
  }, [attrs.sourceUrl, canvasType, open]);

  useEffect(() => {
    if (!open || canvasType !== 'mindmap') return;

    const handleHelpShortcut = (event: KeyboardEvent) => {
      const target = event.target;
      if (
        target instanceof HTMLElement &&
        (target.isContentEditable ||
          ['INPUT', 'TEXTAREA'].includes(target.tagName))
      )
        return;
      if (event.key !== '?') return;
      event.preventDefault();
      event.stopPropagation();
      setShowMindMapHelp((current) => !current);
    };

    window.addEventListener('keydown', handleHelpShortcut, true);
    return () =>
      window.removeEventListener('keydown', handleHelpShortcut, true);
  }, [canvasType, open]);

  const saveMindMap = async () => {
    if (!mindMapEditorRef.current || savingSourceRef.current) return;
    savingSourceRef.current = true;
    setUploadingSource(true);
    let uploadedSourceUrl = '';
    try {
      const data = mindMapEditorRef.current.exportData();
      const svg = mindMapEditorRef.current.exportSvg();
      const previousSourceUrl = attrs.sourceUrl as string;
      const previousPreviewUrl = preview;
      const sourceUrl = await uploadFile(
        mindMapToFile(data),
        'files/article/mindmap',
      );
      uploadedSourceUrl = sourceUrl;
      setUploadingPreview(true);
      const previewFile = await mindMapSvgToFile(svg);
      const previewUrl = await uploadImage(
        previewFile,
        'images/article/mindmap',
      );

      updateAttrs({
        sourceUrl,
        data: '',
        preview: previewUrl,
        schemaVersion: 4,
      });
      const articleSaved = await saveArticle(editor, 'manual');
      if (!articleSaved) {
        window.alert(
          i18nText("app.article.canvas.canvasblockview.10062817"),
        );
        return;
      }
      if (previousSourceUrl && previousSourceUrl !== sourceUrl) {
        deleteUploadedFile(previousSourceUrl).catch((error) =>
          console.warn('Old mind map source deletion failed:', error),
        );
      }
      if (previousPreviewUrl && previousPreviewUrl !== previewUrl) {
        deleteUploadedFile(previousPreviewUrl).catch((error) =>
          console.warn('Old mind map preview deletion failed:', error),
        );
      }
      pendingMindMapRef.current = undefined;
      setOpen(false);
    } catch (error: any) {
      if (uploadedSourceUrl) {
        deleteUploadedFile(uploadedSourceUrl).catch((cleanupError) =>
          console.warn('Failed to clean up new mind map source:', cleanupError),
        );
      }
      console.error('Mind map save failed:', error);
      window.alert(
        error?.message ?? i18nText("app.article.canvas.canvasblockview.ef3cb9ca"),
      );
    } finally {
      savingSourceRef.current = false;
      setUploadingSource(false);
      setUploadingPreview(false);
    }
  };

  const runMindMapAction = (
    action: (editor: MindMapEditorHandle) => void | Promise<void>,
  ) => {
    const mindMapEditor = mindMapEditorRef.current;
    if (!mindMapEditor) return;
    Promise.resolve(action(mindMapEditor)).catch((error: any) => {
      window.alert(error?.message ?? i18nText("app.article.canvas.canvasblockview.932d0846"));
    });
  };

  const deleteNode = () => {
    const position = getPos();
    if (position === undefined) return;

    editor
      .chain()
      .focus()
      .command(({ tr }) => {
        tr.delete(position, position + node.nodeSize);
        return true;
      })
      .run();
  };

  const zoomPreview = (step: number) => {
    setPreviewScale((currentScale) =>
      Math.min(2.5, Math.max(0.5, Number((currentScale + step).toFixed(2)))),
    );
  };

  const togglePreviewFullscreen = async () => {
    try {
      if (document.fullscreenElement === previewContainerRef.current) {
        await document.exitFullscreen();
      } else if (previewContainerRef.current?.requestFullscreen) {
        await previewContainerRef.current.requestFullscreen();
      } else {
        throw new Error(i18nText("app.article.canvas.canvasblockview.2c75d219"));
      }
    } catch (error: any) {
      window.alert(error?.message ?? i18nText("app.article.canvas.canvasblockview.50ecb4c9"));
    }
  };

  const generateWithAi = async () => {
    const prompt = window.prompt(
      canvasType === 'mindmap'
        ? i18nText("app.article.canvas.canvasblockview.4b2c9a18")
        : i18nText("app.article.canvas.canvasblockview.0eac6b9a"),
    );
    if (!prompt?.trim()) return;

    setGenerating(true);
    try {
      const response = await simpleRequest(
        canvasType === 'mindmap'
          ? {
              text: prompt,
              action:
                'Create a clear hierarchical mind map. Return only valid JSON compatible with Mind Elixir. The root object must contain nodeData; every node must contain a unique id and a topic, and may contain children. Use Chinese labels when appropriate. Do not use markdown fences or explanations.',
              responseFormat: 'Mind Elixir JSON',
            }
          : {
              text: prompt,
              action:
                'Create a clear Draw.io flowchart. Return only valid, uncompressed mxGraphModel XML. Include cells 0 and 1, use unique ids, and use Chinese labels when appropriate. Do not use markdown code fences or explanatory text.',
              responseFormat: 'Draw.io mxGraphModel XML',
            },
      );

      if (canvasType === 'mindmap') {
        const json = response
          .replace(/^```json\s*|^```\s*|\s*```$/g, '')
          .trim();
        const data = JSON.parse(json) as MindElixirData;
        if (!data?.nodeData?.id || !data.nodeData.topic) {
          throw new Error(i18nText("app.article.canvas.canvasblockview.70533b71"));
        }
        pendingMindMapRef.current = data;
        handleOpenChange(true);
        return;
      }

      const xml = response.replace(/^```xml\s*|^```\s*|\s*```$/g, '').trim();

      if (!xml.startsWith('<mxGraphModel') || !xml.includes('<root>')) {
        throw new Error(i18nText("app.article.canvas.canvasblockview.8795d6bf"));
      }

      pendingXmlRef.current = xml;
      handleOpenChange(true);
    } catch (error: any) {
      window.alert(error?.message ?? i18nText("app.article.canvas.canvasblockview.c170ad88"));
    } finally {
      setGenerating(false);
    }
  };

  return (
    <NodeViewWrapper className="relative my-4">
      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-900">
        {preview ? (
          <div
            ref={previewContainerRef}
            className={cn(
              'relative h-[min(60vh,640px)] min-h-64 w-full overflow-hidden bg-white dark:bg-slate-950',
              isPreviewFullscreen && 'h-screen max-h-none min-h-0 w-screen',
            )}
          >
            <div className="absolute top-3 right-3 h-8 z-10 flex items-center gap-1 rounded-md border border-slate-200 bg-white/90 p-1 shadow-sm backdrop-blur dark:border-slate-700 dark:bg-slate-900/90">
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={() => zoomPreview(-0.25)}
                disabled={previewScale <= 0.5}
                title={i18nText("app.article.canvas.canvasblockview.5d4207ed")}
              >
                <ZoomOutIcon className="size-4" />
              </Button>
              <span className="min-w-12 text-center text-xs text-slate-500">
                {Math.round(previewScale * 100)}%
              </span>
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={() => zoomPreview(0.25)}
                disabled={previewScale >= 2.5}
                title={i18nText("app.article.canvas.canvasblockview.5f612f64")}
              >
                <ZoomInIcon className="size-4" />
              </Button>
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={() => setPreviewScale(1)}
                disabled={previewScale === 1}
                title={i18nText("app.article.canvas.canvasblockview.e13328ad")}
              >
                <RotateCcwIcon className="size-4" />
              </Button>
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={togglePreviewFullscreen}
                title={isPreviewFullscreen ? i18nText("app.article.canvas.canvasblockview.23d28ca1") : i18nText("app.article.canvas.canvasblockview.edf8deaf")}
              >
                {isPreviewFullscreen ? (
                  <Minimize2Icon className="size-4" />
                ) : (
                  <Maximize2Icon className="size-4" />
                )}
              </Button>
            </div>

            <div className="size-full overflow-auto scrollbar-thin">
              <object
                data={preview}
                type="image/svg+xml"
                aria-label={attrs.title || meta.label}
                className="block shrink-0 bg-transparent p-4"
                style={{
                  colorScheme: isDark ? 'dark' : 'light',
                  height: `${previewScale * 100}%`,
                  margin: 'auto',
                  objectFit: 'contain',
                  objectPosition: 'center',
                  width: `${previewScale * 100}%`,
                }}
              >
                <a href={preview} target="_blank" rel="noreferrer">
                  {i18nText("app.article.canvas.canvasblockview.df7e368b")}{meta.label}
                </a>
              </object>
            </div>
          </div>
        ) : (
          <div className="flex min-h-36 items-center gap-3 bg-slate-50 p-5 text-slate-700 dark:bg-slate-950 dark:text-slate-200">
            <span className="flex size-10 items-center justify-center rounded-md bg-white shadow-sm dark:bg-slate-800">
              <Icon className="size-5" />
            </span>
            <div>
              <p className="m-0 font-medium">{attrs.title || meta.label}</p>
              <p className="m-0 mt-1 text-sm text-slate-500 dark:text-slate-400">
                {meta.description}
              </p>
            </div>
          </div>
        )}
        <div className="flex items-center justify-between border-t border-slate-100 px-4 py-2 dark:border-slate-800">
          <span className="text-xs text-slate-500">{meta.label}</span>
          {editable && (
            <div className="flex gap-1">
              {canEdit && (
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={generating}
                  onClick={generateWithAi}
                >
                  <SparklesIcon className="size-4" />
                  {generating ? i18nText("app.article.canvas.canvasblockview.32a54de6") : i18nText("app.article.canvas.canvasblockview.4df4d9e1")}
                </Button>
              )}
              <Button
                variant="ghost"
                size="sm"
                disabled={!canEdit}
                className={cn(!canEdit && 'cursor-not-allowed')}
                onClick={editExistingCanvas}
              >
                <EditIcon className="size-4" />
                {i18nText("app.article.canvas.canvasblockview.0f778d3c")}
              </Button>
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={deleteNode}
                aria-label={i18nText("app.article.canvas.canvasblockview.a8725ed2")}
              >
                <Trash2Icon className="size-4" />
              </Button>
            </div>
          )}
        </div>
      </div>

      <Dialog
        open={open}
        onOpenChange={handleOpenChange}
        disablePointerDismissal
      >
        <DialogContent
          className="!z-[1100] !flex h-[calc(100vh-3rem)] max-w-[calc(100vw-3rem)] !flex-col gap-3 p-4 sm:max-w-[calc(100vw-3rem)]"
          overlayClassName="!z-[1099]"
          showCloseButton
        >
          <DialogHeader className="shrink-0 pr-10">
            <div className="flex items-center justify-between gap-4">
              <DialogTitle>
                {attrs.title ||
                  (canvasType === 'mindmap'
                    ? i18nText("app.article.canvas.canvasblockview.cbf06f86")
                    : i18nText("app.article.canvas.canvasblockview.107310c6"))}
                {loadingSource
                  ? i18nText("app.article.canvas.canvasblockview.5a10395c")
                  : uploadingSource
                    ? i18nText("app.article.canvas.canvasblockview.a7193766")
                    : uploadingPreview
                      ? i18nText("app.article.canvas.canvasblockview.1441344d")
                      : ''}
              </DialogTitle>
              {canvasType === 'mindmap' && (
                <div className="flex items-center gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => runMindMapAction((mind) => mind.addChild())}
                    title={i18nText("app.article.canvas.canvasblockview.203fff44")}
                  >
                    <GitBranchPlusIcon className="size-4" />
                    {i18nText("app.article.canvas.canvasblockview.a1f87b57")}
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() =>
                      runMindMapAction((mind) => mind.addSibling())
                    }
                    title={i18nText("app.article.canvas.canvasblockview.94b0b121")}
                  >
                    <PlusIcon className="size-4" />
                    {i18nText("app.article.canvas.canvasblockview.144b67c1")}
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() =>
                      runMindMapAction((mind) => mind.editSelected())
                    }
                    title={i18nText("app.article.canvas.canvasblockview.6e78f039")}
                  >
                    <EditIcon className="size-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() =>
                      runMindMapAction((mind) => mind.removeSelected())
                    }
                    title={i18nText("app.article.canvas.canvasblockview.d50d5865")}
                  >
                    <Trash2Icon className="size-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => runMindMapAction((mind) => mind.undo())}
                    title={i18nText("app.article.canvas.canvasblockview.c5414bed")}
                  >
                    <Undo2Icon className="size-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => runMindMapAction((mind) => mind.redo())}
                    title={i18nText("app.article.canvas.canvasblockview.030f50a0")}
                  >
                    <Redo2Icon className="size-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => setShowMindMapHelp(true)}
                    title={i18nText("app.article.canvas.canvasblockview.b94af55c")}
                  >
                    <CircleHelpIcon className="size-4" />
                  </Button>
                  <Button
                    size="sm"
                    disabled={
                      !mindMapData || uploadingSource || uploadingPreview
                    }
                    onClick={saveMindMap}
                  >
                    <SaveIcon className="size-4" />
                    {i18nText("app.article.canvas.canvasblockview.9c876095")}
                  </Button>
                </div>
              )}
            </div>
          </DialogHeader>
          <div className="relative min-h-0 flex-1">
            {canvasType === 'drawio' && (
              <iframe
                ref={iframeRef}
                title={i18nText("app.article.canvas.canvasblockview.f5c69a59")}
                src={drawioUrl}
                className="size-full border-0"
              />
            )}
            {canvasType === 'mindmap' && mindMapData && (
              <MindMapEditor
                ref={mindMapEditorRef}
                data={mindMapData}
                isDark={isDark}
              />
            )}
            {canvasType === 'mindmap' && showMindMapHelp && (
              <div className="absolute inset-4 z-[200] overflow-auto rounded-xl border border-slate-200 bg-white/95 p-6 shadow-2xl backdrop-blur dark:border-slate-700 dark:bg-slate-900/95">
                <div className="mb-5 flex items-center justify-between">
                  <div>
                    <h3 className="m-0 text-lg font-semibold">
                      {i18nText("app.article.canvas.canvasblockview.10017ddf")}
                    </h3>
                    <p className="m-0 mt-1 text-sm text-slate-500">
                      {i18nText("app.article.canvas.canvasblockview.95cf7345")}
                    </p>
                  </div>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => setShowMindMapHelp(false)}
                    aria-label={i18nText("app.article.canvas.canvasblockview.991be50b")}
                  >
                    <XIcon className="size-4" />
                  </Button>
                </div>
                <div className="grid gap-6 md:grid-cols-2 xl:grid-cols-3">
                  <MindMapShortcutGroup
                    title={i18nText("app.article.canvas.canvasblockview.9976e86a")}
                    shortcuts={[
                      ['Tab', i18nText("app.article.canvas.canvasblockview.a1afdfbf")],
                      ['Enter', i18nText("app.article.canvas.canvasblockview.c094f96d")],
                      ['Shift + Enter', i18nText("app.article.canvas.canvasblockview.bc858025")],
                      ['Ctrl/Cmd + Enter', i18nText("app.article.canvas.canvasblockview.16e7ab8b")],
                      [i18nText("app.article.canvas.canvasblockview.2ac62e48"), i18nText("app.article.canvas.canvasblockview.7c3adfd6")],
                      ['Delete / Backspace', i18nText("app.article.canvas.canvasblockview.29efe486")],
                    ]}
                  />
                  <MindMapShortcutGroup
                    title={i18nText("app.article.canvas.canvasblockview.4bbd8f23")}
                    shortcuts={[
                      [i18nText("app.article.canvas.canvasblockview.191fcfcf"), i18nText("app.article.canvas.canvasblockview.a77adb9d")],
                      ['PageUp / Alt + ↑', i18nText("app.article.canvas.canvasblockview.79f8e04c")],
                      ['PageDown / Alt + ↓', i18nText("app.article.canvas.canvasblockview.1eae6744")],
                      ['F1', i18nText("app.article.canvas.canvasblockview.90684e96")],
                      ['Ctrl/Cmd + ←', i18nText("app.article.canvas.canvasblockview.e6615090")],
                      ['Ctrl/Cmd + →', i18nText("app.article.canvas.canvasblockview.b3065c75")],
                      ['Ctrl/Cmd + ↑', i18nText("app.article.canvas.canvasblockview.556451cd")],
                    ]}
                  />
                  <MindMapShortcutGroup
                    title={i18nText("app.article.canvas.canvasblockview.37cf48ce")}
                    shortcuts={[
                      ['Ctrl/Cmd + =', i18nText("app.article.canvas.canvasblockview.5f612f64")],
                      ['Ctrl/Cmd + -', i18nText("app.article.canvas.canvasblockview.5d4207ed")],
                      ['Ctrl/Cmd + 0', i18nText("app.article.canvas.canvasblockview.18b1c971")],
                      ['Ctrl/Cmd + Z', i18nText("app.article.canvas.canvasblockview.c5414bed")],
                      ['Ctrl/Cmd + Shift + Z / Ctrl + Y', i18nText("app.article.canvas.canvasblockview.030f50a0")],
                      ['Ctrl/Cmd + C / X / V', i18nText("app.article.canvas.canvasblockview.fca1320d")],
                      [i18nText("app.article.canvas.canvasblockview.41403531"), i18nText("app.article.canvas.canvasblockview.6e8f0035")],
                      [i18nText("app.article.canvas.canvasblockview.2d9c67c0"), i18nText("app.article.canvas.canvasblockview.a1f4c1b5")],
                    ]}
                  />
                </div>
              </div>
            )}
          </div>
        </DialogContent>
      </Dialog>
    </NodeViewWrapper>
  );
}
