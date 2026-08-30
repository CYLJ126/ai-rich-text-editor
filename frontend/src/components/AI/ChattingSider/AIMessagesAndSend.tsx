import {i18nText} from '@/utils/i18n';
import React, {forwardRef, useCallback, useState} from "react";
import {Input, message, Splitter} from "antd";
import {MessageList} from "@/components";
import {useModelsStore} from "@/stores/modelsStore";
import {useChatData} from "@/components/AI/ChatContext";

export const QUICK_HINTS = [
  i18nText("app.ai.chattingsider.aimessagesandsend.d915adaf"),
  i18nText("app.ai.chattingsider.aimessagesandsend.3c8eef1a"),
  i18nText("app.ai.chattingsider.aimessagesandsend.b3d53dd3"),
  i18nText("app.ai.chattingsider.aimessagesandsend.87cf7332"),
  i18nText("app.ai.chattingsider.aimessagesandsend.58cb572f"),
  i18nText("app.ai.chattingsider.aimessagesandsend.4b73734d"),
  i18nText("app.ai.chattingsider.aimessagesandsend.ad681f39"),
  i18nText("app.ai.chattingsider.aimessagesandsend.5f052c3e"),
  i18nText("app.ai.chattingsider.aimessagesandsend.ffc90320")
];


export interface AIMessagesAndChatRef {

}

export interface AIMessagesAndChatProps {
  height: number;
}

// 消息列表 + 输入框
export const AIMessagesAndSend = forwardRef<AIMessagesAndChatRef, AIMessagesAndChatProps>(({height}, ref) => {
  const getModelById = useModelsStore((state) => state.getModelById);
  const {
    activeConversation,
    isStreaming,
    streamingMessageId,
    sendMessage,
    regenerateMessage,
    retryMessage,
    onRemove,
    messageListRef,
    assistantSiderRef,
    quotedMessageRef,
    extraMenuOperations,
  } = useChatData();
  // 消息列表 vs 输入框 垂直分割尺寸
  const [vertSizes, setVertSizes] = useState<number[]>([height - 115, 115]);
  // 输入内容
  const [inputValue, setInputValue] = useState<string>('');

  // 发送
  const handleSend = useCallback(async () => {
    if (!activeConversation) {
      message.warning(i18nText("app.ai.chattingsider.aimessagesandsend.d302e450")).then();
      return;
    }
    if (!activeConversation.modelId) {
      if (activeConversation.assistantId) {
        const assistant = assistantSiderRef.current?.getList().filter(item => item.id !== activeConversation.assistantId)[0];
        if (!assistant?.modelId) {
          message.warning(i18nText("app.ai.chattingsider.aimessagesandsend.0b329198")).then();
          return;
        }
      } else {
        message.warning(i18nText("app.ai.chattingsider.aimessagesandsend.0b329198")).then();
        return;
      }
    }
    const content = inputValue.trim();
    setInputValue('');
    sendMessage({
      content,
      quotedMessage: quotedMessageRef.current || undefined,
      model: getModelById(activeConversation.modelId) || undefined,
    });
  }, [inputValue, sendMessage, activeConversation, assistantSiderRef.current, quotedMessageRef.current, getModelById]);

  // 键盘快捷键：Enter 发送，Shift+Enter 换行
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
        console.log('触发发送事件')
        e.preventDefault();
        handleSend().then(() => {
          messageListRef.current?.showNewestMessage();
        });
      }
    },
    [handleSend],
  );

  return (<div>
    <Splitter orientation="vertical" style={{height: '100%'}} onResize={setVertSizes}>
      <Splitter.Panel min={120} size={vertSizes[0]}>
        <MessageList
          ref={messageListRef}
          quickHints={QUICK_HINTS}
          currentConv={activeConversation}
          streamingMessageId={streamingMessageId}
          isStreaming={isStreaming}
          onRemove={onRemove}
          onEdit={() => {
            // TODO
          }}
          onQuote={() => {
            // TODO
          }}
          onRetry={retryMessage}
          regenerateMessage={regenerateMessage}
          sendMessage={sendMessage}
          extraMenuOperations={extraMenuOperations}
        />
      </Splitter.Panel>

      <Splitter.Panel size={vertSizes[1]} min={115} className="scrollbar-none p-2">
        <Input.TextArea
          className="overflow-auto scrollbar-none"
          disabled={isStreaming}
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={i18nText("app.ai.chattingsider.aimessagesandsend.ba144aa6")}
          autoSize={{minRows: 4, maxRows: 16}}
        />
      </Splitter.Panel>
    </Splitter>
  </div>)
});
