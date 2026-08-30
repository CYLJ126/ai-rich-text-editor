import {AppstoreOutlined, MessageOutlined, ReadOutlined, RobotOutlined, SettingOutlined,} from '@ant-design/icons';
import {Splitter} from 'antd';
import {createStyles} from 'antd-style';
import React, {useCallback, useMemo, useRef, useState} from 'react';
import {
  AssistantSider,
  ChatInput,
  ConversationEditSider,
  ConversationSider,
  MessageList,
  ModelSider,
  RagSider,
  RightSidebar,
  type SidePanel,
} from '@/components';
import {ChatProvider, useChatData} from '@/components/AI/ChatContext';
import {DEFAULT_CHAT_INPUT_HEIGHT} from '@/components/AI/ChatInput';
import {createConversation} from '@/services/ant-design-pro/ai.rbac';
import type {Conversation, ConversationUpsertDto} from '@/types/ai.type';
import {useComponentHeight} from '@/utils/useDynamicHeight';

// ─── 常量 ───
const FLOAT_BTN_SIZE = 28; // 悬浮按钮默认尺寸
const DEFAULT_LEFT_SIZE = 300; // 默认左侧面板尺寸
const DEFAULT_RIGHT_SIZE = 300; // 默认右侧面板尺寸

const useStyles = createStyles(({ token, css }) => ({
  // 最外层容器
  layout: css`
    height: 100%;
    background: ${token.colorBgLayout};
    border-radius: ${token.borderRadiusLG}px;
    overflow: hidden;
    border: 1px solid ${token.colorBorderSecondary};
    position: relative; /* 悬浮按钮基于此定位 */
  `,

  // 水平 Splitter 撑满容器
  horizontalSplitter: css`
    height: 100%;
    width: 100%;
  `,

  // 左侧面板
  leftPanel: css`
    height: 100%;
    background: ${token.colorBgContainer};
    border-right: 1px solid ${token.colorBorderSecondary};
    display: flex;
    flex-direction: column;
    overflow: hidden;
  `,

  // 中间面板
  centerPanel: css`
    height: 100%;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    background: ${token.colorBgLayout};
    position: relative;
  `,

  // ── 通用悬浮按钮 ───
  floatBtn: css`
    position: absolute;
    top: 50%;
    transform: translateY(-50%);
    z-index: 100;
    width: ${FLOAT_BTN_SIZE}px;
    height: ${FLOAT_BTN_SIZE * 2}px;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    background: ${token.colorBgElevated};
    border: 1px solid ${token.colorBorderSecondary};
    box-shadow: ${token.boxShadowSecondary};
    color: ${token.colorTextSecondary};
    transition: all 0.2s;
    user-select: none;

    &:hover {
      color: ${token.colorPrimary};
      background: ${token.colorBgTextHover};
      box-shadow: ${token.boxShadow};
    }

    /* 左侧按钮：贴左边，右半圆 */

    &.float-left {
      left: 0;
      border-left: none;
      border-radius: 0 ${FLOAT_BTN_SIZE}px ${FLOAT_BTN_SIZE}px 0;
    }

    /* 右侧按钮：贴右边，左半圆 */

    &.float-right {
      right: 0;
      border-right: none;
      border-radius: ${FLOAT_BTN_SIZE}px 0 0 ${FLOAT_BTN_SIZE}px;
    }
  `,
}));

