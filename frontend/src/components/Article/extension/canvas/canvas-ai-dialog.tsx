import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useModel} from '@umijs/max';
import {createStyles} from 'antd-style';
import {ImagePlusIcon, Loader2Icon, SendIcon, SparklesIcon, SquareIcon, XIcon} from 'lucide-react';
import ModelSelector from '@/components/AI/ModelSelector';
import ThinkingBlock from '@/components/AI/ThinkingBlock';
import {Button} from '@/components/ui/button';
import {Textarea} from '@/components/ui/textarea';
import {Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle} from '@/components/ui/dialog';
import {STREAM_CHAT_URL, streamChat} from '@/services/ant-design-pro/ai.chat';
import {createConversation, listMessages} from '@/services/ant-design-pro/ai.rbac';
import {useModelsStore} from '@/stores/modelsStore';
import type {Conversation, Message, ModelConfig} from '@/types/ai.type';
import {getI18nLocale, i18nText} from '@/utils/i18n';
import {generateRandomUUID} from '@/utils/RandomUtil';
import {cn} from '@/lib/utils';
import type {CanvasType} from './canvas';
import {buildCanvasPrompt, CANVAS_CONTEXT_MARKER, type CanvasAiMode, hasCanvasSource, parseCanvasResponse} from './canvas-ai';
import {CanvasAiPreview} from './canvas-ai-preview';
import {type CanvasImageRecord, configureCanvasConversation, toCanvasAttachment, uploadCanvasChatImage} from './canvas-ai-service';

const useStyles = createStyles(({token, css}) => ({
  workspace: css`
    background: ${token.colorBgElevated};
    color: ${token.colorText};
    --color-primary: ${token.colorPrimary};
    --color-bg-container: ${token.colorBgContainer};
    --color-bg-layout: ${token.colorBgLayout};
    --color-text-secondary: ${token.colorTextSecondary};
    --color-border: ${token.colorBorderSecondary};
    --color-error: ${token.colorError};
    .bg-primary { color: ${token.colorWhite}; }
    .bg-secondary { background: ${token.colorPrimaryBg}; color: ${token.colorPrimary}; }
    [data-slot='button']:hover { background-color: ${token.colorFillSecondary}; }
    [data-slot='button'].bg-primary:hover { background-color: ${token.colorPrimaryHover}; }
  `,
}));

type CanvasChatMessage = Message;
const PAGE_SIZE = 20;

