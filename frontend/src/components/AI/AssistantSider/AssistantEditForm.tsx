import {i18nText} from '@/utils/i18n';
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
      label: i18nText("app.ai.assistantsider.assistanteditform.29139660"),
      required: true,
      placeholder: i18nText("app.ai.assistantsider.assistanteditform.e99cb099"),
    },
    {
      fieldName: 'avatar',
      fieldType: 'icon-picker',
      label: i18nText("app.ai.assistantsider.assistanteditform.5f90c572"),
      required: true,
      placeholder: i18nText("app.ai.assistantsider.assistanteditform.f78d585f"),
    },
    {
      fieldName: 'modelProvider',
      fieldType: 'select',
      label: i18nText("app.ai.assistantsider.assistanteditform.45964902"),
      placeholder: i18nText("app.ai.assistantsider.assistanteditform.74cb1c86"),
      options: providerOptions,
    },
    {
      fieldName: 'modelId',
      fieldType: 'select',
      label: i18nText("app.ai.assistantsider.assistanteditform.de86d311"),
      placeholder: i18nText("app.ai.assistantsider.assistanteditform.184f3007"),
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
      label: i18nText("app.ai.assistantsider.assistanteditform.a65186f6"),
      placeholder: i18nText("app.ai.assistantsider.assistanteditform.69412fd9"),
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
      label: i18nText("app.ai.assistantsider.assistanteditform.bdd27c6f"),
      placeholder: i18nText("app.ai.assistantsider.assistanteditform.be19244b"),
      defaultValue: 'high',
      options: reasoningEffortOptions,
      clearable: true,
    },
    {
      fieldName: 'textType',
      fieldType: 'select',
      label: i18nText("app.ai.assistantsider.assistanteditform.507fe7c4"),
      placeholder: i18nText("app.ai.assistantsider.assistanteditform.64612d40"),
      defaultValue: 'markdown',
      options: textTypeOptions,
      clearable: true,
    },
    {
      fieldName: 'contextStrategy',
      fieldType: 'select',
      label: i18nText("app.ai.assistantsider.assistanteditform.6bc38fd6"),
      placeholder: i18nText("app.ai.assistantsider.assistanteditform.e2f16e48"),
      defaultValue: 'window',
      options: contextStrategyOptions,
      clearable: true,
      onChange: handleContextStrategyChange,
    },
    {
      fieldName: 'contextWindow',
      fieldType: 'custom',
      label: i18nText("app.ai.assistantsider.assistanteditform.2e9efc22"),
      dependOn: ['contextStrategy'],
      defaultValue: 6,
      render: ({value, onChange}) => {
        return <Slider value={value} onChange={onChange} {...contextWindowOptions} />; // TODO 不会切换
      },
    },
    {
      fieldName: 'temperature',
      fieldType: 'slider',
      label: i18nText("app.ai.assistantsider.assistanteditform.850250a0"),
      defaultValue: 0.7,
      extraProps: {min: 0, max: 2, step: 0.1, marks: {0: '0', 0.5: '0.5', 1: '1', 1.5: '1.5', 2: '2'}},
    },
    {
      fieldName: 'maxTokens',
      fieldType: 'slider',
      label: i18nText("app.ai.assistantsider.assistanteditform.9138e5ac"),
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
      label: i18nText("app.ai.assistantsider.assistanteditform.ce644caf"),
      defaultValue: 0.2,
      extraProps: {min: -2.0, max: 2.0, step: 0.1, marks: {'-2': '-2', '-1': '-1', '0': '0', '1': '1', '2': '2'}},
    },
    {
      fieldName: 'frequencyPenalty',
      fieldType: 'slider',
      label: i18nText("app.ai.assistantsider.assistanteditform.1bb46147"),
      defaultValue: 0.2,
      extraProps: {min: -2.0, max: 2.0, step: 0.1, marks: {'-2': '-2', '-1': '-1', '0': '0', '1': '1', '2': '2'}},
    },
    {
      fieldName: 'globalMemoryFlag',
      fieldType: 'switch',
      label: i18nText("app.ai.assistantsider.assistanteditform.fdc4f734"),
      defaultValue: false,
    },
    {
      fieldName: 'queryRewriteFlag',
      fieldType: 'switch',
      label: i18nText("app.ai.assistantsider.assistanteditform.48848d89"),
      defaultValue: false,
    },
    {
      fieldName: 'knowledgeBaseId',
      fieldType: 'select',
      label: i18nText("app.ai.assistantsider.assistanteditform.f33aab5b"),
      placeholder: i18nText("app.ai.assistantsider.assistanteditform.11eda565"),
      options: knowledgeOptions,
      clearable: true,
    },
    {
      fieldName: 'sortOrder',
      fieldType: 'number',
      label: i18nText("app.ai.assistantsider.assistanteditform.32b24e67"),
      required: true,
      defaultValue: 1,
      placeholder: i18nText("app.ai.assistantsider.assistanteditform.9dab7d2e"),
      rules: [
        {
          pattern: /^[0-9]+$/,
          message: i18nText("app.ai.assistantsider.assistanteditform.11f99d0c"),
        },
      ],
      extraProps: {min: 0, max: 1000, precision: 0},
    },
    {
      fieldName: 'status',
      fieldType: 'select',
      label: i18nText("app.ai.assistantsider.assistanteditform.f2f9da7e"),
      required: true,
      defaultValue: 1,
      options: [
        {label: i18nText("app.ai.assistantsider.assistanteditform.aadb1845"), value: 1},
        {label: i18nText("app.ai.assistantsider.assistanteditform.ff41fea7"), value: 3},
      ],
    },
    {
      fieldName: 'description',
      fieldType: 'input',
      label: i18nText("app.ai.assistantsider.assistanteditform.b8f87b98"),
      placeholder: i18nText("app.ai.assistantsider.assistanteditform.d10cecd7"),
      extraProps: {
        rows: 1,
        maxLength: 200,
      },
    },
    {
      fieldName: 'extraParams',
      fieldType: 'json',
      label: i18nText("app.ai.assistantsider.assistanteditform.f18ca8e1"),
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
        <Spin size="large" description={i18nText("app.ai.assistantsider.assistanteditform.31f9d90f")}/>
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
      submitText={isEdit ? i18nText("app.ai.assistantsider.assistanteditform.6a06c9d2") : i18nText("app.ai.assistantsider.assistanteditform.523a0aed")}
      cancelText={i18nText("app.ai.assistantsider.assistanteditform.90ac8f4c")}
      loading={submitLoading}
    />
  );
};
export default AssistantEditForm;
