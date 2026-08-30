import { i18nText } from '@/utils/i18n';
import { useModel } from '@@/exports';
import { App } from 'antd';
import React, {
  createContext,
  type Dispatch,
  type RefObject,
  type SetStateAction,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
} from 'react';
import { STREAM_CHAT_URL, streamChat } from '@/services/ant-design-pro/ai.chat';
import type {
  Conversation,
  ConversationUpsertDto,
  Message,
  SendMessageParams,
} from '@/types/ai.type';
import { generateRandomUUID } from '@/utils/RandomUtil';
import type { RightSiderItem } from '../MyRightSiderPanel';
import type { AssistantSiderRef } from './AssistantSider';
import type { ConversationSiderRef } from './ConversationSider';
import type { MessageListRef, MessageOperationProps } from './MessageList';

export interface ChatRequestContext {
  type: 'send' | 'retry'; // 消息类型
  activeConversation: Conversation; // 当前会话
  currentUser: any; // 当前用户
  sendParams?: SendMessageParams; // 发送消息参数
  userMessage: Message; // 用户消息
  failedMessage?: Message; // 失败消息
  siderParams: Record<string, any>; // 侧边栏参数
}

export interface ChatContextType {
  activeConversation: Conversation; // 当前会话
  setActiveConversation: Dispatch<SetStateAction<Conversation>>; // 设置当前会话
  streamingMessageId: string; // 流式消息ID
  setStreamingMessageId: Dispatch<SetStateAction<string>>; // 设置流式消息ID
  isStreaming: boolean; // 是否正在流式输出
  setIsStreaming: Dispatch<SetStateAction<boolean>>; // 设置是否正在流式输出
  abortControllerRef: RefObject<AbortController | null>; // 中断控制器引用
  messageListRef: RefObject<MessageListRef | null>; // 消息列表引用
  conversationSiderRef: RefObject<ConversationSiderRef | null>; // 会话侧边栏引用
  assistantSiderRef: RefObject<AssistantSiderRef | null>; // 助手侧边栏引用
  siderParamsRef: RefObject<Record<string, any>>; // 侧边栏参数引用
  quotedMessageRef: RefObject<{ messageId: string; content: string }>; // 引用消息引用
  extraMenuOperations?: MessageOperationProps[]; // 额外消息操作按钮
  sendMessage: (params: SendMessageParams) => void; // 发送消息
  regenerateMessage: (message: Message) => void; // 重新生成 AI 消息
  retryMessage: (failedMessage: Message) => void; // 重试消息
  onRemove: (message: Message) => void; // 删除消息
  updateConversation: (upsert: ConversationUpsertDto) => void; // 更新会话
  stopStreaming: () => void; // 停止流式输出
  currentUser: any; // 当前用户
}

interface ChatProviderProps {
  children: React.ReactNode; // 子元素
  extraMenuOperations?: MessageOperationProps[]; // 额外消息操作按钮
  buildRequest?: (context: ChatRequestContext) => Record<string, any>; // 构建请求参数
  createMessageTime?: () => string; // 创建消息时间
  getAssistantModelId?: (
    params: SendMessageParams,
    activeConversation: Conversation,
  ) => number | undefined; // 获取助手模型 ID
}

const ChatContext = createContext<ChatContextType | undefined>(undefined);
const REGENERATE_CHAT_URL = '/arte/ai/chat/regenerate';
type StreamContentField = 'content' | 'optimizedContent';

// 构建默认请求参数
const defaultBuildRequest = ({
  type,
  activeConversation,
  currentUser,
  sendParams,
  userMessage,
  failedMessage,
  siderParams,
}: ChatRequestContext) => ({
  convId: activeConversation.convId,
  content: type === 'send' ? sendParams?.content : userMessage.content,
  modelId: type === 'send' ? sendParams?.model?.id : failedMessage?.modelId,
  quotedMessageId:
    type === 'send'
      ? sendParams?.quotedMessage?.messageId
      : userMessage.quotedMessageId,
  attachments:
    type === 'send' ? sendParams?.attachments : userMessage.attachments,
  userName: currentUser?.userName ?? '',
  enableSearch: sendParams?.enableSearch,
  enableVision: sendParams?.enableVision,
  enableThinking: sendParams?.enableThinking,
  generateImage: sendParams?.generateImage,
  ...siderParams,
});

