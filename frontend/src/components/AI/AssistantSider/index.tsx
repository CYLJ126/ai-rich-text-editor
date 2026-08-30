import React, {forwardRef, useCallback, useImperativeHandle, useState} from 'react';
import {AssistantConfig, AssistantParam} from "@/types/ai.type";
import {Button, Modal} from "antd";
import {
  CheckOutlined,
  CloseOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  PushpinFilled,
  PushpinOutlined,
  StarOutlined,
} from "@ant-design/icons";
import {MyRightSiderPanel} from "@/components";
import {LoadFuncResult, RightSiderItem} from "@/components/MyRightSiderPanel";
import {AssistantEditForm} from "@/components/AI";
import {
  deleteAssistant,
  getAssistant,
  listAssistants,
  setAsDefaultAssistant,
  toggleAssistantPin,
  toggleAssistantStatus,
} from "@/services/ant-design-pro/ai.rbac";
import {RightSiderItemOption} from "@/components/MyRightSiderPanel/type";

export interface AssistantSiderProps {
  height?: number;
  onItemClick?: (assistant: AssistantConfig) => void;
  onItemDoubleClick?: (assistant: AssistantConfig) => void;
}

export interface AssistantSiderRef {
  getList: () => AssistantConfig[];
}

function transferAssistant(assistant: AssistantConfig) {
  let abstractInfo = '';
  assistant.systemPrompt && (abstractInfo = assistant.systemPrompt.substring(0, 15) + '...');
  let disabled = assistant.status === 3;
  let backgroundColor = '';
  if (assistant.defaultFlag) {
    backgroundColor = 'var(--color-chinese-blue)';
  } else if (assistant.pinFlag) {
    backgroundColor = 'var(--color-chinese-orange)';
  } else if (!disabled) {
    backgroundColor = 'var(--color-chinese-green)';
  }
  return {
    origin: assistant,
    key: assistant.id,
    title: assistant.name || '未命名助手',
    icon: assistant.avatar || 'robot',
    abstractInfo,
    pinFlag: assistant.pinFlag,
    disabled,
    backgroundColor,
  } as RightSiderItem;
}