function ChatManagementContent() {
  const { styles } = useStyles();
  const {
    activeConversation,
    setActiveConversation,
    streamingMessageId,
    isStreaming,
    messageListRef,
    conversationSiderRef,
    siderParamsRef,
    extraMenuOperations,
    sendMessage,
    regenerateMessage,
    retryMessage,
    onRemove,
    updateConversation,
    stopStreaming,
  } = useChatData();
  // ─── 当前组件的页面高度 ───
  const pageHeight = useComponentHeight(40, 500);
  const [activePanel, setActivePanel] = useState('conversation');

  // ── 中间区域垂直分割尺寸（消息列表 vs 输入框）───
  const [vertSizes, setVertSizes] = useState<number[]>([
    pageHeight - DEFAULT_CHAT_INPUT_HEIGHT,
    DEFAULT_CHAT_INPUT_HEIGHT,
  ]);

  // ─── 中间区域高度变化调整 ───
  const onHeightChange = useCallback(
    (chatInputHeight: number) => {
      setVertSizes([pageHeight - chatInputHeight, chatInputHeight]);
    },
    [pageHeight],
  );

  // ── 水平分割尺寸（左 / 中 / 右）受控，用于程序化展开折叠面板 ───
  const [horizSizes, setHorizSizes] = useState<(number | string)[]>([
    DEFAULT_LEFT_SIZE,
    '100%', // 中间弹性占满剩余
    DEFAULT_RIGHT_SIZE,
  ]);

  // ── 折叠状态 ───
  const [leftCollapsed, setLeftCollapsed] = useState(false);
  const [rightCollapsed, setRightCollapsed] = useState(false);

  // 记录折叠前的宽度，以便还原
  const prevLeftSize = useRef<number>(DEFAULT_LEFT_SIZE);
  const prevRightSize = useRef<number>(DEFAULT_RIGHT_SIZE);

  /**
   * 面板折叠展开回调
   * Splitter onCollapse 回调
   * collapsed: boolean[]  —— 每个 panel 是否处于折叠状态
   * sizes: number[]       —— 折叠后各 panel 的像素尺寸
   */
  const handleCollapse = useCallback(
    (collapsed: boolean[], sizes: number[]) => {
      const [lc, , rc] = collapsed; // 左、中、右
      const [ls, , rs] = sizes;

      // ── 左侧 ──
      if (lc !== leftCollapsed) {
        if (!lc) {
          // 从折叠 → 展开（通过 Splitter 自身拖动展开）
          prevLeftSize.current = ls || DEFAULT_LEFT_SIZE;
        }
        setLeftCollapsed(lc);
      }

      // ── 右侧 ──
      if (rc !== rightCollapsed) {
        if (!rc) {
          prevRightSize.current = rs || DEFAULT_RIGHT_SIZE;
        }
        setRightCollapsed(rc);
      }
    },
    [leftCollapsed, rightCollapsed],
  );

  // ── 点击左侧悬浮按钮：恢复左侧面板 ───
  const expandLeft = useCallback(() => {
    setHorizSizes([prevLeftSize.current, '100%', horizSizes[2]]);
    setLeftCollapsed(false);
  }, [horizSizes]);

  // ── 点击右侧悬浮按钮：恢复右侧面板 ───
  const expandRight = useCallback(() => {
    setHorizSizes([horizSizes[0], '100%', prevRightSize.current]);
    setRightCollapsed(false);
  }, [horizSizes]);

  // ── 右侧面板 Tab 配置 ───
  const rightSidePanels = useMemo<SidePanel[]>(
    () => [
      {
        id: 'conversation',
        name: '会话',
        icon: <MessageOutlined />,
        noPadding: true,
        component: (
          <ConversationEditSider
            conversation={activeConversation}
            onUpdate={updateConversation}
          />
        ),
      },
      {
        id: 'assistant',
        name: '助手',
        icon: <RobotOutlined />,
        noPadding: true,
        component: <AssistantSider />,
      },
      {
        id: 'model',
        name: '模型',
        icon: <AppstoreOutlined />,
        noPadding: true,
        component: <ModelSider />,
      },
      {
        id: 'rag',
        name: '知识库',
        icon: <ReadOutlined />,
        component: (
          <RagSider
            onSelect={(chatRagRequest) =>
              (siderParamsRef.current = {
                ...siderParamsRef.current,
                chatRagRequest: chatRagRequest,
              })
            }
          />
        ),
      },
    ],
    [activeConversation],
  );

  return (
    <div className={styles.layout} style={{ height: pageHeight }}>
      {/* ── 左侧悬浮按钮（仅折叠时可见）── */}
      {leftCollapsed && (
        <div
          className={`${styles.floatBtn} float-left`}
          onClick={expandLeft}
          title="展开会话列表"
        >
          <MessageOutlined />
        </div>
      )}

      {/* ── 右侧悬浮按钮（仅折叠时可见）── */}
      {rightCollapsed && (
        <div
          className={`${styles.floatBtn} float-right`}
          onClick={expandRight}
          title="展开设置面板"
        >
          <SettingOutlined />
        </div>
      )}

      <Splitter
        className={styles.horizontalSplitter}
        style={{ height: pageHeight }}
        onCollapse={handleCollapse}
        onResize={setHorizSizes}
      >
        {/* ── 左侧：会话列表 ── */}
        <Splitter.Panel
          size={horizSizes[0]}
          defaultSize={DEFAULT_LEFT_SIZE}
          min={150}
          collapsible
        >
          <div className={styles.leftPanel}>
            <ConversationSider
              ref={conversationSiderRef}
              onCreate={() =>
                createConversation({
                  title: '新对话',
                  convId: '',
                  scene: 'chat_management',
                } as ConversationUpsertDto)
              }
              onEdit={(conversation: Conversation) => {
                setActiveConversation(conversation);
                setActivePanel('conversation');
              }}
              onClick={setActiveConversation}
            />
          </div>
        </Splitter.Panel>

        {/* ── 中间：聊天区域 ── */}
        <Splitter.Panel min={400}>
          <div className={styles.centerPanel}>
            {/* 纵向 Splitter：消息列表 + 输入框 */}
            <Splitter
              orientation="vertical"
              style={{ height: '100%' }}
              onResize={setVertSizes}
            >
              <Splitter.Panel min={120} size={vertSizes[0]}>
                <MessageList
                  ref={messageListRef}
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

              <Splitter.Panel
                size={vertSizes[1]}
                min={DEFAULT_CHAT_INPUT_HEIGHT}
              >
                <ChatInput
                  currentConv={activeConversation}
                  onHeightChange={onHeightChange}
                  isStreaming={isStreaming}
                  sendMessage={sendMessage}
                  stopStreaming={stopStreaming}
                  onSend={() => messageListRef.current?.showNewestMessage()}
                />
              </Splitter.Panel>
            </Splitter>
          </div>
        </Splitter.Panel>

        {/* ── 右侧：助手 / 设置面板 ── */}
        <Splitter.Panel
          size={horizSizes[2]}
          defaultSize={DEFAULT_RIGHT_SIZE}
          min={150}
          collapsible
        >
          <RightSidebar
            panels={rightSidePanels}
            activeId={activePanel}
            onActiveIdChange={setActivePanel}
          />
        </Splitter.Panel>
      </Splitter>
    </div>
  );
}

export default function ChatManagement() {
  return (
    <ChatProvider>
      <ChatManagementContent />
    </ChatProvider>
  );
}