export function CanvasAiDialog({type, title, conversationId, initialModelId, savedModelId, savedModelName, preview, getSource, onConversationCreated, onApply, onEdit, onClose}: {
  type: CanvasType;
  title: string;
  conversationId: string;
  initialModelId?: number;
  savedModelId?: number;
  savedModelName?: string;
  preview: string;
  getSource: () => Promise<string>;
  onConversationCreated: (id: string, model: ModelConfig) => Promise<void>;
  onApply: (source: string, svg: Blob) => Promise<void>;
  onEdit: () => void;
  onClose: () => void;
}) {
  const {styles} = useStyles();
  const {initialState} = useModel('@@initialState');
  const models = useModelsStore(state => state.models);
  const loadModels = useModelsStore(state => state.loadModels);
  const [model, setModel] = useState<ModelConfig>();
  const [boundModelId, setBoundModelId] = useState(savedModelId);
  const [boundModelName, setBoundModelName] = useState(savedModelName ?? '');
  const [messages, setMessages] = useState<CanvasChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  const [filePreviews, setFilePreviews] = useState<string[]>([]);
  const [mode, setMode] = useState<CanvasAiMode>('create');
  const [resetHistory, setResetHistory] = useState(true);
  const [workingSource, setWorkingSource] = useState('');
  const [draft, setDraft] = useState<{source: string; messageId: string}>();
  const [appliedSource, setAppliedSource] = useState('');
  const [svg, setSvg] = useState<Blob>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [initialized, setInitialized] = useState(false);
  const [busy, setBusy] = useState(false);
  const [saving, setSaving] = useState(false);
  const [hasOlder, setHasOlder] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [mobileTab, setMobileTab] = useState<'chat' | 'preview'>('chat');
  const convIdRef = useRef(conversationId);
  const pageRef = useRef(1);
  const controllerRef = useRef<AbortController | null>(null);
  const sendLockRef = useRef(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const followScrollRef = useRef(true);
  const requestDraftsRef = useRef(new Map<string, {files: File[]; mode: CanvasAiMode; source: string; resetHistory: boolean}>());
  useEffect(() => {
    const urls = files.map(file => URL.createObjectURL(file));
    setFilePreviews(urls);
    return () => urls.forEach(url => URL.revokeObjectURL(url));
  }, [files]);

  const loadHistory = async (page: number) => {
    const result = await listMessages({convId: convIdRef.current, current: page, size: PAGE_SIZE,
      includeDeleted: false, orders: [{column: 'id', asc: false}]});
    if (!result) throw new Error(i18nText('app.article.canvas.ai.historyError'));
    const records = (result.records ?? []) as (Omit<Message, 'attachments'> & {attachments?: CanvasImageRecord[]})[];
    setHasOlder(page * PAGE_SIZE < result.total);
    pageRef.current = page;
    return records.reverse().map(item => ({...item, attachments: item.attachments?.map(toCanvasAttachment)})) as CanvasChatMessage[];
  };

  useEffect(() => {
    let active = true;
    const initialize = async () => {
      try {
        const source = await getSource();
        const history = convIdRef.current ? await loadHistory(1) : [];
        if (savedModelId) await loadModels();
        else if (convIdRef.current) {
          const oldest = await listMessages({convId: convIdRef.current, current: 1, size: PAGE_SIZE,
            includeDeleted: false, orders: [{column: 'id', asc: true}]});
          if (!oldest) throw new Error(i18nText('app.article.canvas.ai.historyError'));
          const firstModelId = oldest.records?.find((item: Message) => item.role === 'assistant' && item.modelId)?.modelId;
          if (firstModelId) {
            const availableModels = await loadModels();
            const firstModel = availableModels.find(item => item.id === firstModelId);
            if (active) {
              setBoundModelId(firstModelId);
              setBoundModelName(firstModel?.modelName || firstModel?.modelId || '');
            }
          }
        }
        if (!active) return;
        setWorkingSource(source);
        setAppliedSource(source);
        setMode(source ? 'modify' : 'create');
        setResetHistory(history.length === 0 && !source);
        setMessages(history);
        setInitialized(true);
      } catch (cause) {
        if (active) setError((cause as Error).message);
      } finally { if (active) setLoading(false); }
    };
    void initialize();
    return () => { active = false; controllerRef.current?.abort(); };
  }, []);

  useEffect(() => {
    if (followScrollRef.current && scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
  }, [messages, busy]);

  const selectedModel = models.find(item => item.id === (boundModelId ?? model?.id) && item.status !== 3);
  const modelName = boundModelName || selectedModel?.modelName || selectedModel?.modelId;
  const hasHistoryImages = messages.some(item => item.attachments?.length);
  const needsVision = files.length > 0 || (!resetHistory && hasHistoryImages);
  const canSend = initialized && !loading && !busy && !saving && !!selectedModel?.id
    && (!needsVision || selectedModel.supportVision) && (!!input.trim() || files.length > 0);
  const candidates = useMemo(() => new Map(messages.filter(item => item.role === 'assistant' && item.status === 'completed')
    .flatMap(item => {
      try { return [[item.messageId, parseCanvasResponse(type, item.optimizedContent || item.content)] as const]; }
      catch { return []; }
    })), [messages, type]);
  const selectedCandidate = draft && candidates.get(draft.messageId);

  const selectDraft = (messageId: string, source: string) => {
    setWorkingSource(source);
    setMode('modify');
    setResetHistory(false);
    setMobileTab('preview');
    if (draft?.messageId === messageId) return;
    setDraft({source, messageId});
    setSvg(undefined);
    setError('');
  };

  const patchMessage = (messageId: string, patch: Partial<CanvasChatMessage>) => {
    setMessages(previous => previous.map(item => item.messageId === messageId ? {...item, ...patch} : item));
  };

  const send = async () => {
    if (!canSend || sendLockRef.current) return;
    sendLockRef.current = true;
    setBusy(true);
    setError('');
    followScrollRef.current = true;
    const controller = new AbortController();
    controllerRef.current = controller;
    const userMessageId = generateRandomUUID(32);
    const assistantMessageId = generateRandomUUID(32);
    const text = input.trim() || i18nText('app.article.canvas.ai.imageRequest');
    let response = '';
    let reasoning = '';
    let failed = false;
    let started = false;
    try {
      if (!convIdRef.current) {
        const conversation = await createConversation({title: `${title} · ${i18nText('app.article.canvas.ai.title')}`,
          scene: `article_${type}`, interactionType: 'backend'}) as Conversation;
        if (!conversation?.convId) throw new Error(i18nText('app.article.canvas.ai.historyError'));
        convIdRef.current = conversation.convId;
      }
      if (controller.signal.aborted) return;
      // Persist the first model with the link so future sessions keep the same model.
      await onConversationCreated(convIdRef.current, {...selectedModel!, modelName: modelName || String(selectedModel!.id)});
      setBoundModelId(selectedModel!.id);
      setBoundModelName(modelName || String(selectedModel!.id));
      await configureCanvasConversation(convIdRef.current, selectedModel!.id!, resetHistory ? 'create' : 'modify');
      const attachments = [];
      for (const file of files) {
        if (controller.signal.aborted) return;
        attachments.push(await uploadCanvasChatImage(file, convIdRef.current, userMessageId));
      }
      if (controller.signal.aborted) return;
      const content = buildCanvasPrompt(type, text, workingSource, mode, getI18nLocale());
      setMessages(previous => [...previous,
        {messageId: userMessageId, convId: convIdRef.current, role: 'user', content, attachments, status: 'completed'},
        {messageId: assistantMessageId, convId: convIdRef.current, role: 'assistant', content: '', status: 'streaming', modelId: selectedModel!.id},
      ]);
      started = true;
      requestDraftsRef.current.set(userMessageId, {files, mode, source: workingSource, resetHistory});
      setResetHistory(false);
      setInput('');
      setFiles([]);
      await streamChat(STREAM_CHAT_URL, {convId: convIdRef.current, content, modelId: selectedModel!.id,
        userName: initialState?.currentUser?.userName ?? '', userMessageId, assistantMessageId,
        attachmentIds: attachments.map(item => item.attachmentId), enableVision: needsVision, reasoningEffort: 'none',
        scene: `article_${type}`}, {
        onContent: delta => { response += delta; patchMessage(assistantMessageId, {content: response}); },
        onThinking: delta => { reasoning += delta; patchMessage(assistantMessageId, {reasoningContent: reasoning}); },
        onError: cause => {
          failed = true;
          const message = cause?.message || i18nText('app.article.canvas.ai.failed');
          patchMessage(assistantMessageId, {status: 'failed', errorMessage: message});
          setError(message);
        },
      }, controller.signal);
      if (failed || controller.signal.aborted) return;
      if (!response.trim()) throw new Error(i18nText('app.article.canvas.ai.failed'));
      patchMessage(assistantMessageId, {status: 'completed'});
      if (hasCanvasSource(response)) {
        const result = parseCanvasResponse(type, response);
        selectDraft(assistantMessageId, result.source);
      }
    } catch (cause) {
      const stopped = controller.signal.aborted;
      if (started) patchMessage(assistantMessageId, {status: stopped ? 'stopped' : 'failed', errorMessage: stopped ? undefined : (cause as Error).message});
      if (!stopped) setError((cause as Error).message);
    } finally {
      controllerRef.current = null;
      sendLockRef.current = false;
      setBusy(false);
    }
  };

  const addFiles = (items: File[]) => {
    const images = items.filter(file => ['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(file.type));
    if (images.length !== items.length) setError(i18nText('app.article.canvas.ai.imageTypes'));
    setFiles(previous => [...previous, ...images]);
  };
  const apply = async () => {
    if (!draft || !svg) return;
    setSaving(true);
    setError('');
    try { await onApply(draft.source, svg); setAppliedSource(draft.source); onClose(); }
    catch (cause) { setError((cause as Error).message); }
    finally { setSaving(false); }
  };
  const previewReady = useCallback((value: Blob) => setSvg(value), []);
  const previewError = useCallback((message: string) => setError(message), []);
  const loadOlder = async () => {
    setLoadingOlder(true);
    try { const records = await loadHistory(pageRef.current + 1); followScrollRef.current = false; setMessages(previous => [...records, ...previous]); }
    catch (cause) { setError((cause as Error).message); }
    finally { setLoadingOlder(false); }
  };

  return <Dialog open onOpenChange={value => { if (!value && !saving) onClose(); }} disablePointerDismissal>
    <DialogContent className={cn(styles.workspace, '!z-[1100] !flex h-[calc(100dvh-2rem)] max-w-[calc(100vw-2rem)] !flex-col gap-0 overflow-hidden p-0 sm:max-w-[min(72rem,calc(100vw-2rem))]')} overlayClassName="!z-[1099]" showCloseButton={!saving}>
      <DialogHeader className="shrink-0 border-b border-border px-5 py-4 pr-12">
        <DialogTitle className="flex items-center gap-2"><SparklesIcon className="size-5 text-primary" />{title} · {i18nText('app.article.canvas.ai.title')}</DialogTitle>
        <DialogDescription className="text-text-secondary">{i18nText('app.article.canvas.ai.description')}</DialogDescription>
      </DialogHeader>
      <div className="flex gap-2 border-b border-border p-2 md:hidden">
        {(['chat', 'preview'] as const).map(tab => <Button key={tab} variant={mobileTab === tab ? 'default' : 'ghost'} size="sm" onClick={() => setMobileTab(tab)}>{i18nText(`app.article.canvas.ai.${tab}`)}</Button>)}
      </div>
      <div className="flex min-h-0 flex-1 flex-col md:flex-row">
        <section className={cn('min-h-0 w-full flex-col border-border md:flex md:w-[390px] md:shrink-0 md:border-r lg:w-[430px]', mobileTab === 'chat' ? 'flex' : 'hidden')}>
          <div ref={scrollRef} onScroll={() => { const element = scrollRef.current; if (element) followScrollRef.current = element.scrollHeight - element.scrollTop - element.clientHeight < 80; }} className="min-h-0 flex-1 space-y-4 overflow-auto p-4" aria-live="polite" aria-busy={busy || loading}>
            {hasOlder && <Button variant="ghost" size="sm" disabled={loadingOlder || busy} onClick={loadOlder}>{i18nText('app.article.canvas.ai.older')}</Button>}
            {loading ? <div className="flex items-center gap-2 text-text-secondary"><Loader2Icon className="size-4 animate-spin" />{i18nText('app.article.canvas.ai.loading')}</div> : messages.length === 0 && <div className="py-8 text-center">
              <SparklesIcon className="mx-auto mb-3 size-8 text-primary" />
              <p className="mb-3 font-medium">{i18nText('app.article.canvas.ai.empty')}</p>
              <p className="text-sm text-text-secondary">{i18nText('app.article.canvas.ai.emptyHint')}</p>
              <Button variant="outline" size="sm" className="mt-4 max-w-full whitespace-normal" onClick={() => { setInput(i18nText(type === 'mindmap' ? 'app.article.canvas.ai.mindmapExample' : 'app.article.canvas.ai.drawioExample')); inputRef.current?.focus(); }}>{i18nText('app.article.canvas.ai.tryExample')}</Button>
            </div>}
            {messages.map(item => {
              const candidate = candidates.get(item.messageId);
              return <div key={item.messageId} className={cn('rounded-xl border border-border p-3 text-sm', item.role === 'user' ? 'ml-6 bg-primary/5' : 'mr-2 bg-bg-container')}>
                <p className="mb-2 text-xs font-medium text-text-secondary">{item.role === 'user' ? i18nText('app.article.canvas.ai.you') : 'AI'}</p>
                {item.reasoningContent && <ThinkingBlock content={item.reasoningContent} isStreaming={item.status === 'streaming'} />}
                <p className="m-0 whitespace-pre-wrap break-words">{item.role === 'user' ? item.content.split(CANVAS_CONTEXT_MARKER)[0].trim() : candidate?.summary || (candidate ? i18nText('app.article.canvas.ai.result') : item.status === 'streaming' ? i18nText('app.article.canvas.ai.generating') : hasCanvasSource(item.content) ? item.errorMessage || i18nText('app.article.canvas.ai.invalid') : item.content)}</p>
                {item.role === 'assistant' && item.status !== 'streaming' && !candidate && hasCanvasSource(item.content) && <details className="mt-2 text-text-secondary"><summary className="cursor-pointer text-xs">{i18nText('app.article.canvas.ai.rawResponse')}</summary><pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap break-all text-xs">{item.content}</pre></details>}
                {item.attachments?.map(attachment => <a key={attachment.attachmentId} href={attachment.fileUrl} target="_blank" rel="noreferrer" className="mt-2 block"><img src={attachment.fileUrl} alt={attachment.fileName} className="max-h-32 max-w-full rounded-md object-contain" /></a>)}
                {candidate && <Button variant={draft?.messageId === item.messageId ? 'secondary' : 'outline'} className="mt-3" size="sm" disabled={busy || saving} onClick={() => selectDraft(item.messageId, candidate.source)}>{i18nText(draft?.messageId === item.messageId ? 'app.article.canvas.ai.selected' : 'app.article.canvas.ai.viewVersion')}</Button>}
                {item.status === 'stopped' && <p className="mt-2 text-xs text-text-secondary">{i18nText('app.article.canvas.ai.stopped')}</p>}
                {(item.status === 'failed' || item.status === 'stopped') && <Button variant="ghost" size="sm" disabled={busy} onClick={() => {
                  const index = messages.findIndex(message => message.messageId === item.messageId);
                  const request = messages.slice(0, index).reverse().find(message => message.role === 'user');
                  if (request) {
                    setInput(request.content.split(CANVAS_CONTEXT_MARKER)[0].trim());
                    const savedDraft = requestDraftsRef.current.get(request.messageId);
                    if (savedDraft) {
                      setFiles(savedDraft.files);
                      setMode(savedDraft.mode);
                      setWorkingSource(savedDraft.source);
                      setResetHistory(savedDraft.resetHistory);
                    }
                  }
                  inputRef.current?.focus();
                }}>{i18nText('app.article.canvas.ai.retry')}</Button>}
              </div>;
            })}
          </div>
          <div className="shrink-0 space-y-3 border-t border-border bg-bg-container p-4">
            <div className="flex flex-wrap gap-1" role="group" aria-label={i18nText('app.article.canvas.ai.mode')}>
              {(['modify', 'create'] as const).map(value => <Button key={value} size="sm" disabled={busy || saving || loading || (value === 'modify' && !workingSource)} variant={mode === value ? 'default' : 'ghost'} aria-pressed={mode === value} onClick={() => { setMode(value); setResetHistory(value === 'create'); }}>{i18nText(`app.article.canvas.ai.${value}`)}</Button>)}
            </div>
            {boundModelId ? <div className="rounded-lg border border-border bg-primary/5 px-3 py-2">
              <p className="m-0 truncate text-sm" title={modelName}>{i18nText('app.article.canvas.ai.model')} · {modelName || boundModelId}</p>
              <p className="m-0 mt-1 text-xs text-text-secondary">{i18nText('app.article.canvas.ai.modelFixed')}</p>
            </div> : <ModelSelector width="100%" defaultModelId={initialModelId} onSelect={setModel} disabled={busy || saving || loading} getPopupContainer={trigger => trigger.parentElement!} />}
            {!selectedModel && !loading && <p className="m-0 text-xs text-text-secondary">{i18nText(boundModelId ? 'app.article.canvas.ai.modelUnavailable' : 'app.article.canvas.ai.chooseModel')}</p>}
            {needsVision && <p className={cn('m-0 text-xs', selectedModel?.supportVision ? 'text-text-secondary' : 'text-error')}>{i18nText(selectedModel?.supportVision ? 'app.article.canvas.ai.visionEnabled' : boundModelId ? 'app.article.canvas.ai.fixedVisionRequired' : 'app.article.canvas.ai.visionRequired')}</p>}
            {files.length > 0 && <div className="flex max-h-24 flex-wrap gap-2 overflow-auto">{files.map((file, index) => <span key={`${file.name}-${index}`} className="inline-flex max-w-full items-center gap-1 rounded border border-border px-2 py-1 text-xs"><img src={filePreviews[index]} alt={file.name} className="h-8 w-10 rounded object-cover" /><span className="truncate">{file.name}</span><Button size="icon-xs" variant="ghost" disabled={busy} aria-label={i18nText('app.article.canvas.ai.removeImage')} onClick={() => setFiles(previous => previous.filter((_, position) => index !== position))}><XIcon /></Button></span>)}</div>}
            <Textarea ref={inputRef} value={input} disabled={busy || saving || loading} rows={3} className="max-h-40 resize-none border-border" aria-label={i18nText('app.article.canvas.ai.prompt')} placeholder={i18nText('app.article.canvas.ai.placeholder')} onChange={event => setInput(event.target.value)}
              onPaste={event => { const images = Array.from(event.clipboardData.files); if (images.length) { event.preventDefault(); addFiles(images); } }}
              onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void send(); } }} />
            <div className="flex items-center justify-between gap-2">
              <input ref={fileInputRef} type="file" className="hidden" multiple accept="image/png,image/jpeg,image/webp,image/gif" onChange={event => { addFiles(Array.from(event.target.files ?? [])); event.target.value = ''; }} />
              <Button variant="ghost" size="sm" disabled={busy || saving} onClick={() => fileInputRef.current?.click()}><ImagePlusIcon className="size-4" />{i18nText('app.article.canvas.ai.addImage')}</Button>
              {busy ? <Button variant="outline" size="sm" onClick={() => controllerRef.current?.abort()}><SquareIcon className="size-4" />{i18nText('app.article.canvas.ai.stop')}</Button> : <Button size="sm" disabled={!canSend} onClick={send}><SendIcon className="size-4" />{i18nText('app.article.canvas.ai.send')}</Button>}
            </div>
            <p className="m-0 text-xs text-text-secondary">{i18nText('app.article.canvas.ai.keyboard')}</p>
          </div>
        </section>
        <section className={cn('min-h-0 min-w-0 flex-1 flex-col md:flex', mobileTab === 'preview' ? 'flex' : 'hidden')}>
          <div className="flex shrink-0 items-center justify-between gap-2 border-b border-border px-4 py-3"><span className="font-medium">{i18nText('app.article.canvas.ai.preview')}</span><span className="text-xs text-text-secondary">{i18nText(draft ? draft.source === appliedSource ? 'app.article.canvas.ai.applied' : 'app.article.canvas.ai.draft' : 'app.article.canvas.ai.current')}</span></div>
          <div className="relative min-h-0 flex-1 bg-bg-layout">
            {draft ? <CanvasAiPreview key={draft.messageId} type={type} source={draft.source} onReady={previewReady} onError={previewError} />
              : preview ? <img src={preview} alt={title} className="size-full object-contain p-4" />
              : <div className="flex h-full flex-col items-center justify-center gap-3 p-6 text-center text-text-secondary"><SparklesIcon className="size-10 text-primary/60" /><p>{i18nText('app.article.canvas.ai.previewHint')}</p></div>}
            {busy && <div role="status" className="absolute right-3 bottom-3 flex items-center gap-2 rounded-lg border border-border bg-bg-container px-3 py-2 shadow-sm"><Loader2Icon className="size-4 animate-spin text-primary" />{i18nText('app.article.canvas.ai.generating')}</div>}
          </div>
          {selectedCandidate?.summary && <p className="m-0 max-h-24 shrink-0 overflow-auto border-t border-border px-4 py-3 text-sm text-text-secondary">{selectedCandidate.summary}</p>}
          <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-t border-border p-4">
            <Button variant="outline" size="sm" disabled={busy || saving || (!!draft && draft.source !== appliedSource)} onClick={onEdit}>{i18nText('app.article.canvas.ai.edit')}</Button>
            <Button size="sm" disabled={busy || saving || !draft || !svg || draft.source === appliedSource} onClick={apply}>{saving && <Loader2Icon className="size-4 animate-spin" />}{i18nText(saving ? 'app.article.canvas.ai.saving' : 'app.article.canvas.ai.apply')}</Button>
          </div>
        </section>
      </div>
      {error && <div role="alert" className="max-h-24 shrink-0 overflow-auto break-words border-t border-border bg-error/5 px-4 py-3 text-sm text-error">{error}</div>}
    </DialogContent>
  </Dialog>;
}
