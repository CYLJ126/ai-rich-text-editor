import {
  DeleteOutlined,
  FormOutlined,
  InboxOutlined,
  PlusOutlined,
  PushpinFilled,
  PushpinOutlined,
} from '@ant-design/icons';
import {Button} from 'antd';
import dayjs from 'dayjs';
import React, {forwardRef, useCallback, useImperativeHandle, useState,} from 'react';
import {MyRightSiderPanel} from '@/components';
import type {LoadFuncResult, RightSiderItem,} from '@/components/MyRightSiderPanel';
import {deleteConversation, listConversations, toggleConversationPin,} from '@/services/ant-design-pro/ai.rbac';
import type {Conversation} from '@/types/ai.conversation.type';

export interface ConversationSiderProps {
  onCreate?: () => Promise<Conversation>; // 新建会话
  onEdit?: (conversation: Conversation) => void; // 编辑会话
  onClick?: (conversation: Conversation) => void; // 点击会话
  onDoubleClick?: (conversation: Conversation) => void; // 双击会话
  onPin?: (conversation: Conversation) => void; // 置顶会话
  searchParam?: any; // 搜索参数
}

export interface ConversationSiderRef {
  setList: (
    list: RightSiderItem[] | ((prev: RightSiderItem[]) => RightSiderItem[]),
  ) => void;
  selectFirstOrCreate: () => Promise<
    | {
        conversation: Conversation;
        created: boolean;
      }
    | undefined
  >;
}

function transferConversation(conversation: Conversation) {
  let backgroundColor = '';
  if(conversation.defaultFlag) {
    backgroundColor = 'var(--color-chinese-blue)';
  } else if(conversation.pinFlag) {
    backgroundColor = 'var(--color-chinese-orange)';
  } else if(conversation.status === 'active') {
    backgroundColor = 'var(--color-chinese-green)';
  }
  return {
    key: conversation.convId,
    title: conversation.title || '未命名会话',
    abstractInfo: conversation.lastMessageDigest || '',
    pinFlag: conversation.pinFlag,
    icon: 'messageOutlined',
    origin: conversation,
    backgroundColor,
  };
}

const formatTime = (dateStr?: string) => {
  if (!dateStr) return '';
  const d = dayjs(dateStr);
  const now = dayjs();
  if (now.diff(d, 'hour') < 24) return d.fromNow();
  if (now.diff(d, 'day') < 7) return d.format('ddd');
  return d.format('MM/DD');
};

// ─── 会话列表组件 ───
const ConversationSider = forwardRef<
  ConversationSiderRef,
  ConversationSiderProps
