import React, {useEffect, useState} from 'react';
import {Spin} from 'antd';
import {DynamicForm} from '@/components';
import {FormFieldConfig} from '@/components/DynamicForm/FormField';
import {
  addModelConfig,
  getModelConfig,
  listModelProviders,
  updateModelConfig,
} from '@/services/ant-design-pro/ai.rbac';
import {GMCrypto} from "@/utils/crypto/gmCrypto";

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

  const [providerOptions, setProviderOptions] = useState<{ label: string; value: string }[]>([]);

  // 加载下拉选项数据
  useEffect(() => {
    const loadOptions = async () => {
      try {
        const providersRes = await listModelProviders();
        providersRes && setProviderOptions([...providersRes]);
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
      label: '模型提供商',
      required: true,
      placeholder: '请选择模型提供商',
      options: providerOptions,
    },
    {
      fieldName: 'modelId',
      fieldType: 'input',
      label: '模型 ID',
      required: true,
      placeholder: '请输入模型 ID',
    },
    {
      fieldName: 'modelName',
      fieldType: 'input',
      label: '名称',
      required: true,
      placeholder: '请输入模型名称',
    },
    {
      fieldName: 'apiKey',
      fieldType: 'input',
      label: 'API 密钥',
      required: true,
      placeholder: '请输入 API 密钥',
      transformFunction: (value: string) => {
        // 已是 SM2 密文则跳过，避免编辑时对后端返回的密文重复加密
        if (GMCrypto.isSM2Encrypted(value)) return value;
        return GMCrypto.sm2Encrypt(value, localStorage.getItem('platform-public-key') || '');
      },
    },
    {
      fieldName: 'apiBaseUrl',
      fieldType: 'input',
      label: 'API 基础 URL',
      required: true,
      placeholder: '请输入 API 基础 URL',
    },
    {
      fieldName: 'apiVersion',
      fieldType: 'input',
      label: 'API 版本',
      required: false,
      placeholder: '请输入 API 版本',
    },
    {
      fieldName: 'contextWindow',
      fieldType: 'number',
      label: '上下文窗口大小',
      required: false,
    },
    {
      fieldName: 'maxTokens',
      fieldType: 'number',
      label: '最大输出 token 数',
      required: false,
    },
    {
      fieldName: 'supportVision',
      fieldType: 'select',
      label: '是否支持视觉',
      required: false,
      placeholder: '请选择是否支持视觉',
      options: [
        {label: '是', value: true},
        {label: '否', value: false},
      ],
    },
    {
      fieldName: 'supportFunction',
      fieldType: 'select',
      label: '是否支持函数',
      required: false,
      placeholder: '请选择是否支持函数',
      options: [
        {label: '是', value: true},
        {label: '否', value: false},
      ],
    },
    {
      fieldName: 'supportThinking',
      fieldType: 'select',
      label: '是否支持思考',
      required: false,
      placeholder: '请选择是否支持思考',
      options: [
        {label: '是', value: true},
        {label: '否', value: false},
      ],
    },
    {
      fieldName: 'supportSearch',
      fieldType: 'select',
      label: '是否支持搜索',
      required: false,
      placeholder: '请选择是否支持搜索',
      options: [
        {label: '是', value: true},
        {label: '否', value: false},
      ],
    },
    {
      fieldName: 'supportPromptCaching',
      fieldType: 'select',
      label: '是否支持提示缓存',
      required: false,
      placeholder: '请选择是否支持提示缓存',
      options: [
        {label: '是', value: true},
        {label: '否', value: false},
      ],
    },
    {
      fieldName: 'orgId',
      fieldType: 'select',
      label: '组织 ID',
      required: false,
      placeholder: '请输入组织 ID',
      options: [
        {label: '组织 ID 1', value: 1},
        {label: '组织 ID 2', value: 2},
      ],
    },
    {
      fieldName: 'inputUnitPrice',
      fieldType: 'number',
      label: '输入单价',
      required: false,
      placeholder: '请输入输入单价',
    },
    {
      fieldName: 'outputUnitPrice',
      fieldType: 'number',
      label: '输出单价',
      required: false,
      placeholder: '请输入输出单价',
    },
    {
      fieldName: 'priceCurrency',
      fieldType: 'select',
      label: '货币单位',
      required: false,
      placeholder: '请选择货币单位',
      options: [
        {label: 'CNY', value: 'CNY'},
        {label: 'USD', value: 'USD'},
      ]
    },
    {
      fieldName: 'proxy',
      fieldType: 'input',
      label: '代理',
      required: false,
      placeholder: '请输入代理',
    },
    {
      fieldName: 'requestsPerMinute',
      fieldType: 'number',
      label: '每分钟最大请求数（RPM）',
      required: false,
      placeholder: '请输入每分钟最大请求数（RPM）',
    },
    {
      fieldName: 'tokensPerMinute',
      fieldType: 'number',
      label: '每分钟最大 Token 数（TPM）',
      required: false,
      placeholder: '请输入每分钟最大 Token 数（TPM）',
    },
    {
      fieldName: 'dailyRequestLimit',
      fieldType: 'number',
      label: '每日最大请求数',
      required: false,
      placeholder: '请输入每日最大请求数',
    },
    {
      fieldName: 'concurrencyLimit',
      fieldType: 'number',
      label: '并发请求数限制',
      required: false,
      placeholder: '请输入并发请求数限制',
    },
    {
      fieldName: 'timeoutSeconds',
      fieldType: 'number',
      label: '请求超时时间（秒）',
      required: false,
      placeholder: '请输入请求超时时间（秒）',
    },
    {
      fieldName: 'maxRetries',
      fieldType: 'number',
      label: '最大重试次数',
      required: false,
      placeholder: '请输入最大重试次数',
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
      required: false,
      placeholder: '请输入描述',
      span: 12,
      extraProps: {
        rows: 1,
        maxLength: 200,
      },
    },
    {
      fieldName: 'defaultParams',
      fieldType: 'json',
      label: '默认参数',
      required: false,
      height: 150,
      fullWidth: true,
    },
  ];

  const handleSubmit = async (values: Record<string, any>) => {
    setSubmitLoading(true);
    try {
      const submitData = {...values};
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
export default ModelEditForm;
