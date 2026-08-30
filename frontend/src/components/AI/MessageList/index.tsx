import React, {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {message as antdMessage, Skeleton, Spin, Tooltip} from 'antd';
import {
  ArrowDownOutlined,
  CheckOutlined,
  CopyOutlined,
  DeleteOutlined,
  DislikeFilled,
  DislikeOutlined,
  EditOutlined,
  LikeFilled,
  LikeOutlined,
  LoadingOutlined,
  QuestionCircleOutlined,
  ReloadOutlined,
  SwapOutlined,
} from '@ant-design/icons';
import {createStyles} from 'antd-style';
import MessageBubble, {type MessageOperationProps} from './MessageBubble';
import type {Conversation, Message, SendMessageParams} from '@/types/ai.type';
import {batchDeleteMessages, listMessages, toggleMessageLike} from "@/services/ant-design-pro/ai.rbac";

export type {MessageOperationProps} from './MessageBubble';

export const MESSAGE_PAGE_SIZE = 10; // 每次加载多少条消息

// ─── Styles ───
const useStyles = createStyles(({token, css}) => ({
  container: css`
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    position: relative;
    height: 100%;
  `,
  scrollArea: css`
    flex: 1;
    overflow-y: auto;
    overflow-x: hidden;
    padding: 16px 0 8px;

    &::-webkit-scrollbar {
      width: 7px;
    }

    &::-webkit-scrollbar-thumb {
      background: ${token.colorFill};
      border-radius: 5px;
    }

    &::-webkit-scrollbar-thumb:hover {
      background: ${token.colorBorder};
    }
  `,
  inner: css`
    margin: 0 auto;
    padding: 0 16px;
    display: flex;
    flex-direction: column;
    gap: 4px;
  `,
  loadMoreArea: css`
    text-align: center;
    padding: 8px 0 16px;
    min-height: 32px;
    display: flex;
    align-items: center;
    justify-content: center;
  `,
  scrollToBottom: css`
    position: absolute;
    bottom: 16px;
    right: 24px;
    width: 36px;
    height: 36px;
    border-radius: 50%;
    background: ${token.colorBgElevated};
    border: 1px solid ${token.colorBorderSecondary};
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    box-shadow: ${token.boxShadow};
    transition: opacity 0.2s, transform 0.2s;
    z-index: 10;

    &:hover {
      background: ${token.colorFillSecondary};
      transform: translateY(-1px);
    }
  `,
  emptyBox: css`
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    color: ${token.colorTextSecondary};
    padding: 40px 16px;
    height: 100%;
  `,
  emptyHints: css`
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    justify-content: center;
    max-width: 480px;
    margin-top: 8px;
  `,
  hintChip: css`
    padding: 6px 14px;
    background: ${token.colorFillQuaternary};
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 20px;
    font-size: 13px;
    color: ${token.colorTextSecondary};
    cursor: pointer;
    transition: all 0.15s;

    &:hover {
      background: ${token.colorPrimaryBg};
      border-color: ${token.colorPrimaryBorder};
      color: ${token.colorPrimary};
    }
  `,
}));

// ─── 快捷提示词 ───
const QUICK_HINTS = [
  '帮我写一段 Python 代码',
  '用简洁的语言解释量子计算',
  '给我推荐一份健康的早餐食谱',
  '分析一下当前 AI 发展趋势',
  '帮我优化这段 SQL 查询',
  '写一封正式的商务邮件',
];

// ─── Props ───
export interface MessageListProps {
  currentConv?: Conversation | null; // 当前会话信息
  streamingMessageId?: string; // 流式输出的消息 ID
  isStreaming: boolean; // 是否正在流式输出
  onQuote?: (msg: Message) => void; // 引用消息回调
  onEdit?: (msg: Message) => void; // 编辑消息回调
  onRemove?: (msg: Message) => void; // 删除消息回调
  sendMessage?: (params: SendMessageParams) => void; // 发送消息回调
  regenerateMessage?: (msg: Message) => void; // 重新生成消息回调
  onRetry?: (msg: Message) => void; // 重试消息回调
  quickHints?: string[]; // 快捷提示词
  extraMenuOperations?: MessageOperationProps[]; // 外部传入的额外消息操作
}

export interface MessageListRef {
  showNewestMessage: () => void; // 显示最新消息（滚动到底部）
  getMessages: () => Message[]; // 获取当前消息列表（供父组件查找上一条用户消息等）
  setMessages: (messages: Message[] | ((prev: Message[]) => Message[])) => void; // 设置消息列表
  stopStreaming: () => void; // 停止流式输出
}

// ─── 主组件：消息列表展示 ───
const MessageList = forwardRef(
  ({
     onQuote,
     onEdit,
     onRemove,
     currentConv,
     streamingMessageId,
     isStreaming,
     sendMessage,
     regenerateMessage,
     onRetry,
     quickHints = QUICK_HINTS,
     extraMenuOperations,
   }: MessageListProps, ref) => {
    const {styles} = useStyles();
    const [messages, setMessages] = useState<Message[]>([]); // 消息列表
    const [loading, setLoading] = useState<boolean>(false); // 是否正在加载消息
    const currentPageRef = useRef(1); // 当前页码
    const totalRef = useRef(0); // 总消息数
    const loadedCountRef = useRef(0); // 已加载的消息数量

    const scrollRef = useRef<HTMLDivElement>(null);
    const bottomRef = useRef<HTMLDivElement>(null);
    const loadMoreRef = useRef<HTMLDivElement>(null);

    const [showScrollBtn, setShowScrollBtn] = useState(false);
    const [copiedMessageId, setCopiedMessageId] = useState<string>();
    const [optimizedViewByMessageId, setOptimizedViewByMessageId] = useState<Record<string, boolean>>({});
    const autoScrollRef = useRef(true);// 为 true 时，新消息/流式输出自动滚动到底部
    const [, forceUpdate] = useState(0);

    const prevConvIdRef = useRef<string | null>(null);

    // ─── 从后端获取消息 ───
    const fetchMessages = useCallback(async (convId: string, current = 1, append = false) => {
      setLoading(true);
      const res = await listMessages({
        convId,
        current,
        size: MESSAGE_PAGE_SIZE,
        includeDeleted: false,
        orders: [{column: 'id', asc: false}],
      });
      const currentRecords = res.records || [];
      currentPageRef.current = current;
      totalRef.current = res.total ?? 0;
      loadedCountRef.current = currentRecords.length + loadedCountRef.current;
      if (append) {
        setMessages((prev) => {
          let next = [...currentRecords, ...prev];
          next.sort((a: any, b: any) => a.id - b.id);
          return next;
        });
      } else {
        setMessages(currentRecords.sort((a: any, b: any) => a.id - b.id));
      }
      setLoading(false);
    }, [setLoading, setMessages]);

    // ─── 删除指定消息 ───
    const removeMessage = useCallback(async (msg: Message) => {
      await batchDeleteMessages(msg.convId, [msg.messageId]).then(() => {
        setMessages(prev => prev.filter(item => item.messageId !== msg.messageId));
        onRemove?.(msg);
      })
    }, [setMessages, onRemove]);

    // ─── 切换会话时：重置 + 加载第一页 ───
    useLayoutEffect(() => {
      const convId = currentConv?.convId;
      if (!convId || convId === prevConvIdRef.current) return;
      prevConvIdRef.current = convId;
      autoScrollRef.current = true;
      currentPageRef.current = 1;
      loadedCountRef.current = 0;
      totalRef.current = 0;
      setOptimizedViewByMessageId({});
      fetchMessages(convId).then();
    }, [currentConv?.convId, fetchMessages]);

    // ─── 初次加载完成后滚动到底部 ───
    useLayoutEffect(() => {
      if (!loading && messages.length > 0 && autoScrollRef.current) {
        // 用 requestAnimationFrame 确保 DOM 已渲染
        requestAnimationFrame(() => {
          bottomRef.current?.scrollIntoView({behavior: 'instant', block: 'end'});
        });
      }
    }, [loading]);

    // ─── 流式输出时跟随滚动：监听流式消息内容长度变化 ───
    const streamingContentLen = useMemo(() => {
      if (!isStreaming || !streamingMessageId) return 0;
      const msg = messages.find((m) => m.messageId === streamingMessageId);
      return (msg?.content?.length ?? 0)
        + (msg?.optimizedContent?.length ?? 0)
        + (msg?.reasoningContent?.length ?? 0);
    }, [isStreaming, streamingMessageId, messages]);

    useEffect(() => {
      if (isStreaming && autoScrollRef.current) {
        const el = scrollRef.current;
        if (el) {
          el.scrollTop = el.scrollHeight;
        }
      }
    }, [isStreaming, streamingContentLen]);

    // ─── 非流式新消息到达时滚动到底部（发送后） ───
    const prevMsgLenRef = useRef(0);
    useEffect(() => {
      const cur = messages.length;
      const prev = prevMsgLenRef.current;
      prevMsgLenRef.current = cur;
      // 仅在消息数增加（非 prepend 历史）且用户在底部时滚动
      if (cur > prev && !isStreaming && autoScrollRef.current) {
        requestAnimationFrame(() => {
          bottomRef.current?.scrollIntoView({behavior: 'smooth', block: 'end'});
        });
      }
    }, [messages.length, isStreaming]);

    // ─── 滚动事件：判断是否显示「回到底部」按钮 & 是否保持 autoScroll ───
    const handleScroll = useCallback(() => {
      const el = scrollRef.current;
      if (!el) return;
      const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
      const atBottom = distFromBottom < 100;
      // 流式输出期间不关闭 autoScroll，避免滚动动画未完成时误判
      if (!isStreaming && autoScrollRef.current !== atBottom) {
        autoScrollRef.current = atBottom;
        forceUpdate((n) => n + 1);
      }
      setShowScrollBtn(distFromBottom > 200);
    }, [isStreaming]);

    // ─── 回到底部 ───
    const scrollToBottom = useCallback((behavior: ScrollBehavior = 'smooth') => {
      bottomRef.current?.scrollIntoView({behavior, block: 'end'});
    }, []);

    // ─── IntersectionObserver：触顶加载更多历史消息 ───
    useEffect(() => {
      const el = loadMoreRef.current;
      if (!el) return;

      const observer = new IntersectionObserver(
        (entries) => {
          if (
            entries[0].isIntersecting &&
            totalRef.current > loadedCountRef.current &&
            !loading &&
            currentConv?.convId
          ) {
            const scrollEl = scrollRef.current;
            // 记录加载前的 scrollHeight，加载后补偿滚动位置
            const prevScrollHeight = scrollEl?.scrollHeight ?? 0;
            fetchMessages(currentConv.convId, currentPageRef.current + 1, true).then(() => {
              requestAnimationFrame(() => {
                if (scrollEl) {
                  // 新内容插入顶部后，保持当前视图位置不跳动
                  scrollEl.scrollTop += scrollEl.scrollHeight - prevScrollHeight;
                }
              });
            });
          }
        },
        {
          root: scrollRef.current,
          threshold: 0.1,
        },
      );

      observer.observe(el);
      return () => observer.disconnect();
    }, [loading, currentConv?.convId]);

    // ─── 点赞/点踩 ───
    const handleLike = useCallback(
      (message: Message, currentStatus: number) => {
        if (currentStatus !== 1 && currentStatus !== -1) return;
        if (currentStatus === 1 && message.likeStatus === 1) return;
        if (currentStatus === -1 && message.likeStatus === -1) return;
        toggleMessageLike(message.messageId, currentStatus).then(() => {
          setMessages((prev) => prev.map((msg) =>
            (msg.messageId === message.messageId ? {...message, likeStatus: currentStatus} : msg)));
        });
      }, []);

    const isShowingOptimized = useCallback((msg: Message) => {
      const override = optimizedViewByMessageId[msg.messageId];
      return override ?? (msg.role === 'assistant' && Boolean(msg.optimizedContent));
    }, [optimizedViewByMessageId]);

    const getDisplayedContent = useCallback((msg: Message) => (
      isShowingOptimized(msg) ? (msg.optimizedContent ?? '') : msg.content
    ), [isShowingOptimized]);

    const handleCopy = useCallback(async (msg: Message) => {
      try {
        await navigator.clipboard.writeText(getDisplayedContent(msg));
        setCopiedMessageId(msg.messageId);
        antdMessage.success('已复制到剪贴板').then();
        setTimeout(() => {
          setCopiedMessageId((current) => current === msg.messageId ? undefined : current);
        }, 2000);
      } catch {
        antdMessage.error('复制失败').then();
      }
    }, [getDisplayedContent]);

    const handleRegenerate = useCallback((msg: Message) => {
      setOptimizedViewByMessageId((prev) => ({...prev, [msg.messageId]: true}));
      regenerateMessage?.(msg);
    }, [regenerateMessage]);

    const handleToggleContent = useCallback((msg: Message) => {
      setOptimizedViewByMessageId((prev) => ({
        ...prev,
        [msg.messageId]: !(prev[msg.messageId]
          ?? (msg.role === 'assistant' && Boolean(msg.optimizedContent))),
      }));
    }, []);

    const builtInMenuOperations = useMemo<MessageOperationProps[]>(() => [
      {
        key: 'copy',
        order: 10,
        label: (msg) => copiedMessageId === msg.messageId ? '已复制' : '复制',
        icon: (msg) => copiedMessageId === msg.messageId
          ? <CheckOutlined style={{color: '#52c41a'}}/>
          : <CopyOutlined/>,
        operationFunc: handleCopy,
      },
      {
        key: 'like',
        order: 20,
        label: '赞',
        icon: (msg) => msg.likeStatus === 1 ? <LikeFilled/> : <LikeOutlined/>,
        showFunc: (msg) => msg.role === 'assistant',
        operationFunc: (msg) => handleLike(msg, 1),
        activeFunc: (msg) => msg.likeStatus === 1,
        activeType: 'success',
      },
      {
        key: 'dislike',
        order: 30,
        label: '踩',
        icon: (msg) => msg.likeStatus === -1 ? <DislikeFilled/> : <DislikeOutlined/>,
        showFunc: (msg) => msg.role === 'assistant',
        operationFunc: (msg) => handleLike(msg, -1),
        activeFunc: (msg) => msg.likeStatus === -1,
        activeType: 'danger',
      },
      {
        key: 'regenerate',
        order: 40,
        label: '重新生成',
        icon: <ReloadOutlined/>,
        showFunc: (msg) => msg.role === 'assistant' && Boolean(regenerateMessage),
        operationFunc: handleRegenerate,
      },
      {
        key: 'toggle-content',
        order: 45,
        label: (msg) => isShowingOptimized(msg) ? '查看原内容' : '查看优化内容',
        icon: <SwapOutlined/>,
        showFunc: (msg) => Boolean(msg.optimizedContent),
        operationFunc: handleToggleContent,
      },
      {
        key: 'quote',
        order: 50,
        label: '引用(暂不支持)',
        icon: <QuestionCircleOutlined/>,
        isMore: true,
        operationFunc: (msg) => onQuote?.(msg),
      },
      {
        key: 'edit',
        order: 60,
        label: '编辑(暂不支持)',
        icon: <EditOutlined/>,
        isMore: true,
        operationFunc: (msg) => onEdit?.(msg),
      },
      {
        key: 'delete',
        order: 70,
        label: '删除',
        icon: <DeleteOutlined/>,
        isMore: true,
        dividerBefore: true,
        danger: true,
        operationFunc: removeMessage,
      },
    ], [
      copiedMessageId,
      handleCopy,
      handleLike,
      handleRegenerate,
      handleToggleContent,
      isShowingOptimized,
      onEdit,
      onQuote,
      regenerateMessage,
      removeMessage,
    ]);

    const menuOperations = useMemo(
      () => [...builtInMenuOperations, ...(extraMenuOperations ?? [])],
      [builtInMenuOperations, extraMenuOperations],
    );

    // ─── 快捷提示词点击 ───
    const handleQuickHint = useCallback(
      (hint: string) => {
        sendMessage?.({content: hint});
      },
      [sendMessage],
    );

    // ── 暴露刷新方法给父组件 ───
    useImperativeHandle(ref, () => ({
      showNewestMessage: () => setTimeout(() => scrollToBottom('smooth'), 500),
      setMessages: (param: Message[] | ((prev: Message[]) => Message[])) => {
        if (param instanceof Function) {
          setMessages((prev) => param(prev));
        } else {
          setMessages(param);
        }
      },
      getMessages: () => messages,
      stopStreaming: () => {
        if (streamingMessageId) {
          setMessages((prev: Message[]) => prev.map((msg) => {
            if (msg.messageId === streamingMessageId) {
              return {...msg, status: 'completed'};
            }
            return msg;
          }));
        }
      },
    }));

    // ─── 空状态：未选择会话 ───
    if (!currentConv) {
      return (
        <div className={styles.emptyBox}>
          <div style={{fontSize: 50}}>💬</div>
          <div style={{fontSize: 16, fontWeight: 600}}>选择或新建一个对话</div>
          <div style={{fontSize: 13, color: '#8c8c8c'}}>
            从左侧选择已有对话，或点击「新建」开始
          </div>
        </div>
      );
    }

    // ─── 空状态：首次加载中 ───
    if (loading && messages.length === 0) {
      return (
        <div className={styles.scrollArea}>
          <div className={styles.inner}>
            {Array.from({length: 4}).map((_, i) => (
              <Skeleton
                key={i}
                active
                avatar={{size: 36, shape: 'circle'}}
                paragraph={{rows: 2}}
                style={{marginBottom: 16}}
              />
            ))}
          </div>
        </div>
      );
    }

    // ─── 空状态：会话无消息 ───
    if (!loading && messages.length === 0) {
      return (
        <div className={styles.emptyBox}>
          <div style={{fontSize: 50}}>💬</div>
          <div style={{fontSize: 15, fontWeight: 600}}>
            {currentConv.title || '新对话'}
          </div>
          <div style={{fontSize: 13, color: '#8c8c8c'}}>
            开始你的第一条消息
          </div>
          <div className={styles.emptyHints}>
            {quickHints.map((hint) => (
              <div
                key={hint}
                className={styles.hintChip}
                onClick={() => handleQuickHint(hint)}
              >
                {hint}
              </div>
            ))}
          </div>
        </div>
      );
    }

    // ─── 正常渲染 ───
    return (
      <div className={styles.container}>
        <div
          ref={scrollRef}
          className={styles.scrollArea}
          onScroll={handleScroll}
        >
          <div className={styles.inner}>
            {/* ── 顶部：加载更多触发区 ── */}
            <div ref={loadMoreRef} className={styles.loadMoreArea}>
              {loading && messages.length > 0 ? (
                <Spin indicator={<LoadingOutlined/>} size="small"/>
              ) : totalRef.current > loadedCountRef.current ? (
                // 占位高度，确保 IntersectionObserver 能捕获到
                <div style={{height: 32}}/>
              ) : (
                <span style={{fontSize: 12, color: '#bfbfbf'}}>
                  已加载全部消息
                </span>
              )}
            </div>

            {/* ── 消息列表 ── */}
            {messages.map((msg) => (
              <MessageBubble
                key={msg.messageId}
                message={msg}
                displayContent={getDisplayedContent(msg)}
                menuOperations={menuOperations}
                isStreaming={isStreaming && streamingMessageId === msg.messageId}
                onRetry={() => onRetry?.(msg)}
              />
            ))}

            {/* ── 滚动锚点 ── */}
            <div ref={bottomRef} style={{height: 1}}/>
          </div>
        </div>

        {/* ── 回到底部按钮 ── */}
        {showScrollBtn && (
          <Tooltip title="回到底部">
            <div
              className={styles.scrollToBottom}
              onClick={() => {
                autoScrollRef.current = true;
                scrollToBottom('smooth');
              }}
            >
              <ArrowDownOutlined style={{fontSize: 14}}/>
            </div>
          </Tooltip>
        )}
      </div>
    );
  },
);

export default MessageList;
