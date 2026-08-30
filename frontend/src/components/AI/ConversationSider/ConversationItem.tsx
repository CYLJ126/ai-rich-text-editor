import React, {useCallback, useEffect, useMemo, useRef, useState} from "react";
import {Conversation} from "@/types/ai.type";
import dayjs from "dayjs";
import {
  DeleteOutlined,
  EditOutlined,
  FormOutlined,
  InboxOutlined,
  MessageOutlined,
  MoreOutlined,
  PushpinFilled,
  PushpinOutlined
} from "@ant-design/icons";
import {Avatar, Dropdown, Input, Tooltip, Typography} from "antd";
import {createStyles} from "antd-style";

// ─── 会话条目属性 ───
export interface ConvItemProps {
  conversation: Conversation;
  isActive: boolean;
  onSelect?: (conversation: Conversation) => void;
  onPin?: (conversationId: string, pinFlag: boolean) => void;
  onArchive?: (conversationId: string) => void;
  onEdit?: (conversationId: string) => void;
  onDelete?: (conversationId: string) => void;
  onRename?: (conversationId: string, newTitle: string) => void;
}

const {Text} = Typography;

export const useStyles = createStyles(({token, css}) => ({
  conversationItem: css`
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 10px;
    border-radius: ${token.borderRadiusLG}px;
    border-radius: ${token.borderRadiusLG}px;
    cursor: pointer;
    transition: background 0.15s;
    position: relative;

    &:hover {
      background: ${token.colorFillQuaternary};
    }

    &.active {
      background: ${token.colorPrimaryBg};
    }

    &.pinned {
      background: ${token.colorFillTertiary};
    }
  `,
  conversationItemHover: css`
    &:hover .conv-actions {
      display: flex;
    }
  `,
  avatar: css`
    flex-shrink: 0;
  `,
  content: css`
    flex: 1;
    min-width: 0;
    overflow: hidden;
  `,
  titleRow: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 4px;
  `,
  title: css`
    font-size: 13px;
    font-weight: 500;
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  time: css`
    font-size: 11px;
    color: ${token.colorTextQuaternary};
    flex-shrink: 0;
    white-space: nowrap;
  `,
  digest: css`
    font-size: 12px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    margin-top: 2px;
  `,
  actions: css`
    position: absolute;
    right: 8px;
    top: 50%;
    transform: translateY(-50%);
    display: none;
    background: ${token.colorFillQuaternary};
    border-radius: 4px;

    .active &,
    .ant-dropdown-open & {
      display: flex;
    }
  `,
  pinIcon: css`
    font-size: 12px;
    color: var(--color-chinese-orange);
    flex-shrink: 0;
  `,
  renameInput: css`
    font-size: 13px;
    height: 22px;
    padding: 0 4px;
  `,
}));

// ─── 会话条目组件 ───
const ConversationItem: React.FC<ConvItemProps> = ({
                                                     conversation,
                                             isActive,
                                             onSelect,
                                             onPin,
                                             onArchive,
                                                     onEdit,
                                             onDelete,
                                             onRename,
                                           }) => {
  const {styles, cx} = useStyles();
  const [editing, setEditing] = useState(false);
  const [editTitle, setEditTitle] = useState(conversation.title ?? '');
  const inputRef = useRef<any>(undefined);

  useEffect(() => {
    if (editing) {
      setTimeout(() => inputRef.current?.focus(), 50);
    }
  }, [editing]);

  const handleRenameConfirm = useCallback(() => {
    const trimmed = editTitle.trim();
    if (trimmed && trimmed !== conversation.title) {
      onRename?.(conversation.convId || '', trimmed);
    }
    setEditing(false);
  }, [editTitle, conversation.title, conversation.convId, onRename]);

  const formatTime = useCallback((dateStr?: string) => {
    if (!dateStr) return '';
    const d = dayjs(dateStr);
    const now = dayjs();
    if (now.diff(d, 'hour') < 24) return d.fromNow();
    if (now.diff(d, 'day') < 7) return d.format('ddd');
    return d.format('MM/DD');
  }, []);

  const menuItems = useMemo(
    () => {
      let items: any[] = [];
      if (onPin) {
        items.push({
          key: 'pin',
          icon: conversation.pinFlag ? <PushpinFilled/> : <PushpinOutlined/>,
          label: conversation.pinFlag ? '取消置顶' : '置顶会话',
          onClick: () => onPin(conversation.convId || '', !conversation.pinFlag),
        });
      }
      if (onRename) {
        items.push({
          key: 'rename',
          icon: <EditOutlined/>,
          label: '重命名',
          onClick: () => {
            setEditTitle(conversation.title ?? '');
            setEditing(true);
          },
        });
      }
      if (onEdit) {
        items.push({
          key: 'edit',
          icon: <FormOutlined/>,
          label: '编辑',
          onClick: () => onEdit(conversation.convId || ''),
        });
      }
      if (onArchive) {
        items.push({
          key: 'archive',
          icon: <InboxOutlined/>,
          label: '归档',
          onClick: () => onArchive(conversation.convId || ''),
        });
      }
      items.push({type: 'divider' as const});
      if (onDelete) {
        items.push({
          key: 'delete',
          icon: <DeleteOutlined/>,
          label: '删除',
          danger: true,
          onClick: () => onDelete(conversation.convId || ''),
        });
      }
      return items;
    }, [conversation, onPin, onArchive, onEdit, onDelete]);

  return (
    <div
      className={cx(
        styles.conversationItem,
        styles.conversationItemHover,
        isActive ? 'active' : undefined,
        conversation.pinFlag ? 'pinned' : undefined,
      )}
      onClick={() => !editing && onSelect?.(conversation)}
    >
      {/* 头像 */}
      <Avatar
        className={styles.avatar}
        size={36}
        icon={<MessageOutlined/>}
        style={{
          background: conversation.pinFlag ? 'var(--color-chinese-orange)' : 'var(--color-chinese-green)',
          fontSize: 16,
        }}
      >
        {conversation.title?.charAt(0)?.toUpperCase()}
      </Avatar>

      {/* 主体内容 */}
      <div className={styles.content}>
        <div className={styles.titleRow}>
          {editing ? (
            <Input
              ref={inputRef}
              className={styles.renameInput}
              value={editTitle}
              onChange={(e) => setEditTitle(e.target.value)}
              onPressEnter={handleRenameConfirm}
              onBlur={handleRenameConfirm}
              onClick={(e) => e.stopPropagation()}
              size="small"
            />
          ) : (
            <Text className={styles.title} title={conversation.title ?? ''}>
              {conversation.pinFlag && (
                <PushpinFilled className={styles.pinIcon} style={{marginRight: 4}}/>
              )}
              {conversation.title || '新会话'}
            </Text>
          )}
          <span className={styles.time}>
            {formatTime(conversation.lastMessageAt ?? conversation.updateBy ?? conversation.createBy)}
          </span>
        </div>
        <div className={styles.digest}>
          <span className="text-xs truncate opacity-40 leading-tight">
            {conversation.lastMessageDigest || '暂无消息'}
          </span>
        </div>
      </div>

      {/* 右键菜单 */}
      <Dropdown
        menu={{items: menuItems}}
        trigger={['click']}
        placement="bottomRight"
      >
        <Tooltip title="更多操作">
          <div
            className={cx(styles.actions, 'conv-actions')}
            onClick={(e) => e.stopPropagation()}
          >
            <MoreOutlined style={{padding: '0 4px', cursor: 'pointer'}}/>
          </div>
        </Tooltip>
      </Dropdown>
    </div>
  );
};

export default ConversationItem;
