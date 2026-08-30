import React, {useCallback, useState} from 'react';
import {ModelConfig} from "@/types/ai.type";
import {Button, message, Modal} from "antd";
import {
  CheckOutlined,
  CloseOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  PushpinFilled,
  PushpinOutlined,
  StarOutlined
} from "@ant-design/icons";
import {MyRightSiderPanel} from "@/components";
import {LoadFuncResult, RightSiderItem} from "@/components/MyRightSiderPanel";
import {ModelEditForm} from "@/components/AI";
import {
  deleteModelConfig,
  getModelConfig,
  listModelConfigs,
  setAsDefaultModelConfig,
  toggleModelConfigPin,
  toggleModelConfigStatus
} from "@/services/ant-design-pro/ai.rbac";
import {useModelsStore} from "@/stores/modelsStore";
import {RightSiderItemOption} from "@/components/MyRightSiderPanel/type";

export type ModelSiderProps = {}

function transferModelConfig(model: ModelConfig) {
  let abstractInfo = '';
  model.description && (abstractInfo = model.description.substring(0, 15) + '...');
  let disabled = model.status === 3;
  let backgroundColor = '';
  if(model.defaultFlag) {
    backgroundColor = 'var(--color-chinese-blue)';
  } else if(model.pinFlag) {
    backgroundColor = 'var(--color-chinese-orange)';
  } else if(!disabled) {
    backgroundColor = 'var(--color-chinese-green)';
  }
  return {
    origin: model,
    key: model.id,
    title: model.modelName || '未命名模型',
    abstractInfo,
    pinFlag: model.pinFlag,
    disabled,
    icon: model.icon || 'robot',
    backgroundColor,
  } as RightSiderItem;
}

// ─── 模型侧边栏组件：编辑模型 ───
const ModelSider: React.FC<ModelSiderProps> = () => {
  const setModels = useModelsStore((state) => state.setModels);
  const [activeKey, setActiveKey] = useState<number | undefined>();
  const [formModalVisible, setFormModalVisible] = useState<boolean>(false);
  const formModalTitleRef = React.useRef<string>('');
  const rightSiderPanelRef = React.useRef<any>(null);
  const [defaultModelConfig, setDefaultModelConfig] = useState<RightSiderItem | undefined>(undefined);

  // 模型操作按钮
  const getOperations = useCallback((model: ModelConfig) => {
    const disabled = model.status === 3;
    const defaultOperation = [];
    if(!model.defaultFlag) {
      defaultOperation.push({
        key: "setDefault",
          label: "设为默认",
        order: 1,
        icon: <StarOutlined/>,
        onClick: (current: RightSiderItem) => setAsDefaultModelConfig(current.key as number).then(() => {
        rightSiderPanelRef.current?.refresh();
        setDefaultModelConfig({...current, origin: {...current.origin, defaultFlag: true, id: current.key}});
      }),
      })
    }
    const restOperations = [
      {
        key: "pin",
        label: model.pinFlag ? "取消置顶" : "置顶",
        order: 2,
        icon: model.pinFlag ? <PushpinOutlined/> : <PushpinFilled/>,
        onClick: (current: RightSiderItem) => toggleModelConfigPin(current.key as number, !current.pinFlag).then(() => {
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
        onClick: (current: RightSiderItem) => toggleModelConfigStatus(current.key as number, disabled ? 1 : 3).then(() => {
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
          formModalTitleRef.current = '编辑模型';
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
          deleteModelConfig(current.key as number).then(() => {
            rightSiderPanelRef.current?.setList((prev: RightSiderItem[]) => {
              const newList = prev?.filter((item) => item.key !== current.key);
              setModels(newList.map((item) => item.origin));
              return newList;
            });
          })
        },
      },
    ]
    return [...defaultOperation, ...restOperations];
  }, [setModels, setFormModalVisible]);

  // 模型额外渲染内容
  const extraRender = (item: RightSiderItem) => (
    <span
      className="text-xs px-1.5 py-0.5 rounded-full bg-green-100 text-green-600">
      {(item as any).origin.provider || '模型'}
    </span>
  );

  // 搜索模型
  const searchModels = useCallback(async (param: any) => {
    const res = await listModelConfigs({
      ...param,
      orders: [{column: 'sort_order', asc: true}, {column: 'update_time', asc: false}]
    });
    const records = res?.records || [];
    if (records.length === 0) {
      return {total: 0, current: res.current, size: res.size, records: []} satisfies LoadFuncResult;
    }
    setActiveKey(undefined);
    const models: RightSiderItem[] = [];
    for (const model of records) {
      let item: RightSiderItem = transferModelConfig(model);
      item.extraRender = extraRender;
      item.operations = getOperations(model) as RightSiderItemOption[];

      if (!model.defaultFlag) {
        models.push(item);
      } else {
        setDefaultModelConfig(item);
      }
    }
    if (!defaultModelConfig) {
      getModelConfig(null, true).then(res => {
        if(res) {
          let item = transferModelConfig(res);
          item.extraRender = extraRender;
          item.operations = getOperations(res) as RightSiderItemOption[];
          setDefaultModelConfig(item);
        } else {
          message.warning("请设置一个默认模型！");
        }
      })
    }
    return {
      total: res.total,
      current: res.current,
      size: res.size,
      records: models,
    } satisfies LoadFuncResult;
  }, [defaultModelConfig, getOperations, setDefaultModelConfig]);

  // 添加模型
  const addModel = useCallback(() => {
    formModalTitleRef.current = '添加模型';
    setActiveKey(undefined);
    setFormModalVisible(true);
  }, [setFormModalVisible]);

  const header = (
    <>
      <Button
        type="primary"
        icon={<PlusOutlined/>}
        block
        className="rounded-lg"
        onClick={addModel}
      >
        添加模型
      </Button>
    </>
  );

  return (
    <div className="relative h-full">
      <MyRightSiderPanel
        ref={rightSiderPanelRef}
        header={header}
        virtualItem={defaultModelConfig}
        virtualTip='默认模型'
        searchInputKey='modelName'
        size={15}
        loadFunc={searchModels}
        activeKey={activeKey}
        onItemClick={(item) => setActiveKey(Number(item.key))}
        emptyRender={<span className="text-sm">暂无模型</span>}
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
          <ModelEditForm
            id={activeKey}
            onSuccess={() => {
              setFormModalVisible(false);
              rightSiderPanelRef.current?.refresh().then(() => {
                setModels([...rightSiderPanelRef.current?.getList()]);
              });
            }}
            onCancel={() => setFormModalVisible(false)}
          />
        </div>
      </Modal>
    </div>
  );
};

export default ModelSider;