>((props, ref) => {
  const { onCreate, onEdit, onClick, onDoubleClick, searchParam = {} } = props;
  // 当前选中的会话 KEY，即 conversationId
  const [activeKey, setActiveKey] = useState<string | undefined>();
  const rightSiderPanelRef = React.useRef<any>(null);

  const getOperations = useCallback((conv: Conversation) => {
    return [
      {
        key: 'pin',
        label: conv.pinFlag ? '取消置顶' : '置顶',
        order: 1,
        icon: conv.pinFlag ? <PushpinFilled /> : <PushpinOutlined />,
        onClick: (current: RightSiderItem) => {
          toggleConversationPin(current.key as string, !current.pinFlag).then(
            () => {
              rightSiderPanelRef.current?.setList((prev: RightSiderItem[]) =>
                prev?.map((item) => {
                  if (item.key === current.key) {
                    const pinFlag = !current.pinFlag;
                    item.origin.pinFlag = pinFlag;
                    return {
                      ...item,
                      pinFlag,
                      operations: getOperations(item.origin),
                    };
                  }
                  return item;
                }),
              );
            },
          );
        },
      },
      {
        key: 'edit',
        icon: <FormOutlined />,
        label: '编辑',
        order: 3,
        onClick: (current: RightSiderItem) => {
          onEdit?.(current.origin as Conversation);
        },
      },
      {
        key: 'archive',
        icon: <InboxOutlined />,
        label: '归档',
        order: 4,
        onClick: (current: RightSiderItem) => {}, //TODO
      },
      { type: 'divider' as const, order: 4 },
      {
        key: 'delete',
        label: '删除',
        order: 6,
        isDanger: true,
        icon: <DeleteOutlined />,
        onClick: (current: RightSiderItem) => {
          deleteConversation(current.key as string).then(() => {
            rightSiderPanelRef.current?.setList((prev: RightSiderItem[]) =>
              prev?.filter((item) => item.key !== current.key),
            );
          });
        },
      },
    ];
  }, []);

  // 会话额外渲染内容
  const extraRender = (conv: Conversation) => (
    <span className="text-[11px] text-text-quaternary shrink-0 whitespace-nowrap">
      {formatTime(conv.lastMessageAt) || ''}
    </span>
  );

  const searchConversations = useCallback(
    async (param: any) => {
      const res = await listConversations({
        ...searchParam,
        ...param,
        orders: [{ column: 'last_message_at', asc: false }],
      });
      const records = res?.records || [];
      if (records.length === 0) {
        return {
          total: 0,
          current: res.current,
          size: res.size,
          records: [],
        } satisfies LoadFuncResult;
      }
      setActiveKey(undefined);
      const conversations = records.map((conv: Conversation) => {
        return {
          ...transferConversation(conv),
          operations: getOperations(conv),
          extraRender: () => extraRender(conv),
        };
      });
      return {
        total: res.total,
        current: res.current,
        size: res.size,
        records: conversations,
      } satisfies LoadFuncResult;
    },
    [searchParam],
  );

  const header = (
    <>
      <Button
        type="primary"
        icon={<PlusOutlined />}
        block
        className="rounded-lg"
        onClick={() =>
          onCreate?.().then((res: Conversation) => {
            rightSiderPanelRef.current?.refresh().then(() => {
              onEdit?.(res);
            });
          })
        }
      >
        新建会话
      </Button>
    </>
  );

  useImperativeHandle(ref, () => ({
    setList: (param) => {
      if (param instanceof Function) {
        rightSiderPanelRef.current?.setList((prev: RightSiderItem[]) =>
          param(prev),
        );
      } else {
        rightSiderPanelRef.current?.setList(param);
      }
    },
    selectFirstOrCreate: async () => {
      const loadedFirst = rightSiderPanelRef.current?.getList()?.[0];
      if (loadedFirst?.origin) {
        setActiveKey(loadedFirst.key as string);
        return {
          conversation: loadedFirst.origin as Conversation,
          created: false,
        };
      }

      const res = await listConversations({
        ...searchParam,
        current: 1,
        size: 1,
        orders: [{ column: 'last_message_at', asc: false }],
      });
      const firstConversation = res?.records?.[0] as Conversation | undefined;
      if (firstConversation) {
        setActiveKey(firstConversation.convId);
        return { conversation: firstConversation, created: false };
      }

      const conversation = await onCreate?.();
      if (!conversation) return undefined;
      setActiveKey(conversation.convId);
      await rightSiderPanelRef.current?.refresh();
      return { conversation, created: true };
    },
  }));

  return (
    <div className="relative h-full">
      <MyRightSiderPanel
        ref={rightSiderPanelRef}
        header={header}
        searchInputKey="name"
        size={15}
        loadFunc={searchConversations}
        activeKey={activeKey}
        onItemClick={(item) => {
          setActiveKey(item.key as string);
          onClick?.(item.origin as Conversation);
        }}
        onItemDoubleClick={(item) => {
          setActiveKey(item.key as string);
          onDoubleClick?.(item.origin as Conversation);
        }}
        emptyRender={<span className="text-sm">暂无会话</span>}
      />
    </div>
  );
});

export default ConversationSider;
