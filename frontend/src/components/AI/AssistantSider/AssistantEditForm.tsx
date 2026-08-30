import React, {useCallback, useEffect, useState} from 'react';
import {Slider, Spin} from 'antd';
import DynamicForm from '@/components/DynamicForm';
import {FormFieldConfig} from '@/components/DynamicForm/FormField';
import {
  addAssistant,
  getAssistant,
  listContextStrategies,
  listKnowledgeBaseTypes,
  listModelConfigs,
  listModelProviders,
  listReasoningEfforts,
  listTextTypes,
  updateAssistant,
} from '@/services/ant-design-pro/ai.rbac';
import {useModelsStore} from "@/stores/modelsStore";

const AssistantEditForm = ({
                             id,
                             onSuccess,
                             onCancel,
                           }: {
  id?: number;
  onSuccess?: () => void;
  onCancel?: () => void;
}) => {
  const getModelById = useModelsStore((state) => state.getModelById);
  const isEdit = !!id;
  const [loading, setLoading] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [initialValues, setInitialValues] = useState<Record<string, any>>();

  const [providerOptions, setProviderOptions] = useState<{ label: string; value: string }[]>([]);
  const [modelOptions, setModelOptions] = useState<{ label: string; value: number; provider: string }[]>([]);
  const [knowledgeOptions, setKnowledgeOptions] = useState<{ label: string; value: string }[]>([]);
  const [contextStrategyOptions, setContextStrategyOptions] = useState<any[]>([]);
  const [textTypeOptions, setTextTypeOptions] = useState<any[]>([]);
  const [reasoningEffortOptions, setReasoningEffortOptions] = useState<any[]>([]);
  const [contextWindowOptions, setContextWindowOptions] = useState<any>({});

  // 加载下拉选项数据
  useEffect(() => {
    const loadOptions = async () => {
      try {
        const [providersRes, modelsRes, knowledgeRes, contextStrategyRes, textTypeRes, reasoningEffortRes] = await Promise.all([
          listModelProviders(),
          listModelConfigs({}),
          listKnowledgeBaseTypes(),
          listContextStrategies(),
          listTextTypes(),
          listReasoningEfforts(),
        ]);

        if (providersRes) {
          setProviderOptions(
            providersRes.map((item: any) => ({label: item.label, value: item.value})),
          );
        }
        if (modelsRes?.records) {
          setModelOptions(
            modelsRes.records.map((item: any) => ({
              label: item.modelName || item.modelId,
              value: item.id,
              provider: item.provider,
            })),
          );
        }
        if (knowledgeRes) {
          setKnowledgeOptions(
            knowledgeRes.map((item: any) => ({label: item.label, value: item.value})),
          );
        }
        if (contextStrategyRes) {
          setContextStrategyOptions(
            contextStrategyRes.map((item: any) => ({label: item.label, value: item.value})),
          );
          handleContextStrategyChange('window');
        }
        if (textTypeRes) {
          setTextTypeOptions(
            textTypeRes.map((item: any) => ({label: item.label, value: item.value})),
          );
        }
        if (reasoningEffortRes) {
          setReasoningEffortOptions(
            reasoningEffortRes.map((item: any) => ({label: item.label, value: item.value})),
          );
        }
      } catch (error) {
        console.error('加载选项数据失败：', error);
      }
    };

    loadOptions().then();
  }, []);

  // 编辑模式加载助手数据
  useEffect(() => {
    if (!isEdit || !id) return;

    const loadAssistantData = async () => {
      setLoading(true);
      try {
        const data = await getAssistant(id);
        if (data) {
          if (data.modelId) {
            let filter = modelOptions.filter((model => model.value === data.modelId));
            if (filter) {
              data.modelProvider = getModelById(data.modelId)?.provider || '';
            }
          }
          setInitialValues(data);
          if (data.contextStrategy) {
            handleContextStrategyChange(data.contextStrategy);
          }
        }
      } catch (error) {
        console.error('获取助手信息失败：', error);
      } finally {
        setLoading(false);
      }
    };

    loadAssistantData().then();
  }, [id, isEdit, modelOptions]);

  // 处理上下文策略变化，设置上下文窗口数的滑动条选项
  const handleContextStrategyChange = useCallback((value: string) => {
    if (value === 'token') {
      setContextWindowOptions({
        defaultValue: 4096,
        min: 0,
        max: 8192,
        step: 1,
        marks: {0: '0', 2048: '2048', 4096: '4096', 6144: '6144', 8192: '8192'}
      });
    } else {
      setContextWindowOptions({
        defaultValue: 6,
        min: 1,
        max: 20,
        step: 1,
        marks: {1: '1', 5: '5', 10: '10', 15: '15', 20: '20'},
      });
    }
  }, [setContextWindowOptions]);

  const fields: FormFieldConfig[] = [
    {
      fieldName: 'name',
      fieldType: 'input',
      label: '名称',
      required: true,
      placeholder: '请输入助手名称',
    },
    {
      fieldName: 'avatar',
      fieldType: 'icon-picker',
      label: '头像',
      required: true,
      placeholder: '请选择头像',
    },
    {
      fieldName: 'modelProvider',
      fieldType: 'select',
      label: '模型提供商',
      placeholder: '请选择模型提供商',
      options: providerOptions,
    },
    {
      fieldName: 'modelId',
      fieldType: 'select',
      label: '模型',
      placeholder: '请选择模型',
      dependOn: ['modelProvider'],
      loadOptionsFunc: async (field, dependentValues) => {
        if (dependentValues?.modelProvider) {
          return Promise.resolve(modelOptions.filter((model: any) => model.provider === dependentValues?.modelProvider));
        } else {
          return Promise.resolve(modelOptions);
        }
      }
    },
    {
      fieldName: 'systemPrompt',
      fieldType: 'textarea',
      label: '系统提示词',
      placeholder: '请输入系统提示词',
      fullWidth: true,
      extraProps: {
        rows: 4,
        maxLength: 1000,
        showCount: true,
      },
    },
    {
      fieldName: 'reasoningEffort',
      fieldType: 'select',
      label: '推理力度',
      placeholder: '请选择推理力度',
      defaultValue: 'high',
      options: reasoningEffortOptions,
      clearable: true,
    },
    {
      fieldName: 'textType',
      fieldType: 'select',
      label: '文本类型',
      placeholder: '请选择文本类型',
      defaultValue: 'markdown',
      options: textTypeOptions,
      clearable: true,
    },
    {
      fieldName: 'contextStrategy',
      fieldType: 'select',
      label: '上下文策略',
      placeholder: '请选择上下文策略',
      defaultValue: 'window',
      options: contextStrategyOptions,
      clearable: true,
      onChange: handleContextStrategyChange,
    },
    {
      fieldName: 'contextWindow',
      fieldType: 'custom',
      label: '上下文窗口数',
      dependOn: ['contextStrategy'],
      defaultValue: 6,
      render: ({value, onChange}) => {
        return <Slider value={value} onChange={onChange} {...contextWindowOptions} />; // TODO 不会切换
      },
    },
    {
      fieldName: 'temperature',
      fieldType: 'slider',
      label: '温度',
      defaultValue: 0.7,
      extraProps: {min: 0, max: 2, step: 0.1, marks: {0: '0', 0.5: '0.5', 1: '1', 1.5: '1.5', 2: '2'}},
    },
    {
      fieldName: 'maxTokens',
      fieldType: 'slider',
      label: '最大 Token 数',
      defaultValue: 4096,
      extraProps: {min: 0, max: 8192, step: 1, marks: {0: '0', 2048: '2048', 4096: '4096', 6144: '6144', 8192: '8192'}},
    },
    {
      fieldName: 'topP',
      fieldType: 'slider',
      label: 'Top P',
      defaultValue: 0.9,
      extraProps: {min: 0, max: 1.0, step: 0.01, marks: {0: '0', 0.25: '0.25', 0.5: '0.5', 0.75: '0.75', 1: '1'}},
    },
    {
      fieldName: 'topK',
      fieldType: 'slider',
      label: 'Top K',
      defaultValue: 40,
      extraProps: {min: 0, max: 100, step: 1, marks: {0: '0', 25: '25', 50: '50', 75: '75', 100: '100'}},
    },
    {
      fieldName: 'presencePenalty',
      fieldType: 'slider',
      label: '存在惩罚',
      defaultValue: 0.2,
      extraProps: {min: -2.0, max: 2.0, step: 0.1, marks: {'-2': '-2', '-1': '-1', '0': '0', '1': '1', '2': '2'}},
    },
    {
      fieldName: 'frequencyPenalty',
      fieldType: 'slider',
      label: '频率惩罚',
      defaultValue: 0.2,
      extraProps: {min: -2.0, max: 2.0, step: 0.1, marks: {'-2': '-2', '-1': '-1', '0': '0', '1': '1', '2': '2'}},
    },
    {
      fieldName: 'globalMemoryFlag',
      fieldType: 'switch',
      label: '是否开启全局记忆功能',
      defaultValue: false,
    },
    {
      fieldName: 'queryRewriteFlag',
      fieldType: 'switch',
      label: '是否开启查询重写功能',
      defaultValue: false,
    },
    {
      fieldName: 'knowledgeBaseId',
      fieldType: 'select',
      label: '关联知识库',
      placeholder: '请选择关联知识库',
      options: knowledgeOptions,
      clearable: true,
    },
    {
      fieldName: 'sortOrder',
      fieldType: 'number',
      label: '排序',
      required: true,
      defaultValue: 1,
      placeholder: '请输入排序值',
      rules: [
        {
          pattern: /^[0-9]+$/,
          message: '排序只能输入自然数',
        },
      ],
      extraProps: {min: 0, max: 1000, precision: 0},
    },
    {
      fieldName: 'status',
      fieldType: 'select',
      label: '状态',
      required: true,
      defaultValue: 1,
      options: [
        {label: '启用', value: 1},
        {label: '禁用', value: 3},
      ],
    },
    {
      fieldName: 'description',
      fieldType: 'input',
      label: '描述',
      placeholder: '请输入描述',
      extraProps: {
        rows: 1,
        maxLength: 200,
      },
    },
    {
      fieldName: 'extraParams',
      fieldType: 'json',
      label: '额外参数',
      height: 150,
      fullWidth: true,
    },
  ];

  const handleSubmit = useCallback(async (values: Record<string, any>) => {
    setSubmitLoading(true);
    try {
      const submitData = {...values};
      if (isEdit) {
        await updateAssistant({...submitData, id});
      } else {
        await addAssistant(submitData);
      }
      onSuccess?.();
    } catch (error) {
      console.error('提交失败：', error);
      throw error;
    } finally {
      setSubmitLoading(false);
    }
  }, [isEdit, id, onSuccess, submitLoading]);

  if (loading) {
    return (
      <div style={{padding: '50px 0', textAlign: 'center'}}>
        <Spin size="large" description="加载中..."/>
      </div>
    );
  }

  return (
    <DynamicForm
      fields={fields}
      initialValues={initialValues}
      onSubmit={handleSubmit}
      onCancel={onCancel}
      mode={isEdit ? 'edit' : 'create'}
      size="medium"
      columns={4}
      submitText={isEdit ? '更新' : '创建'}
      cancelText="取消"
      loading={submitLoading}
    />
  );
};
export default AssistantEditForm;
