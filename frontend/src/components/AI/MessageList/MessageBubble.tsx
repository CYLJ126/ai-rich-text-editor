import React, {useMemo} from 'react';
import type {MenuProps} from 'antd';
import {Button, Dropdown, Tag, Tooltip} from 'antd';
import {MoreOutlined, QuestionCircleOutlined, ReloadOutlined, RobotOutlined, UserOutlined,} from '@ant-design/icons';
import {createStyles} from 'antd-style';
import {MarkdownRenderer, ThinkingBlock} from '@/components/AI';
import type {Message} from "@/types/ai.type";
import {useModelsStore} from "@/stores/modelsStore";

const useStyles = createStyles(({token, css}) => ({
  wrapper: css`
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 2px 0;

    &:hover .msg-actions {
      opacity: 1;
      pointer-events: auto;
    }
  `,
  userWrapper: css`
    align-items: flex-end;
  `,
  assistantWrapper: css`
    align-items: flex-start;
  `,
  bubbleRow: css`
    display: flex;
    align-items: flex-end;
    gap: 8px;
    max-width: 100%;
  `,
  userBubbleRow: css`
    flex-direction: row-reverse;
  `,
  avatar: css`
    width: 36px;
    height: 36px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 16px;
    flex-shrink: 0;
    margin-bottom: 4px;
  `,
  userAvatar: css`
    background: var(--color-chinese-green);
    color: #fff;
  `,
  assistantAvatar: css`
    background: var(--color-chinese-purple);
    color: #fff;
  `,
  bubbleContent: css`
    max-width: min(888px, 80%);
    display: flex;
    flex-direction: column;
    gap: 4px;
  `,
  userBubble: css`
    background: var(--color-chinese-green);
    color: #fff;
    border-radius: 18px 18px 4px 18px;
    padding: 10px 14px;
    font-size: 14px;
    line-height: 1.6;
    word-break: break-word;
    white-space: pre-wrap;
    box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
  `,
  assistantBubble: css`
    background: ${token.colorBgContainer};
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 18px 18px 18px 4px;
    padding: 12px 16px;
    font-size: 14px;
    line-height: 1.7;
    word-break: break-word;
    box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
    min-width: 80px;
  `,
  streamingBubble: css`
    border-color: ${token.colorPrimaryBorder};
    box-shadow: 0 0 0 2px ${token.colorPrimaryBg};
  `,
  quotedBlock: css`
    background: ${token.colorFillQuaternary};
    border-left: 3px solid ${token.colorPrimaryBorder};
    border-radius: 4px;
    padding: 6px 10px;
    font-size: 12px;
    color: ${token.colorTextSecondary};
    margin-bottom: 4px;
    overflow: hidden;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  `,
  fileAttachments: css`
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 4px;
  `,
  fileChip: css`
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 3px 8px;
    background: rgba(255, 255, 255, 0.2);
    border: 1px solid rgba(255, 255, 255, 0.3);
    border-radius: 6px;
    font-size: 12px;
    cursor: pointer;

    &:hover {
      background: rgba(255, 255, 255, 0.3);
    }
  `,
  fileChipAssistant: css`
    background: ${token.colorFillQuaternary};
    border-color: ${token.colorBorderSecondary};
    color: ${token.colorTextSecondary};

    &:hover {
      background: ${token.colorFillSecondary};
    }
  `,
  actions: css`
    display: flex;
    align-items: center;
    gap: 2px;
    opacity: 0;
    pointer-events: none;
    transition: opacity 0.15s;
    margin-top: 2px;
  `,
  userActions: css`
    justify-content: flex-end;
  `,
  actionBtn: css`
    width: 26px;
    height: 26px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border-radius: 6px;
    font-size: 13px;
    color: ${token.colorTextQuaternary};
    cursor: pointer;
    transition: color 0.15s, background 0.15s;

    &:hover {
      color: ${token.colorText};
      background: ${token.colorFillSecondary};
    }

    &.active-success {
      color: ${token.colorSuccess};
    }

    &.active-danger {
      color: ${token.colorError};
    }
  `,
  metaRow: css`
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 2px;
  `,
  metaTime: css`
    font-size: 11px;
    color: ${token.colorTextQuaternary};
  `,
  tokenTag: css`
    font-size: 10px;
    padding: 0 4px;
    height: 16px;
    line-height: 16px;
    border-radius: 3px;
  `,
  errorBlock: css`
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 12px;
    background: ${token.colorErrorBg};
    border: 1px solid ${token.colorErrorBorder};
    border-radius: 8px;
    font-size: 13px;
    color: ${token.colorError};
  `,
  cursor: css`
    display: inline-block;
    width: 2px;
    height: 1em;
    background: ${token.colorPrimary};
    margin-left: 1px;
    vertical-align: text-bottom;
    animation: blink 0.8s steps(1) infinite;
    @keyframes blink {
      0%, 100% {
        opacity: 1;
      }
      50% {
        opacity: 0;
      }
    }
  `,
}));

