import {act, cleanup, fireEvent, render, renderHook, screen, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {request} from '@umijs/max';
import {type AssistantTrialCommand} from '@/services/arte-ai/assistant';
import {type AiScope, type ConversationResponse, getInvocationSnapshot} from '@/services/arte-ai';
import AssistantWorkspace from './AssistantWorkspace';
import AssistantTrialPanel from './AssistantTrialPanel';
import BusinessChatPanel from './BusinessChatPanel';
import {useAssistantCommand} from './assistantState';

vi.mock('@umijs/max', () => ({request: vi.fn(), getLocale: () => 'zh-CN'}));
vi.mock('@/services/arte-ai', async original => ({
  ...await original<typeof import('@/services/arte-ai')>(),
  getInvocationSnapshot: vi.fn(),
}));
const scope: AiScope = {tenantId: 'team', workspaceId: 'workspace'};
const context = {userId: 'alice', scope};
const profile = {type: 'chat-profile' as const, id: 'profile-1', version: '1'};
const trial = {trialId: 'trial-1', invocationId: 'invocation-1', draftRevision: 1, createdAt: '2026-11-07T00:00:00Z'};
const reply = (data: unknown, status = 200) => ({status, data: {success: true, code: '200', desc: 'OK', data}});
const trialCommand: AssistantTrialCommand = {
  kind: 'trial', scope, assistantId: 'assistant-1', draftRevision: 1, text: '原始试用输入',
  capability: {type: 'capability', id: 'text', version: '1'},
  binding: {type: 'binding', id: 'model', version: '1'},
  budgetRef: 'own-account', maxInputTokens: 1024, timeoutSeconds: 30,
  generationOptions: {maxOutputTokens: 128, temperature: null, topP: null, stopSequences: []},
};

beforeEach(() => {
  vi.resetAllMocks();
  sessionStorage.clear();
});
afterEach(cleanup);

it('隔离账号与空间，重新挂载后以原请求及原键恢复待确认试用', async () => {
  const accepted = vi.fn();
  vi.mocked(request).mockRejectedValueOnce(new Error('response lost'));
  const first = renderHook(() => useAssistantCommand(context, accepted));
  await act(() => first.result.current.submit(trialCommand));
  expect(first.result.current.pending?.request).toEqual(trialCommand);
  expect(accepted).not.toHaveBeenCalled();
  first.unmount();

  const other = renderHook(() => useAssistantCommand({...context, scope: {...scope, workspaceId: 'other'}}, accepted));
  expect(other.result.current.pending).toBeNull();
  other.unmount();

  vi.mocked(request).mockResolvedValueOnce(reply({
    trialId: trial.trialId, draftRevision: trial.draftRevision,
    execution: {executionId: trial.invocationId, acceptedAt: trial.createdAt},
  }, 202));
  const restored = renderHook(() => useAssistantCommand(context, accepted));
  await act(() => restored.result.current.submit());
  const [initial, retry] = vi.mocked(request).mock.calls as unknown as [string, { headers: unknown; data: unknown }][];
  expect(initial[0]).toBe('/arte/ai-new/assistant/trialAssistantDraft');
  expect(initial[1]?.headers).toEqual(retry[1]?.headers);
  expect(initial[1]?.data).toEqual(retry[1]?.data);
  expect(restored.result.current.pending).toBeNull();
  expect(accepted).toHaveBeenCalledOnce();
});

it('只有当前修订的成功试用能解锁发布', async () => {
  vi.mocked(request).mockResolvedValue(reply({items: [trial], nextCursor: null}));
  vi.mocked(getInvocationSnapshot).mockResolvedValue({
    invocationId: trial.invocationId, missing: false, result: null,
    status: {
      blocksConversation: false, invocationId: trial.invocationId, kind: 'GENERATION', state: 'SUCCEEDED', version: 1,
      budgetState: 'SETTLED', activeAttemptId: null, error: null, resultAvailable: false, partial: null,
      conversation: null, acceptedAt: trial.createdAt, updatedAt: trial.createdAt,
    },
  });
  const onPublish = vi.fn();
  const panel = (revision: number) => <AssistantTrialPanel scope={scope} assistantId="assistant-1" revision={revision}
                                                           complete examples={[]} canTrial={false} canPublish
                                                           alreadyPublished={false} disabled={false}
                                                           accepted={trial} onTrial={vi.fn()} onPublish={onPublish}/>;
  const view = render(panel(2));
  await screen.findByText('费用状态 · 已结算');
  expect((screen.getByRole('button', {name: '发布当前修订'}) as HTMLButtonElement).disabled).toBe(true);
  view.rerender(panel(1));
  fireEvent.click(screen.getByRole('button', {name: '发布当前修订'}));
  fireEvent.click(await screen.findByRole('button', {name: '确认发布'}));
  expect(onPublish).toHaveBeenCalledWith(trial.trialId);
});

it('确认选用传出目录的固定引用，停用时聊天仍可读且屏蔽新执行入口', async () => {
  const example = '请为以下内容整理研究提纲。';
  const assistant = {
    assistantId: 'assistant-1', displayName: '研究提纲助手', description: '基于材料整理提纲',
    chatProfile: profile, examplePrompts: [example]
  };
  vi.mocked(request).mockResolvedValueOnce(reply({items: [assistant], nextCursor: null}));
  const onUse = vi.fn();
  const directory = render(<AssistantWorkspace context={context} writable selectionDisabled={false} onUse={onUse}
                                               onUnsafeChange={vi.fn()} onUpdated={vi.fn()}/>);
  fireEvent.click(await screen.findByRole('button', {name: '使用此版本开启会话'}));
  fireEvent.click(await screen.findByRole('button', {name: '确认开启'}));
  expect(onUse).toHaveBeenCalledWith(assistant, expect.any(Function));
  directory.unmount();

  const conversation: ConversationResponse = {
    conversationId: 'conversation-1',
    title: '研究提纲助手',
    state: 'ACTIVE',
    version: 0,
    chatProfile: profile,
    resources: [],
    createdAt: trial.createdAt,
    updatedAt: trial.createdAt
  };
  vi.mocked(request).mockImplementation(async url => String(url).endsWith('/getConversation')
    ? reply(conversation) : {
      status: 200,
      data: {success: true, code: '200', desc: 'OK', records: [], total: 0, current: 1, size: 20}
    });
  const chat = (unavailable: boolean) => <BusinessChatPanel conversation={conversation} config={null} dirty={false}
                                                            context={context}
                                                            options={[]} t={value => value} onUpdated={vi.fn()}
                                                            onConfigurationRejected={vi.fn()} onChooseModel={vi.fn()}
                                                            onInteraction={vi.fn()} onOpenToolSettings={vi.fn()}
                                                            assistantExamples={[example]}
                                                            assistantUnavailable={unavailable}/>;
  const view = render(chat(false));
  fireEvent.click(await screen.findByRole('button', {name: '填入示例 1'}));
  expect((screen.getByRole('textbox', {name: '输入问题'}) as HTMLTextAreaElement).value).toBe(example + '\n\n');
  expect(screen.queryByRole('button', {name: '工具设置'})).toBeNull();
  expect(vi.mocked(request).mock.calls.some(([url]) => String(url).endsWith('/turnsForChat'))).toBe(false);
  view.rerender(chat(true));
  await waitFor(() => expect(screen.queryByRole('button', {name: '填入示例 1'})).toBeNull());
  expect((screen.getByRole('button', {name: '发送'}) as HTMLButtonElement).disabled).toBe(true);
  expect(screen.getByRole('region', {name: '聊天内容'})).toBeTruthy();
});
