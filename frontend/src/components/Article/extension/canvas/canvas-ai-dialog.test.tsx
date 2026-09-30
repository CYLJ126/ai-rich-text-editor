import React, {useEffect} from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react';
import type {StreamChatCallbacks} from '@/types/ai.type';
import {i18nText} from '@/utils/i18n';
import {CanvasAiDialog} from './canvas-ai-dialog';

const mocks = vi.hoisted(() => ({
  create: vi.fn(), history: vi.fn(), configure: vi.fn(), upload: vi.fn(), stream: vi.fn(),
  loadModels: vi.fn(),
  model: {id: 7, modelId: 'vision-model', modelName: 'Vision model', status: 1, supportVision: true},
  otherModel: {id: 8, modelId: 'other-model', modelName: 'Other model', status: 1, supportVision: true},
}));
vi.mock('@umijs/max', () => ({useModel: () => ({initialState: {currentUser: {userName: 'tester'}}})}));
vi.mock('antd-style', () => ({createStyles: () => () => ({styles: {workspace: ''}})}));
vi.mock('@/stores/modelsStore', () => ({useModelsStore: (select: (value: unknown) => unknown) => select({models: [mocks.model, mocks.otherModel], loadModels: mocks.loadModels})}));
vi.mock('@/components/AI/ModelSelector', () => ({default: ({onSelect}: {onSelect: (model: unknown) => void}) => {
  useEffect(() => { onSelect(mocks.model); }, []);
  return <span>Existing model selector</span>;
}}));
vi.mock('@/components/AI/ThinkingBlock', () => ({default: () => null}));
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({children}: React.PropsWithChildren) => <div>{children}</div>,
  DialogContent: ({children}: React.PropsWithChildren) => <div>{children}</div>,
  DialogHeader: ({children}: React.PropsWithChildren) => <div>{children}</div>,
  DialogTitle: ({children}: React.PropsWithChildren) => <h2>{children}</h2>,
  DialogDescription: ({children}: React.PropsWithChildren) => <p>{children}</p>,
}));
vi.mock('./canvas-ai-preview', () => ({CanvasAiPreview: ({source, onReady}: {source: string; onReady: (svg: Blob) => void}) => {
  useEffect(() => onReady(new Blob(['<svg/>'], {type: 'image/svg+xml'})), [onReady, source]);
  return <div data-testid="diagram-preview">{source}</div>;
}}));
vi.mock('@/services/ant-design-pro/ai.rbac', () => ({createConversation: mocks.create, listMessages: mocks.history}));
vi.mock('@/services/ant-design-pro/ai.chat', () => ({STREAM_CHAT_URL: '/stream', streamChat: mocks.stream}));
vi.mock('./canvas-ai-service', () => ({configureCanvasConversation: mocks.configure, uploadCanvasChatImage: mocks.upload,
  toCanvasAttachment: (image: {id: number; accessUrl: string}) => ({attachmentId: String(image.id), fileUrl: image.accessUrl}),
}));

const source = JSON.stringify({nodeData: {id: 'root', topic: 'Existing'}});
const generated = JSON.stringify({nodeData: {id: 'root', topic: 'Revised'}});
const text = (key: string) => i18nText(`app.article.canvas.ai.${key}`);
const response = `Updated the plan.\n\n\`\`\`json\n${generated}\n\`\`\``;