export function ChatProvider({
  children,
  extraMenuOperations,
  buildRequest = defaultBuildRequest,
  createMessageTime = () => new Date().toISOString(),
  getAssistantModelId = (params) => params.model?.id,
}: ChatProviderProps) {
  const { message } = App.useApp();
  const { initialState } = useModel('@@initialState');
  // 当前用户
  const { currentUser }: any = initialState ?? {};
  // 当前会话
  const [activeConversation, setActiveConversation] = useState<Conversation>(
    {} as Conversation,
  );
  // 流式消息 ID
  const [streamingMessageId, setStreamingMessageId] = useState('');
  // 是否正在流式输出
  const [isStreaming, setIsStreaming] = useState(false);
  // 相关引用
  const abortControllerRef = useRef<AbortController | null>(null);
  const messageListRef = useRef<MessageListRef>(null);
  const conversationSiderRef = useRef<ConversationSiderRef>(null);
  const assistantSiderRef = useRef<AssistantSiderRef>(null);
  const siderParamsRef = useRef<Record<string, any>>({});
  const quotedMessageRef = useRef<{ messageId: string; content: string }>({
    messageId: '',
    content: '',
  });

  // 更新会话
  const updateConversation = useCallback(
    (upsert: ConversationUpsertDto) => {
      conversationSiderRef.current?.setList((prev) =>
        prev.map((conversation: RightSiderItem) =>
          conversation.origin.convId === upsert.convId
            ? {
                ...conversation,
                ...upsert,
                origin: { ...conversation.origin, ...upsert },
              }
            : conversation,
        ),
      );
      if (activeConversation.convId === upsert.convId) {
        setActiveConversation((prev) => ({ ...prev, ...upsert }));
      }
    },
    [activeConversation.convId],
  );

  // 流式聊天
  const doStreamChat = useCallback(
    (
      params: Record<string, any>,
      placeholderId: string,
      streamUrl = STREAM_CHAT_URL,
      contentField: StreamContentField = 'content',
      failureFallback?: Partial<Message>,
    ) => {
      const currentMessageIdRef = { current: placeholderId };
      const streamFailedRef = { current: false };
      const controller = new AbortController();
      abortControllerRef.current = controller;
      setIsStreaming(true);
      setStreamingMessageId(placeholderId);

      // 调用流式聊天 API
      streamChat(
        streamUrl,
        params,
        {
          onMessageId: (realId: string) => {
            currentMessageIdRef.current = realId;
            setStreamingMessageId(realId);
            messageListRef.current?.setMessages((prev) =>
              prev.map((item) =>
                item.messageId === placeholderId
                  ? { ...item, messageId: realId }
                  : item,
              ),
            );
          },
          onThinking: (delta: string) => {
            if (contentField === 'optimizedContent') return;
            const id = currentMessageIdRef.current;
            messageListRef.current?.setMessages((prev) =>
              prev.map((item) =>
                item.messageId === id || item.messageId === placeholderId
                  ? {
                      ...item,
                      reasoningContent: (item.reasoningContent ?? '') + delta,
                    }
                  : item,
              ),
            );
          },
          onContent: (delta: string) => {
            const id = currentMessageIdRef.current;
            messageListRef.current?.setMessages((prev) =>
              prev.map((item) =>
                item.messageId === id || item.messageId === placeholderId
                  ? {
                      ...item,
                      [contentField]: (item[contentField] ?? '') + delta,
                      status: 'streaming',
                    }
                  : item,
              ),
            );
          },
          onMetadata: (metadata) => {
            if (contentField === 'optimizedContent') return;
            const id = currentMessageIdRef.current;
            if (!metadata) return;
            messageListRef.current?.setMessages((prev) =>
              prev.map((item) =>
                item.messageId === id
                  ? {
                      ...item,
                      promptToken: metadata.promptTokens,
                      completionToken: metadata.completionTokens,
                      totalToken: metadata.totalTokens,
                    }
                  : item,
              ),
            );
          },
          onDone: () => {
            if (streamFailedRef.current) return;
            const id = currentMessageIdRef.current;
            messageListRef.current?.setMessages((prev) =>
              prev.map((item) =>
                item.messageId === id ? { ...item, status: 'completed' } : item,
              ),
            );
            setIsStreaming(false);
            setStreamingMessageId('');
            abortControllerRef.current = null;
          },
          onError: (error) => {
            streamFailedRef.current = true;
            const id = currentMessageIdRef.current;
            console.log('onError: ', error);
            messageListRef.current?.setMessages((prev) =>
              prev.map((item) =>
                item.messageId === id
                  ? {
                      ...item,
                      status: 'failed',
                      errorMessage:
                        error?.message ??
                        i18nText('app.ai.ai.chatcontext.4de0880b'),
                      ...failureFallback,
                    }
                  : item,
              ),
            );
            setIsStreaming(false);
            setStreamingMessageId('');
            abortControllerRef.current = null;
            message
              .error(
                i18nText('app.ai.ai.chatcontext.8bf7b2ac', {
                  value0:
                    error?.message ??
                    i18nText('app.ai.ai.chatcontext.906732b5'),
                }),
              )
              .then();
          },
        },
        controller.signal,
      ).catch((error: unknown) => {
        if ((error as Error)?.name === 'AbortError') return;
        const id = currentMessageIdRef.current;
        messageListRef.current?.setMessages((prev) =>
          prev.map((item) =>
            item.messageId === id
              ? {
                  ...item,
                  status: 'failed',
                  errorMessage:
                    (error as Error)?.message ??
                    i18nText('app.ai.ai.chatcontext.906732b5'),
                  ...failureFallback,
                }
              : item,
          ),
        );
        setIsStreaming(false);
        setStreamingMessageId('');
        abortControllerRef.current = null;
      });
    },
    [message],
  );

  // 发送消息
  const sendMessage = useCallback(
    (params: SendMessageParams) => {
      if (!activeConversation.convId) return;

      const temporaryUserId = generateRandomUUID(32);
      const temporaryAssistantId = generateRandomUUID(32);
      const createTime = createMessageTime();
      const userMessage: Message = {
        messageId: temporaryUserId,
        convId: activeConversation.convId,
        role: 'user',
        content: params.content ?? '',
        createTime,
        status: 'completed',
        attachments: params.attachments,
        quotedMessageId: params.quotedMessage?.messageId,
        quotedSnapshot: params.quotedMessage?.content,
      };
      const assistantMessage: Message = {
        messageId: temporaryAssistantId,
        convId: activeConversation.convId,
        role: 'assistant',
        content: '',
        reasoningContent: '',
        createTime,
        status: 'streaming',
        modelId: getAssistantModelId(params, activeConversation),
      };

      // 添加用户消息和助手消息
      messageListRef.current?.setMessages((prev) => [
        ...prev,
        userMessage,
        assistantMessage,
      ]);
      // 调用流式聊天 API
      doStreamChat(
        {
          ...buildRequest({
            type: 'send',
            activeConversation,
            currentUser,
            sendParams: params,
            userMessage,
            siderParams: siderParamsRef.current,
          }),
          userMessageId: temporaryUserId,
          assistantMessageId: temporaryAssistantId,
        },
        temporaryAssistantId,
      );
    },
    [
      activeConversation,
      buildRequest,
      createMessageTime,
      currentUser,
      doStreamChat,
      getAssistantModelId,
    ],
  );

  // 重试消息
  const retryMessage = useCallback(
    (failedMessage: Message) => {
      if (!activeConversation.convId || isStreaming) return;

      const allMessages = messageListRef.current?.getMessages() ?? [];
      const failedIndex = allMessages.findIndex(
        (item) => item.messageId === failedMessage.messageId,
      );
      if (failedIndex < 0) return;

      let userMessage: Message | undefined;
      for (let index = failedIndex - 1; index >= 0; index -= 1) {
        if (allMessages[index].role === 'user') {
          userMessage = allMessages[index];
          break;
        }
      }
      if (!userMessage) {
        message.warning(i18nText('app.ai.ai.chatcontext.59457b9c')).then();
        return;
      }

      const targetId = failedMessage.messageId;
      messageListRef.current?.setMessages((prev) =>
        prev.map((item) =>
          item.messageId === targetId
            ? {
                ...item,
                content: '',
                reasoningContent: '',
                errorMessage: undefined,
                status: 'streaming',
              }
            : item,
        ),
      );
      doStreamChat(
        buildRequest({
          type: 'retry',
          activeConversation,
          currentUser,
          userMessage,
          failedMessage,
          siderParams: siderParamsRef.current,
        }),
        targetId,
      );
    },
    [
      activeConversation,
      buildRequest,
      currentUser,
      doStreamChat,
      isStreaming,
      message,
    ],
  );

  // 重新生成 AI 消息
  const regenerateMessage = useCallback(
    (assistantMessage: Message) => {
      if (
        assistantMessage.role !== 'assistant' ||
        !activeConversation.convId ||
        isStreaming
      ) {
        return;
      }

      const targetId = assistantMessage.messageId;
      messageListRef.current?.setMessages((prev) =>
        prev.map((item) =>
          item.messageId === targetId
            ? {
                ...item,
                optimizedContent: '',
                errorMessage: undefined,
                status: 'streaming',
              }
            : item,
        ),
      );
      doStreamChat(
        {
          messageId: targetId,
          userName: currentUser?.userName ?? '',
        },
        targetId,
        REGENERATE_CHAT_URL,
        'optimizedContent',
        {
          optimizedContent: assistantMessage.optimizedContent,
          errorMessage: assistantMessage.errorMessage,
          status: assistantMessage.status,
        },
      );
    },
    [activeConversation.convId, currentUser, doStreamChat, isStreaming],
  );

  // 删除消息
  const onRemove = useCallback(
    (removedMessage: Message) => {
      conversationSiderRef.current?.setList((prev) =>
        prev.map((conversation: RightSiderItem) =>
          conversation.origin.convId === removedMessage.convId
            ? {
                ...conversation,
                origin: {
                  ...conversation.origin,
                  messageCount: conversation.origin.messageCount - 1,
                },
              }
            : conversation,
        ),
      );
      if (activeConversation.convId === removedMessage.convId) {
        setActiveConversation((prev) => ({
          ...prev,
          messageCount: (prev.messageCount || 1) - 1,
        }));
      }
    },
    [activeConversation.convId],
  );

  // 停止流式聊天
  const stopStreaming = useCallback(() => {
    abortControllerRef.current?.abort();
    messageListRef.current?.stopStreaming();
    setIsStreaming(false);
    setStreamingMessageId('');
    abortControllerRef.current = null;
  }, []);

  const value = useMemo<ChatContextType>(
    () => ({
      activeConversation,
      setActiveConversation,
      streamingMessageId,
      setStreamingMessageId,
      isStreaming,
      setIsStreaming,
      abortControllerRef,
      messageListRef,
      conversationSiderRef,
      assistantSiderRef,
      siderParamsRef,
      quotedMessageRef,
      extraMenuOperations,
      sendMessage,
      regenerateMessage,
      retryMessage,
      onRemove,
      updateConversation,
      stopStreaming,
      currentUser,
    }),
    [
      activeConversation,
      currentUser,
      extraMenuOperations,
      isStreaming,
      onRemove,
      regenerateMessage,
      retryMessage,
      sendMessage,
      stopStreaming,
      streamingMessageId,
      updateConversation,
    ],
  );

  return <ChatContext.Provider value={value}>{children}</ChatContext.Provider>;
}

export function useChatData() {
  const context = useContext(ChatContext);
  if (!context) {
    throw new Error('useChatData must be used within a ChatProvider');
  }
  return context;
}
