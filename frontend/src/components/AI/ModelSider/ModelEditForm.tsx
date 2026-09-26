import {i18nText} from '@/utils/i18n';
import React, {useEffect, useState} from 'react';
import {Spin} from 'antd';
import {DynamicForm} from '@/components';
import type {FormFieldConfig} from '@/components/DynamicForm/FormField';
import {
  addModelConfig,
  getModelConfig,
  listModelProviders,
  updateModelConfig,
} from '@/services/ant-design-pro/ai.rbac';
import {GMCrypto} from "@/utils/crypto/gmCrypto";
import {
  type AvailableModel,
  listAvailableModels,
} from '@/components/AI/ModelSider/modelService';

const MASKED_API_KEY = '********';

interface ProviderOption {
  label: string;
  value: string;
  defaultApiBaseUrl?: string;
}

interface ModelEditFormProps {
  id?: number;
  onSuccess?: () => void;
  onCancel?: () => void;
}

const ModelEditForm = ({
                             id,
                             onSuccess,
                             onCancel,
                       }: ModelEditFormProps) => {
  const isEdit = !!id;
  const [loading, setLoading] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [initialValues, setInitialValues] = useState<Record<string, any>>({});

  const [providerOptions, setProviderOptions] = useState<ProviderOption[]>([]);
  const [availableModels, setAvailableModels] = useState<AvailableModel[]>([]);

  // 加载下拉选项数据
  useEffect(() => {
    const loadOptions = async () => {
      try {
        const providersRes = await listModelProviders();
        providersRes && setProviderOptions([...providersRes] as ProviderOption[]);
      } catch (error) {
        console.error('加载模型提供商选项数据失败：', error);
      }
    };
    loadOptions().then();
  }, []);

  // 编辑模式加载模型配置数据
  useEffect(() => {
    if (!isEdit || !id) return;

    const loadModelData = async () => {
      setLoading(true);
      try {
        const data = await getModelConfig(id);
        if (data) {
          setInitialValues(data);
        }
      } catch (error) {
        console.error('获取助手信息失败：', error);
      } finally {
        setLoading(false);
      }
    };

    loadModelData().then();
  }, [id, isEdit]);

  const fields: FormFieldConfig[] = [
    {
      fieldName: 'provider',
      fieldType: 'select',
      label: i18nText("app.ai.modelsider.modeleditform.bb3d9ba5"),
      required: true,
      placeholder: i18nText("app.ai.modelsider.modeleditform.0795c3e4"),
      options: providerOptions,
      onChange: (value: string, _field, formData) => ({
        apiBaseUrl: providerOptions.find((item) => item.value === value)
          ?.defaultApiBaseUrl,
        apiKey:
          isEdit && initialValues.provider === value
            ? formData.apiKey
            : undefined,
      }),
    },
    {
      fieldName: 'apiKey',
      fieldType: 'password',
      label: i18nText("app.ai.modelsider.modeleditform.3f2b7a7d"),
      required: true,
      placeholder: i18nText("app.ai.modelsider.modeleditform.b41d4008"),
      debounce: 600,
      extraProps: {
        // 已保存的密钥只允许替换，不允许在编辑页切换为明文显示。
        visibilityToggle: !isEdit,
      },
      transformFunction: (value: string) => {
        if (value === MASKED_API_KEY) return value;
        // 已是 SM2 密文则跳过，避免编辑时对后端返回的密文重复加密
        if (GMCrypto.isSM2Encrypted(value)) return value;
        return GMCrypto.sm2Encrypt(value, localStorage.getItem('platform-public-key') || '');
      },
    },
    {
      fieldName: 'apiBaseUrl',
      fieldType: 'input',
      label: i18nText("app.ai.modelsider.modeleditform.fb4b43e1"),
      required: true,
      placeholder: i18nText("app.ai.modelsider.modeleditform.b9ed74af"),
      debounce: 600,
    },
    {
      fieldName: 'modelId',
      fieldType: 'select',
      label: i18nText("app.ai.modelsider.modeleditform.743ba271"),
      required: true,
      placeholder: i18nText("app.ai.modelsider.modeleditform.345c1867"),
      dependOn: ['provider', 'apiKey', 'apiBaseUrl'],
      loadOptionsFunc: async (_, dependentValues) => {
        const provider = dependentValues?.provider;
        const apiKey = dependentValues?.apiKey;
        if (!provider || !apiKey) {
          setAvailableModels([]);
          return [];
        }
        const encryptedApiKey =
          apiKey === MASKED_API_KEY || GMCrypto.isSM2Encrypted(apiKey)
            ? apiKey
            : GMCrypto.sm2Encrypt(
                apiKey,
                localStorage.getItem('platform-public-key') || '',
              );
        const models =
          (await listAvailableModels({
            provider,
            modelConfigId: id,
            apiKey: encryptedApiKey,
            apiBaseUrl: dependentValues?.apiBaseUrl,
          })) || [];
        setAvailableModels(models);
        return models.map((model) => ({
          label:
            model.modelName && model.modelName !== model.modelId
              ? `${model.modelName} (${model.modelId})`
              : model.modelId,
          value: model.modelId,
        }));
      },
      onChange: (value: string) => {
        const model = availableModels.find((item) => item.modelId === value);
        if (!model) return;
        return {
          supportVision: model.supportVision,
          supportFunction: model.supportFunction,
          supportThinking: model.supportThinking,
          supportSearch: model.supportSearch,
          contextWindow: model.contextWindow,
          maxTokens: model.maxTokens,
        };
      },
      extraProps: {
        showSearch: true,
        optionFilterProp: 'label',
      },
    },
    {
      fieldName: 'apiVersion',
      fieldType: 'input',
      label: i18nText("app.ai.modelsider.modeleditform.e95e2bdd"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.b598ad3b"),
    },
    {
      fieldName: 'contextWindow',
      fieldType: 'number',
      label: i18nText("app.ai.modelsider.modeleditform.0dcb5e49"),
      required: false,
    },
    {
      fieldName: 'maxTokens',
      fieldType: 'number',
      label: i18nText("app.ai.modelsider.modeleditform.1a077c0b"),
      required: false,
    },
    {
      fieldName: 'supportVision',
      fieldType: 'select',
      label: i18nText("app.ai.modelsider.modeleditform.4af5509c"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.b1e6a82c"),
      options: [
        {label: i18nText("app.ai.modelsider.modeleditform.f8d44568"), value: true},
        {label: i18nText("app.ai.modelsider.modeleditform.b2b54bc3"), value: false},
      ],
    },
    {
      fieldName: 'supportFunction',
      fieldType: 'select',
      label: i18nText("app.ai.modelsider.modeleditform.e8843de3"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.72aa0f0c"),
      options: [
        {label: i18nText("app.ai.modelsider.modeleditform.f8d44568"), value: true},
        {label: i18nText("app.ai.modelsider.modeleditform.b2b54bc3"), value: false},
      ],
    },
    {
      fieldName: 'supportThinking',
      fieldType: 'select',
      label: i18nText("app.ai.modelsider.modeleditform.6a0f084a"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.13459154"),
      options: [
        {label: i18nText("app.ai.modelsider.modeleditform.f8d44568"), value: true},
        {label: i18nText("app.ai.modelsider.modeleditform.b2b54bc3"), value: false},
      ],
    },
    {
      fieldName: 'supportSearch',
      fieldType: 'select',
      label: i18nText("app.ai.modelsider.modeleditform.420de5ba"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.6bfbd6d0"),
      options: [
        {label: i18nText("app.ai.modelsider.modeleditform.f8d44568"), value: true},
        {label: i18nText("app.ai.modelsider.modeleditform.b2b54bc3"), value: false},
      ],
    },
    {
      fieldName: 'supportPromptCaching',
      fieldType: 'select',
      label: i18nText("app.ai.modelsider.modeleditform.7e8f8c2f"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.e53e93df"),
      options: [
        {label: i18nText("app.ai.modelsider.modeleditform.f8d44568"), value: true},
        {label: i18nText("app.ai.modelsider.modeleditform.b2b54bc3"), value: false},
      ],
    },
    {
      fieldName: 'orgId',
      fieldType: 'select',
      label: i18nText("app.ai.modelsider.modeleditform.bae60abc"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.a16a06e4"),
      options: [
        {label: i18nText("app.ai.modelsider.modeleditform.301d9e41"), value: 1},
        {label: i18nText("app.ai.modelsider.modeleditform.6dadd797"), value: 2},
      ],
    },
    {
      fieldName: 'inputUnitPrice',
      fieldType: 'number',
      label: i18nText("app.ai.modelsider.modeleditform.de3083d4"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.2a8c9b2a"),
    },
    {
      fieldName: 'outputUnitPrice',
      fieldType: 'number',
      label: i18nText("app.ai.modelsider.modeleditform.948c1217"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.8c89526c"),
    },
    {
      fieldName: 'priceCurrency',
      fieldType: 'select',
      label: i18nText("app.ai.modelsider.modeleditform.0aed44a0"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.110bd4b4"),
      options: [
        {label: 'CNY', value: 'CNY'},
        {label: 'USD', value: 'USD'},
      ]
    },
    {
      fieldName: 'proxy',
      fieldType: 'input',
      label: i18nText("app.ai.modelsider.modeleditform.8f23ef75"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.39c90149"),
    },
    {
      fieldName: 'requestsPerMinute',
      fieldType: 'number',
      label: i18nText("app.ai.modelsider.modeleditform.fb74f081"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.de79ea68"),
    },
    {
      fieldName: 'tokensPerMinute',
      fieldType: 'number',
      label: i18nText("app.ai.modelsider.modeleditform.8716d64a"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.818065d1"),
    },
    {
      fieldName: 'dailyRequestLimit',
      fieldType: 'number',
      label: i18nText("app.ai.modelsider.modeleditform.4f11f17f"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.eebd8105"),
    },
    {
      fieldName: 'concurrencyLimit',
      fieldType: 'number',
      label: i18nText("app.ai.modelsider.modeleditform.e0874fdf"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.44a1b9eb"),
    },
    {
      fieldName: 'timeoutSeconds',
      fieldType: 'number',
      label: i18nText("app.ai.modelsider.modeleditform.82e55cff"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.6fcc72fc"),
    },
    {
      fieldName: 'maxRetries',
      fieldType: 'number',
      label: i18nText("app.ai.modelsider.modeleditform.28d1eb62"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.9f5b8505"),
    },
    {
      fieldName: 'sortOrder',
      fieldType: 'number',
      label: i18nText("app.ai.modelsider.modeleditform.d94dc205"),
      required: true,
      defaultValue: 1,
      placeholder: i18nText("app.ai.modelsider.modeleditform.6892fb9f"),
      rules: [
        {
          pattern: /^[0-9]+$/,
          message: i18nText("app.ai.modelsider.modeleditform.f013f731"),
        },
      ],
      extraProps: {min: 0, max: 1000, precision: 0},
    },
    {
      fieldName: 'status',
      fieldType: 'select',
      label: i18nText("app.ai.modelsider.modeleditform.8363b67c"),
      required: true,
      defaultValue: 1,
      options: [
        {label: i18nText("app.ai.modelsider.modeleditform.535e21e7"), value: 1},
        {label: i18nText("app.ai.modelsider.modeleditform.2af436d1"), value: 3},
      ],
    },
    {
      fieldName: 'description',
      fieldType: 'input',
      label: i18nText("app.ai.modelsider.modeleditform.a223d373"),
      required: false,
      placeholder: i18nText("app.ai.modelsider.modeleditform.f4aea1c2"),
      span: 12,
      extraProps: {
        rows: 1,
        maxLength: 200,
      },
    },
    {
      fieldName: 'defaultParams',
      fieldType: 'json',
      label: i18nText("app.ai.modelsider.modeleditform.7a726839"),
      required: false,
      height: 150,
      fullWidth: true,
    },
  ];

  const handleSubmit = async (values: Record<string, any>) => {
    setSubmitLoading(true);
    try {
      const selectedModel = availableModels.find(
        (model) => model.modelId === values.modelId,
      );
      const submitData = {
        ...values,
        modelName:
          selectedModel?.modelName ||
          (values.modelId === initialValues.modelId
            ? initialValues.modelName
            : undefined) ||
          values.modelId,
      };
      if (isEdit) {
        await updateModelConfig({...submitData, id});
      } else {
        await addModelConfig(submitData);
      }
      onSuccess?.();
    } catch (error) {
      console.error('提交失败：', error);
      throw error;
    } finally {
      setSubmitLoading(false);
    }
  };

  if (loading) {
    return (
      <div style={{padding: '50px 0', textAlign: 'center'}}>
        <Spin size="large" description={i18nText("app.ai.modelsider.modeleditform.a46c7b8a")}/>
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
      submitText={isEdit ? i18nText("app.ai.modelsider.modeleditform.be1c57f4") : i18nText("app.ai.modelsider.modeleditform.c0c098dc")}
      cancelText={i18nText("app.ai.modelsider.modeleditform.3ea69b66")}
      loading={submitLoading}
    />
  );
};
export default ModelEditForm;
