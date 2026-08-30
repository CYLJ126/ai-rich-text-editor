import {Tabs} from 'antd';
import {createStyles} from 'antd-style';
import React, {forwardRef, useImperativeHandle, useState} from 'react';
import {AssistantSider, ConversationEditSider, ConversationSider,} from '@/components';
import {createConversation} from '@/services/ant-design-pro/ai.rbac';
import type {Conversation, ConversationUpsertDto} from '@/types/ai.type';
import {AIMessagesAndSend} from '@/components/AI';
import {useChatData} from "@/components/AI/ChatContext";

export interface AIChatHandleRef {
  openArticleChat: () => Promise<void>;
}

export interface AIChatProps {
  height: number;
}

const useStyles = createStyles(
  ({ css }, { height }: { height: number | undefined }) => ({
    tabs: css`
    .ant-tabs-nav {
      margin-bottom: 0;

      .ant-tabs-nav-list {
        padding: 0 6px 0 6px;

        .ant-tabs-tab {
          height: 26px;
          padding: 2px 3px 2px 3px;
        }
      }
    }

    .ant-tabs-content {
      height: ${height}px;
    }
  `,
  }),
);

// 聊天侧边栏：聚合 AI 会话列表、会话配置、助手配置、消息列表（含聊天组件），用于其他功能的侧边栏 AI 辅助
export const ChattingSider = forwardRef<AIChatHandleRef, AIChatProps>(
  ({ height }, ref) => {
    const { styles } = useStyles({ height: height - 26 });
    const {
      conversationSiderRef,
      assistantSiderRef,
      activeConversation,
      setActiveConversation,
      updateConversation,
    } = useChatData();
    // 当前选中的 tab
    const [activePanel, setActivePanel] = useState<string>('conversationList');

    useImperativeHandle(
      ref,
      () => ({
        openArticleChat: async () => {
          const result =
            await conversationSiderRef.current?.selectFirstOrCreate();
          if (!result) return;
          setActiveConversation(result.conversation);
          setActivePanel(result.created ? 'conversationConfig' : 'messageList');
        },
      }),
      [conversationSiderRef, setActiveConversation],
    );

    const tabs = [
      {
        label: '会话列表',
        key: 'conversationList',
        children: (
          <ConversationSider
            ref={conversationSiderRef}
            searchParam={{ scene: 'basic_writing_chat' }}
            onCreate={() =>
              createConversation({
                title: '新对话',
                convId: '',
                scene: 'basic_writing_chat',
              } as ConversationUpsertDto)
            }
            onEdit={(conversation: Conversation) => {
              setActiveConversation(conversation);
              setActivePanel('conversationConfig');
            }}
            onClick={(conversation: Conversation) => {
              setActiveConversation(conversation);
              setActivePanel('conversationConfig');
            }}
            onDoubleClick={(conversation: Conversation) => {
              setActiveConversation(conversation);
              setActivePanel('messageList');
            }}
          />
        ),
      },
      {
        label: '会话配置',
        key: 'conversationConfig',
        children: (
          <ConversationEditSider
            conversation={activeConversation}
            onUpdate={updateConversation}
          />
        ),
      },
      {
        label: '助手配置',
        key: 'assistantConfig',
        children: <AssistantSider ref={assistantSiderRef} />,
      },
      {
        label: '消息列表',
        key: 'messageList',
        children: <AIMessagesAndSend height={height - 26} />,
      },
    ];

    return (
      <div style={{ height }} className="scrollbar-none overflow-auto">
        <Tabs
          animated
          activeKey={activePanel}
          onChange={setActivePanel}
          items={tabs}
          className={styles.tabs}
        />
      </div>
    );
  },
);

export default ChattingSider;