// ─── Props ───
type OperationContent = React.ReactNode | ((message: Message) => React.ReactNode);

export interface MessageOperationProps {
  key: string;
  label: OperationContent;
  order: number;
  icon: OperationContent;
  isMore?: boolean; // 是否放入更多操作菜单
  showFunc?: (message: Message) => boolean; // 是否显示
  operationFunc: (message: Message) => void | Promise<void>; // 操作函数
  danger?: boolean;
  dividerBefore?: boolean;
  activeFunc?: (message: Message) => boolean;
  activeType?: 'success' | 'danger';
}

export interface MessageBubbleProps {
  message: Message; // 消息内容
  displayContent?: string; // 当前选中的原始或优化内容
  menuOperations?: MessageOperationProps[]; // 施加给消息的操作菜单
  isStreaming?: boolean; // 是否正在流式输出
  onRetry?: () => void; // 重试回调
}

const getOperationContent = (content: OperationContent, message: Message) =>
  typeof content === 'function' ? content(message) : content;

// ─── 主组件：消息框，使用 Markdown 渲染 ───
const MessageBubble: React.FC<MessageBubbleProps> = ({
     message: msg,
     displayContent = msg.content,
     menuOperations = [],
     isStreaming = false,
     onRetry,
   }) => {
    const getModelById = useModelsStore((state) => state.getModelById);
    const {styles, cx} = useStyles();
    const isUser = msg.role === 'user';

    const visibleOperations = useMemo(
      () => [...menuOperations]
        .sort((a, b) => a.order - b.order)
        .filter((operation) => operation.showFunc?.(msg) ?? true),
      [menuOperations, msg],
    );

    const actionOperations = useMemo(
      () => visibleOperations.filter((operation) => !operation.isMore),
      [visibleOperations],
    );

    // 更多菜单
    const moreMenuItems = useMemo<NonNullable<MenuProps['items']>>(() => {
      const items: NonNullable<MenuProps['items']> = [];
      visibleOperations.filter((operation) => operation.isMore).forEach((operation) => {
        if (operation.dividerBefore && items.length > 0) {
          items.push({type: 'divider'});
        }
        items.push({
          key: operation.key,
          label: getOperationContent(operation.label, msg),
          icon: getOperationContent(operation.icon, msg),
          danger: operation.danger,
          onClick: () => operation.operationFunc(msg),
        });
      });
      return items;
    }, [visibleOperations, msg]);

    // 渲染气泡内容
    const renderContent = () => {
      if (msg.status === 'failed') {
        return (
          <div className={styles.errorBlock}>
            <QuestionCircleOutlined/>
            <span>{'消息发送失败，请重试'}</span>
            {onRetry && (
              <Button
                size="small"
                type="link"
                icon={<ReloadOutlined/>}
                onClick={onRetry}
                style={{padding: 0, height: 'auto', fontSize: 12}}
              >
                重试
              </Button>
            )}
          </div>
        );
      }

      if (isUser) {
        return (
          <div className={styles.userBubble}>
            {/* 引用块 */}
            {msg.quotedSnapshot && (
              <div className={styles.quotedBlock}>{msg.quotedSnapshot}</div>
            )}

            {/* 正文 */}
            <span>{displayContent}</span>

            {/* 附件 */}
            {msg.attachments && msg.attachments.length > 0 && (
              <div className={styles.fileAttachments}>
                {msg.attachments.map((f) => (
                  <span key={f.attachmentId} className={styles.fileChip}>
                    📎 {f.fileName}
                  </span>
                ))}
              </div>
            )}
          </div>
        );
      }

      // assistant
      return (
        <div className={cx(styles.assistantBubble, isStreaming && styles.streamingBubble)}>
          {/* 思考过程块 */}
          {msg.reasoningContent && (
            <ThinkingBlock
              content={msg.reasoningContent}
              isStreaming={isStreaming}
            />
          )}

          {/* 正文 Markdown */}
          {displayContent ? (
            <>
              <MarkdownRenderer content={displayContent}/>
              {isStreaming && <span className={styles.cursor}/>}
            </>
          ) : isStreaming ? (
            <span className={styles.cursor}/>
          ) : null}

          {/* 附件 */}
          {msg.attachments && msg.attachments.length > 0 && (
            <div className={styles.fileAttachments}>
              {msg.attachments.map((f) => (
                <span
                  key={f.attachmentId}
                  className={cx(styles.fileChip, styles.fileChipAssistant)}
                >
                  📎 {f.fileName}
                </span>
              ))}
            </div>
          )}
        </div>
      );
    };

    // 渲染操作栏
    const renderActions = () => (
      <div
        className={cx(
          styles.actions,
          'msg-actions',
          isUser && styles.userActions,
        )}
      >
        {actionOperations.map((operation) => (
          <Tooltip key={operation.key} title={getOperationContent(operation.label, msg)}>
            <span
              className={cx(
                styles.actionBtn,
                operation.activeFunc?.(msg) &&
                (operation.activeType === 'danger' ? 'active-danger' : 'active-success'),
              )}
              onClick={() => operation.operationFunc(msg)}
            >
              {getOperationContent(operation.icon, msg)}
            </span>
          </Tooltip>
        ))}

        {/* 更多 */}
        {moreMenuItems.length > 0 && (
          <Dropdown
            menu={{items: moreMenuItems}}
            trigger={['click']}
            placement={isUser ? 'bottomRight' : 'bottomLeft'}
          >
            <span className={styles.actionBtn}>
              <MoreOutlined/>
            </span>
          </Dropdown>
        )}
      </div>
    );

    // 渲染 meta 信息（时间、token 用量）
    const renderMeta = () => (
      <div
        className={styles.metaRow}
        style={{justifyContent: isUser ? 'flex-end' : 'flex-start'}}
      >
        <span className={styles.metaTime}>
          {msg.createTime
            ? new Date(msg.createTime).toLocaleTimeString('zh-CN', {
              hour: '2-digit',
              minute: '2-digit',
            })
            : ''}
        </span>
        {!isUser && msg.promptToken != null && (
          <Tag className={styles.tokenTag} color="default">
            input {msg.promptToken} tokens
          </Tag>
        )}
        {!isUser && msg.completionToken != null && (
          <Tag className={styles.tokenTag} color="default">
            output {msg.completionToken} tokens
          </Tag>
        )}
        {msg.modelId && !isUser && (
          <Tag className={styles.tokenTag} color="default">
            {getModelById(msg.modelId)?.modelId}
          </Tag>
        )}
      </div>
    );

    return (
      <div
        className={cx(
          styles.wrapper,
          isUser ? styles.userWrapper : styles.assistantWrapper,
        )}
      >
        <div
          className={cx(
            styles.bubbleRow,
            isUser && styles.userBubbleRow,
          )}
        >
          {/* 头像 */}
          <div
            className={cx(
              styles.avatar,
              isUser ? styles.userAvatar : styles.assistantAvatar,
            )}
          >
            {isUser ? <UserOutlined/> : <RobotOutlined/>}
          </div>

          {/* 气泡内容 */}
          <div className={styles.bubbleContent}>
            {renderContent()}
          </div>
        </div>

        {/* 操作栏 & meta */}
        <div style={{paddingLeft: isUser ? 0 : 44, paddingRight: isUser ? 44 : 0}}>
          {renderActions()}
          {renderMeta()}
        </div>
      </div>
    );
};

export default MessageBubble;