const AssistantSider = forwardRef<AssistantSiderRef, AssistantSiderProps>((props, ref) => {
  const {height, onItemClick, onItemDoubleClick} = props;
  // 当前选中的助手 KEY，即 ID
  const [activeKey, setActiveKey] = useState<number | undefined>();
  const [formModalVisible, setFormModalVisible] = useState<boolean>(false);
  const formModalTitleRef = React.useRef<string>('');
  const rightSiderPanelRef = React.useRef<any>(null);
  const [defaultAssistant, setDefaultAssistant] = useState<RightSiderItem | undefined>(undefined);

  // 助手操作按钮
  const getOperations = useCallback((assistant: AssistantConfig) => {
    const disabled = assistant.status === 3;
    const defaultOperation = [];
    if (!assistant.defaultFlag) {
      defaultOperation.push({
        key: "setDefault",
        label: "设为默认",
        order: 1,
        icon: <StarOutlined/>,
        onClick: (current: RightSiderItem) => setAsDefaultAssistant(current.key as number).then(() => {
          rightSiderPanelRef.current?.refresh();
          setDefaultAssistant({...current, origin: {...current.origin, defaultFlag: true, id: current.key}});
        }),
      })
    }
    const restOperations = [
      {
        key: "pin",
        label: assistant.pinFlag ? "取消置顶" : "置顶",
        order: 2,
        icon: assistant.pinFlag ? <PushpinOutlined/> : <PushpinFilled/>,
        onClick: (current: RightSiderItem) => toggleAssistantPin(current.key as number, !current.pinFlag).then(() => {
          rightSiderPanelRef.current?.setList((prev: RightSiderItem[]) => prev?.map((item) => {
            if (item.key === current.key) {
              const pinFlag = !current.pinFlag;
              item.origin.pinFlag = pinFlag;
              return {...item, pinFlag, operations: getOperations(item.origin)};
            }
            return item;
          }));
        }),
      },
      {
        label: disabled ? "启用" : "禁用",
        order: 3,
        icon: disabled ? <CheckOutlined/> : <CloseOutlined/>,
        onClick: (current: RightSiderItem) => toggleAssistantStatus(current.key as number, disabled ? 1 : 3).then(() => {
          rightSiderPanelRef.current?.setList((prev: RightSiderItem[]) => prev?.map((item) => {
            if (item.key === current.key) {
              const status = current.disabled ? 1 : 3;
              item.origin.status = status;
              return {...item, disabled: status === 3, operations: getOperations(item.origin)};
            }
            return item;
          }));
        }),
      },
      {
        key: "edit",
        label: "编辑",
        order: 4,
        icon: <EditOutlined/>,
        onClick: () => {
          formModalTitleRef.current = '编辑助手';
          setFormModalVisible(true);
        },
      },
      {type: 'divider' as const, order: 5},
      {
        key: "delete",
        label: "删除",
        order: 6,
        isDanger: true,
        icon: <DeleteOutlined/>,
        onClick: (current: RightSiderItem) => {
          deleteAssistant(current.key as number).then(() => {
            rightSiderPanelRef.current?.setList((prev: RightSiderItem[]) => prev?.filter((item) => item.key !== current.key));
          })
        },
      },
    ];
    return [...defaultOperation, ...restOperations];
  }, []);

  // 助手额外渲染内容
  const extraRender = () => (
    <span
      className="text-xs px-1.5 py-0.5 rounded-full bg-blue-100 text-blue-600">
          AI
        </span>
  );

  // 搜索助手
  const searchAssistants = useCallback(async (param: AssistantParam) => {
    const res = await listAssistants({
      ...param,
      orders: [{column: 'sort_order', asc: true}, {column: 'update_time', asc: false}]
    });
    const records = res?.records || [];
    if (records.length === 0) {
      return {total: 0, current: res.current, size: res.size, records: []} satisfies LoadFuncResult;
    }
    setActiveKey(undefined);

    const assistants: RightSiderItem[] = [];
    for (const assistant of records) {
      let item = {
        ...transferAssistant(assistant),
        extraRender,
        operations: getOperations(assistant),
      } as RightSiderItem;
      if (assistant.defaultFlag) {
        setDefaultAssistant(item);
      } else {
        assistants.push(item);
      }
    }
    if (!defaultAssistant) {
      getAssistant(null, true).then(res => {
        if (res) {
          setDefaultAssistant({
            ...transferAssistant(res),
            extraRender,
            operations: getOperations(res) as RightSiderItemOption[],
          });
        }
      })
    }
    return {
      total: res.total,
      current: res.current,
      size: res.size,
      records: assistants,
    } satisfies LoadFuncResult;
  }, [defaultAssistant, setDefaultAssistant, getOperations]);

  // 添加助手
  const addAssistant = useCallback(() => {
    formModalTitleRef.current = '添加助手';
    setActiveKey(undefined);
    setFormModalVisible(true);
  }, [setFormModalVisible]);

  useImperativeHandle(ref, () => ({
    getList: () => {
      const rightSiderItem: RightSiderItem[] = rightSiderPanelRef.current?.getList();
      if (!rightSiderItem || rightSiderItem.length === 0) return [];
      return rightSiderItem.map(item => item.origin);
    }
  }));

  const header = (
    <>
      <Button
        type="primary"
        icon={<PlusOutlined/>}
        block
        className="rounded-lg"
        onClick={addAssistant}
      >
        添加助手
      </Button>
    </>
  );

  return (
    <div className="relative h-full">
      <MyRightSiderPanel
        ref={rightSiderPanelRef}
        header={header}
        searchInputKey='name'
        size={15}
        loadFunc={searchAssistants}
        activeKey={activeKey}
        virtualItem={defaultAssistant}
        virtualTip="默认助手"
        onItemClick={(item) => {
          setActiveKey(Number(item.key));
          onItemClick?.(item.origin);
        }}
        onItemDoubleClick={(item) => {
          setActiveKey(Number(item.key));
          onItemDoubleClick?.(item.origin);
          formModalTitleRef.current = '编辑助手';
          setFormModalVisible(true);
        }}
        emptyRender={<span className="text-sm">暂无助手</span>}
      />
      <Modal
        title={formModalTitleRef.current}
        width='75%'
        open={formModalVisible}
        onCancel={() => setFormModalVisible(false)}
        destroyOnHidden
        centered
        footer={null}
      >
        <div className='h-180 overflow-auto'>
          <AssistantEditForm
            id={activeKey}
            onSuccess={() => {
              setFormModalVisible(false);
              rightSiderPanelRef.current?.refresh();
            }}
            onCancel={() => setFormModalVisible(false)}
          />
        </div>
      </Modal>
    </div>
  );
});

export default AssistantSider;