function mount(overrides = {}) {
  const props = {type: 'mindmap' as const, title: 'Mind map', conversationId: '', preview: '',
    getSource: vi.fn().mockResolvedValue(source), onConversationCreated: vi.fn().mockResolvedValue(undefined),
    onApply: vi.fn().mockResolvedValue(undefined), onEdit: vi.fn(), onClose: vi.fn(), ...overrides};
  const view = render(<CanvasAiDialog {...props} />);
  return {...view, props};
}
async function sendRequest(value = 'Revise the plan') {
  const input = screen.getByRole('textbox', {name: text('prompt')});
  await waitFor(() => expect(input).not.toBeDisabled());
  fireEvent.change(input, {target: {value}});
  await waitFor(() => expect(screen.getByRole('button', {name: text('send')})).not.toBeDisabled());
  fireEvent.click(screen.getByRole('button', {name: text('send')}));
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.model.supportVision = true;
  mocks.model.status = 1;
  mocks.loadModels.mockResolvedValue([mocks.model, mocks.otherModel]);
  mocks.create.mockResolvedValue({convId: 'diagram-conversation'});
  mocks.history.mockResolvedValue({records: [], total: 0});
  mocks.configure.mockResolvedValue(undefined);
  mocks.upload.mockResolvedValue({attachmentId: '19', fileUrl: '/image.png', fileName: 'sketch.png'});
  mocks.stream.mockImplementation(async (_url: string, _params: unknown, callbacks: StreamChatCallbacks) => callbacks.onContent?.(response));
});
afterEach(cleanup);

describe('canvas AI workflow', () => {
  it('persists the conversation link, uses the selected model and previews before applying', async () => {
    const {props} = mount();
    await sendRequest();
    await screen.findByTestId('diagram-preview');
    expect(mocks.create).toHaveBeenCalledTimes(1);
    expect(props.onConversationCreated).toHaveBeenCalledWith('diagram-conversation', expect.objectContaining({id: 7, modelName: 'Vision model'}));
    expect(mocks.stream.mock.calls[0][1]).toMatchObject({modelId: 7, convId: 'diagram-conversation', enableVision: false});
    expect(mocks.stream.mock.calls[0][1].content).toContain(source);
    expect(props.onApply).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByRole('button', {name: text('apply')})).not.toBeDisabled());
    fireEvent.click(screen.getByRole('button', {name: text('apply')}));
    await waitFor(() => expect(props.onApply).toHaveBeenCalledWith(generated, expect.any(Blob)));
    await waitFor(() => expect(props.onClose).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByRole('button', {name: text('apply')})).toBeDisabled());
    // Selecting the same version must not invalidate its already exported preview.
    fireEvent.click(screen.getByRole('button', {name: text('selected')}));
    expect(screen.getByRole('button', {name: text('edit')})).not.toBeDisabled();
  });
  it('keeps the window open when applying to the article fails', async () => {
    const {props} = mount({onApply: vi.fn().mockRejectedValue(new Error('Article save failed'))});
    await sendRequest();
    await screen.findByTestId('diagram-preview');
    await waitFor(() => expect(screen.getByRole('button', {name: text('apply')})).not.toBeDisabled());
    fireEvent.click(screen.getByRole('button', {name: text('apply')}));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Article save failed'));
    expect(props.onClose).not.toHaveBeenCalled();
    expect(screen.getByRole('button', {name: text('apply')})).not.toBeDisabled();
  });
  it('reopens with the saved first model even when the article uses another model', async () => {
    const {props} = mount({conversationId: 'saved', savedModelId: 8, savedModelName: 'First model', initialModelId: 7});
    await sendRequest();
    await screen.findByTestId('diagram-preview');
    expect(screen.queryByText('Existing model selector')).not.toBeInTheDocument();
    expect(screen.getByText(`${text('model')} · First model`)).toBeInTheDocument();
    expect(mocks.configure).toHaveBeenCalledWith('saved', 8, 'modify');
    expect(mocks.stream.mock.calls[0][1].modelId).toBe(8);
    expect(props.onConversationCreated).toHaveBeenCalledWith('saved', expect.objectContaining({id: 8, modelName: 'First model'}));
    expect(mocks.history).toHaveBeenCalledTimes(1);
  });
  it('recovers a legacy card’s model from the oldest reply instead of the latest reply', async () => {
    mocks.history.mockImplementation(async (params: {orders: {asc: boolean}[]}) => ({records: [
      {messageId: 'past', role: 'assistant', modelId: params.orders[0].asc ? 7 : 8, content: response, status: 'completed'},
    ], total: 1}));
    mount({conversationId: 'legacy', initialModelId: 8});
    await sendRequest();
    await waitFor(() => expect(mocks.stream).toHaveBeenCalled());
    expect(mocks.stream.mock.calls[0][1].modelId).toBe(7);
    expect(screen.queryByText('Existing model selector')).not.toBeInTheDocument();
  });
  it('does not switch a card to a different model if its original model is disabled', async () => {
    mocks.model.status = 3;
    mount({conversationId: 'saved', savedModelId: 7, savedModelName: 'Vision model'});
    await screen.findByText(text('modelUnavailable'));
    fireEvent.change(screen.getByRole('textbox'), {target: {value: 'Continue'}});
    expect(screen.getByRole('button', {name: text('send')})).toBeDisabled();
    expect(mocks.stream).not.toHaveBeenCalled();
  });
  it('locks the first model after sending and explains image restrictions for that card', async () => {
    mocks.model.supportVision = false;
    const {container} = mount();
    expect(screen.getByText('Existing model selector')).toBeInTheDocument();
    await sendRequest();
    await screen.findByTestId('diagram-preview');
    expect(screen.queryByText('Existing model selector')).not.toBeInTheDocument();
    fireEvent.change(container.querySelector('input[type=file]')!, {target: {files: [new File(['image'], 'sketch.png', {type: 'image/png'})]}});
    fireEvent.change(screen.getByRole('textbox'), {target: {value: 'Continue'}});
    expect(screen.getByText(text('fixedVisionRequired'))).toBeInTheDocument();
    expect(screen.getByRole('button', {name: text('send')})).toBeDisabled();
  });
  it('uses the latest generated source for the next change in the same conversation', async () => {
    mount();
    await sendRequest();
    await screen.findByTestId('diagram-preview');
    await sendRequest('Add testing');
    await waitFor(() => expect(mocks.stream).toHaveBeenCalledTimes(2));
    expect(mocks.create).toHaveBeenCalledTimes(1);
    expect(mocks.stream.mock.calls[1][1].content).toContain(generated);
    expect(mocks.configure).toHaveBeenLastCalledWith('diagram-conversation', 7, 'modify');
  });
  it('clears prior diagram context for a fresh generation', async () => {
    mount();
    await waitFor(() => expect(screen.getByRole('button', {name: text('create')})).not.toBeDisabled());
    fireEvent.click(screen.getByRole('button', {name: text('create')}));
    await sendRequest('A new diagram');
    await screen.findByTestId('diagram-preview');
    expect(mocks.configure).toHaveBeenCalledWith('diagram-conversation', 7, 'create');
    expect(mocks.stream.mock.calls[0][1].content).not.toContain(source);
  });
  it('restores history and previewable versions when reopened', async () => {
    mocks.history.mockResolvedValue({records: [{id: 1, messageId: 'past', convId: 'saved', role: 'assistant', content: response, status: 'completed'}], total: 1});
    mount({conversationId: 'saved'});
    const version = await screen.findByRole('button', {name: text('viewVersion')});
    fireEvent.click(version);
    expect(await screen.findByTestId('diagram-preview')).toHaveTextContent(generated);
    expect(mocks.create).not.toHaveBeenCalled();
  });
  it('uploads image input as real attachment IDs and automatically enables vision', async () => {
    const {container} = mount();
    await waitFor(() => expect(screen.getByRole('textbox')).not.toBeDisabled());
    fireEvent.change(container.querySelector('input[type=file]')!, {target: {files: [new File(['image'], 'sketch.png', {type: 'image/png'})]}});
    await sendRequest();
    await screen.findByTestId('diagram-preview');
    expect(mocks.upload).toHaveBeenCalledWith(expect.any(File), 'diagram-conversation', expect.any(String));
    expect(mocks.stream.mock.calls[0][1]).toMatchObject({attachmentIds: ['19'], enableVision: true});
  });
  it('blocks sending images to a model without vision', async () => {
    mocks.model.supportVision = false;
    const {container} = mount();
    await waitFor(() => expect(screen.getByRole('textbox')).not.toBeDisabled());
    fireEvent.change(container.querySelector('input[type=file]')!, {target: {files: [new File(['image'], 'sketch.png', {type: 'image/png'})]}});
    fireEvent.change(screen.getByRole('textbox'), {target: {value: 'Draw this'}});
    expect(screen.getByRole('button', {name: text('send')})).toBeDisabled();
    expect(screen.getByText(text('visionRequired'))).toBeInTheDocument();
    expect(mocks.stream).not.toHaveBeenCalled();
  });
  it('keeps the previous preview and does not apply a failed generation', async () => {
    const {props} = mount();
    await sendRequest();
    await screen.findByTestId('diagram-preview');
    mocks.stream.mockImplementationOnce(async (_url: string, _params: unknown, callbacks: StreamChatCallbacks) => callbacks.onError?.({message: 'Provider error'}));
    await sendRequest('More details');
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Provider error'));
    expect(screen.getByTestId('diagram-preview')).toHaveTextContent(generated);
    expect(props.onApply).not.toHaveBeenCalled();
  });
  it('does not call the model when persisting the article link fails', async () => {
    mount({onConversationCreated: vi.fn().mockRejectedValue(new Error('Save failed'))});
    await sendRequest();
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Save failed'));
    expect(mocks.stream).not.toHaveBeenCalled();
    expect(screen.getByRole('textbox')).toHaveValue('Revise the plan');
  });
  it('blocks generation if the current source or history could not be loaded', async () => {
    mount({getSource: vi.fn().mockRejectedValue(new Error('Source unavailable'))});
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Source unavailable'));
    fireEvent.change(screen.getByRole('textbox'), {target: {value: 'Modify'}});
    expect(screen.getByRole('button', {name: text('send')})).toBeDisabled();
  });
  it('stops the stream without replacing or applying the diagram', async () => {
    mocks.stream.mockImplementation((_url: string, _params: unknown, callbacks: StreamChatCallbacks, signal: AbortSignal) => {
      callbacks.onContent?.('Partial response');
      return new Promise((_, reject) => signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))));
    });
    const {props} = mount();
    await sendRequest();
    await waitFor(() => expect(mocks.stream).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', {name: text('stop')}));
    await waitFor(() => expect(screen.getByText(text('stopped'))).toBeInTheDocument());
    expect(props.onApply).not.toHaveBeenCalled();
    expect(screen.queryByTestId('diagram-preview')).not.toBeInTheDocument();
  });
  it('allows a clarification conversation before a new diagram is generated', async () => {
    mocks.stream.mockImplementationOnce(async (_url: string, _params: unknown, callbacks: StreamChatCallbacks) => callbacks.onContent?.('Which branches should be included?'));
    mount({getSource: vi.fn().mockResolvedValue('')});
    await sendRequest('A new diagram');
    await screen.findByText('Which branches should be included?');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await sendRequest('Include testing and launch');
    await screen.findByTestId('diagram-preview');
    expect(mocks.configure).toHaveBeenNthCalledWith(1, 'diagram-conversation', 7, 'create');
    expect(mocks.configure).toHaveBeenNthCalledWith(2, 'diagram-conversation', 7, 'modify');
  });
  it('keeps clarification history and its reference image after reopening an empty card', async () => {
    mocks.history.mockResolvedValue({records: [
      {messageId: 'reference', role: 'user', content: 'Draw this', status: 'completed', attachments: [{id: 19, accessUrl: '/image.png'}]},
      {messageId: 'question', role: 'assistant', content: 'Which branches?', modelId: 7, status: 'completed'},
    ], total: 2});
    mount({conversationId: 'saved', savedModelId: 7, getSource: vi.fn().mockResolvedValue('')});
    await sendRequest('Include testing and launch');
    await screen.findByTestId('diagram-preview');
    expect(mocks.configure).toHaveBeenCalledWith('saved', 7, 'modify');
    expect(mocks.stream.mock.calls[0][1].enableVision).toBe(true);
  });
  it('does not save or send a conversation that was stopped while being created', async () => {
    let finishCreate!: (value: {convId: string}) => void;
    mocks.create.mockImplementationOnce(() => new Promise(resolve => { finishCreate = resolve; }));
    const {props} = mount();
    await sendRequest();
    await waitFor(() => expect(mocks.create).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', {name: text('stop')}));
    finishCreate({convId: 'diagram-conversation'});
    await waitFor(() => expect(screen.getByRole('button', {name: text('send')})).not.toBeDisabled());
    expect(props.onConversationCreated).not.toHaveBeenCalled();
    expect(mocks.configure).not.toHaveBeenCalled();
    expect(mocks.stream).not.toHaveBeenCalled();
  });
  it('restores the selected version as context after editing a failed request', async () => {
    mount();
    await sendRequest();
    await screen.findByTestId('diagram-preview');
    mocks.stream.mockImplementationOnce(async (_url: string, _params: unknown, callbacks: StreamChatCallbacks) => callbacks.onError?.({message: 'Provider error'}));
    fireEvent.click(screen.getByRole('button', {name: text('create')}));
    await sendRequest('Start over');
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Provider error'));
    fireEvent.click(screen.getByRole('button', {name: text('retry')}));
    fireEvent.click(screen.getByRole('button', {name: text('selected')}));
    await sendRequest('Continue this version');
    await waitFor(() => expect(mocks.stream).toHaveBeenCalledTimes(3));
    expect(mocks.stream.mock.calls[2][1].content).toContain(generated);
    expect(mocks.configure).toHaveBeenLastCalledWith('diagram-conversation', 7, 'modify');
  });
  it('restores image input when editing a failed request', async () => {
    mocks.stream.mockImplementationOnce(async (_url: string, _params: unknown, callbacks: StreamChatCallbacks) => callbacks.onError?.({message: 'Provider error'}));
    const {container} = mount();
    await waitFor(() => expect(screen.getByRole('textbox')).not.toBeDisabled());
    fireEvent.change(container.querySelector('input[type=file]')!, {target: {files: [new File(['image'], 'sketch.png', {type: 'image/png'})]}});
    await sendRequest();
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Provider error'));
    fireEvent.click(screen.getByRole('button', {name: text('retry')}));
    expect(screen.getByText('sketch.png')).toBeInTheDocument();
    await sendRequest('Try again');
    await screen.findByTestId('diagram-preview');
    expect(mocks.upload).toHaveBeenCalledTimes(2);
  });
  it('runs the same preview-and-apply workflow for Draw.io XML', async () => {
    const xml = '<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/></root></mxGraphModel>';
    mocks.stream.mockImplementationOnce(async (_url: string, _params: unknown, callbacks: StreamChatCallbacks) => callbacks.onContent?.(`\`\`\`xml\n${xml}\n\`\`\``));
    const {props} = mount({type: 'drawio', getSource: vi.fn().mockResolvedValue('')});
    await sendRequest('Create a flowchart');
    expect(await screen.findByTestId('diagram-preview')).toHaveTextContent(xml);
    expect(mocks.create.mock.calls[0][0].scene).toBe('article_drawio');
    await waitFor(() => expect(screen.getByRole('button', {name: text('apply')})).not.toBeDisabled());
    fireEvent.click(screen.getByRole('button', {name: text('apply')}));
    await waitFor(() => expect(props.onApply).toHaveBeenCalledWith(xml, expect.any(Blob)));
  });
});
