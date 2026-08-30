import {
  Button,
  Cascader,
  Checkbox,
  Col,
  ColorPicker,
  DatePicker,
  Form,
  Input,
  InputNumber,
  message,
  Modal,
  Radio,
  Rate,
  Row,
  Select,
  Slider,
  Switch,
  Tabs,
  TreeSelect,
  Upload,
} from 'antd';
import {debounce} from 'lodash';
import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import type {DynamicFormProps, FormFieldConfig} from './FormField';
import HistoryTab from './HistoryTab';
import NumberRange from './NumberRange';
import RegionPicker from './RegionPicker';
import {IconPicker} from '@/components/DynamicIcon';
import {JsonEditor} from "@/components";

const { RangePicker } = DatePicker;
const { TextArea } = Input;

const DynamicForm: React.FC<DynamicFormProps> = ({
  fields,
  initialValues = {},
  onSubmit,
  onCancel,
  mode = 'create',
  size = 'medium',
  columns = 2,
  submitText = '提交',
  cancelText = '取消',
  submitShortcut = 'Ctrl+S',
  cancelShortcut = 'Escape',
  loading = false,
  historyData,
}) => {
  const [form] = Form.useForm();
  const [formData, setFormData] = useState<Record<string, any>>(initialValues);
  const formDataRef = useRef(formData);
  formDataRef.current = formData;
  const [optionsCache, setOptionsCache] = useState<Record<string, any[]>>({});
  const [submitConfirmVisible, setSubmitConfirmVisible] = useState(false);
  const [activeTab, setActiveTab] = useState('form');

  // 样式配置
  const sizeConfig = useMemo(() => {
    const configs = {
      small: { height: 24, fontSize: 13 },
      medium: { height: 28, fontSize: 16 },
      large: { height: 32, fontSize: 20 },
    };
    return configs[size];
  }, [size]);

  // 计算栅格列宽
  const colSpan = 24 / columns;

  // 初始化表单数据
  const prevInitialValuesRef = useRef<string>('');
  useEffect(() => {
    const serialized = JSON.stringify(initialValues);
    if (serialized === prevInitialValuesRef.current) return;
    prevInitialValuesRef.current = serialized;

    form.setFieldsValue(initialValues);
    setFormData(initialValues);
    // 有自定义选项的，加载选项数据
    fields.forEach((f) => {
      if (f.loadOptionsFunc) {
        let dependentValues = {};
        if (f.dependOn) {
          dependentValues = f.dependOn.reduce(
            (acc, dep) => {
              acc[dep] = initialValues[dep];
              return acc;
            },
            {} as Record<string, any>,
          );
        }
        f.loadOptionsFunc(f, dependentValues).then((res) => setOptionsCache((prev) => ({
          ...prev,
          [f.fieldName]: res,
        })));
      }
    });
  });


  const handleSubmitClick = () => {
    setSubmitConfirmVisible(true);
  };

  const handleCancel = () => {
    if (onCancel) {
      onCancel();
    }
  };

  // 键盘快捷键
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (mode === 'view') return;

      const isCtrlS = e.ctrlKey && e.key === 's';
      const isEscape = e.key === 'Escape';

      if (isCtrlS && submitShortcut === 'Ctrl+S') {
        e.preventDefault();
        handleSubmitClick();
      } else if (isEscape && cancelShortcut === 'Escape') {
        e.preventDefault();
        handleCancel();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [mode, submitShortcut, cancelShortcut]);

  // 加载选项数据
  const defaultLoadOptions = useCallback(
    async (field: FormFieldConfig, dependentValues?: Record<string, any>) => {
      if (!field.optionUrl) return;

      try {
        const params = dependentValues || {};
        const response = await fetch(
          `${field.optionUrl}?${new URLSearchParams(params)}`,
        );
        const data = await response.json();

        const keyMap = field.optionKeyMap || { label: 'label', value: 'value' };
        const options = data.map((item: any) => ({
          label: item[keyMap.label],
          value: item[keyMap.value],
          ...item,
        }));

        setOptionsCache((prev) => ({
          ...prev,
          [field.fieldName]: options,
        }));
      } catch (error) {
        console.error(`Failed to load options for ${field.fieldName}:`, error);
        message.error(`加载${field.label}选项失败`).then();
      }
    },
    [],
  );

  // 处理字段值变化
  const handleFieldChange = useCallback(
    (field: FormFieldConfig, value: any) => {
      const newFormData = { ...formDataRef.current, [field.fieldName]: value };

      // 执行自定义 onChange
      if (field.onChange) {
        field.onChange(value, field.fieldName, newFormData);
      }

      // 处理依赖字段
      fields.forEach((f) => {
        if (f.dependOn && f.dependOn.includes(field.fieldName)) {
          const dependentValues = f.dependOn.reduce(
            (acc, dep) => {
              acc[dep] = newFormData[dep];
              return acc;
            },
            {} as Record<string, any>,
          );
          if (f.loadOptionsFunc) {
            f.loadOptionsFunc(f, dependentValues).then((res) => setOptionsCache((prev) => ({
              ...prev,
              [f.fieldName]: res,
            })));
          } else {
            defaultLoadOptions(f, dependentValues).then();
          }
          newFormData[f.fieldName] = undefined;
          form.setFieldValue(f.fieldName, undefined);
          if (f.onChange) {
            f.onChange(undefined, f.fieldName, newFormData);
          }
        }
      });
      setFormData(newFormData);
    },
    [fields, defaultLoadOptions, form],
  );

  // 创建防抖处理函数
  const createDebouncedHandler = useCallback(
    (field: FormFieldConfig) => {
      if (!field.debounce) {
        return (value: any) => handleFieldChange(field, value);
      }
      return debounce(
        (value: any) => handleFieldChange(field, value),
        field.debounce,
      );
    },
    [handleFieldChange],
  );

  // 渲染表单控件
  const renderFormItem = (field: FormFieldConfig) => {
    const isDisabled =
      mode === 'view' || (mode === 'edit' && field.canEdit === false);
    const commonProps = {
      defaultValue: field.defaultValue,
      style: {
        width: field.width || '100%',
        height: field.height || sizeConfig.height,
        fontSize: sizeConfig.fontSize,
        ...field.extraStyle,
      },
      className: field.extraClassName,
      disabled: isDisabled,
      placeholder: field.placeholder,
      ...field.extraProps,
    };

    const debouncedHandler = createDebouncedHandler(field);

    switch (field.fieldType) {
      case 'input':
        return (
          <Input
            {...commonProps}
            onChange={(e) => debouncedHandler(e.target.value)}
            allowClear={field.clearable}
          />
        );

      case 'textarea':
        return (
          <TextArea
            {...commonProps}
            style={{ ...commonProps.style, height: undefined }}
            rows={field.extraProps?.rows || 4}
            onChange={(e) => debouncedHandler(e.target.value)}
            allowClear={field.clearable}
          />
        );

      case 'number':
        return (
          <InputNumber
            {...commonProps}
            onChange={(value) => debouncedHandler(value)}
          />
        );

      case 'password':
        return (
          <Input.Password
            {...commonProps}
            onChange={(e) => debouncedHandler(e.target.value)}
            allowClear={field.clearable}
          />
        );

      case 'select':
        return (
          <Select
            {...commonProps}
            onChange={(value) => handleFieldChange(field, value)}
            allowClear={field.clearable}
            options={field.options || optionsCache[field.fieldName] || []}
            mode={field.extraProps?.multiple ? 'multiple' : undefined}
          />
        );

      case 'checkbox-group':
        return (
          <Checkbox.Group
            {...commonProps}
            options={field.options || optionsCache[field.fieldName] || []}
            onChange={(value) => handleFieldChange(field, value)}
          />
        );

      case 'tree-select':
        return (
          <TreeSelect
            {...commonProps}
            treeData={field.options || optionsCache[field.fieldName] || []}
            onChange={(value) => handleFieldChange(field, value)}
            allowClear={field.clearable}
          />
        );

      case 'cascader':
        return (
          <Cascader
            {...commonProps}
            options={field.options || optionsCache[field.fieldName] || []}
            onChange={(value) => handleFieldChange(field, value)}
            allowClear={field.clearable}
          />
        );

      case 'switch':
        return (
          <Switch
            {...commonProps}
            style={{...commonProps.style, width: 50, height: 23}}
            onChange={(checked) => handleFieldChange(field, checked)}
          />
        );

      case 'radio':
        return (
          <Radio.Group
            {...commonProps}
            options={field.options || optionsCache[field.fieldName] || []}
            onChange={(e) => handleFieldChange(field, e.target.value)}
          />
        );

      case 'date-picker':
        return (
          <DatePicker
            {...commonProps}
            onChange={(date, dateString) =>
              handleFieldChange(field, dateString)
            }
            format={field.extraProps?.format || 'YYYY-MM-DD'}
            allowClear={field.clearable}
          />
        );

      case 'date-range-picker':
        return (
          <RangePicker
            {...commonProps}
            placeholder={['开始日期', '结束日期']}
            onChange={(dates, dateStrings) =>
              handleFieldChange(field, dateStrings)
            }
            format={field.extraProps?.format || 'YYYY-MM-DD'}
            allowClear={field.clearable}
          />
        );

      case 'number-range':
        return (
          <NumberRange
            {...commonProps}
            onChange={(value) => handleFieldChange(field, value)}
            placeholder={[
              field.placeholder || '最小值',
              field.placeholder || '最大值',
            ]}
          />
        );

      case 'slider':
        return (
          <div className="pr-4">
            <Slider
              {...commonProps}
              style={{...commonProps.style, margin: 5}}
              min={field.extraProps?.min}
              max={field.extraProps?.max}
              step={field.extraProps?.step}
              marks={field.extraProps?.marks}
              onChange={(value: any) => handleFieldChange(field, value)}
              tooltip={{ open: field.extraProps?.tooltip }}
            />
          </div>
        );

      case 'sliderRange':
        return (
          <div>
            <Slider
              {...commonProps}
              min={field.extraProps?.min}
              max={field.extraProps?.max}
              step={field.extraProps?.step}
              marks={field.extraProps?.marks}
              onChange={(value: any) => handleFieldChange(field, value)}
              range={field.extraProps?.range}
              tooltip={{ open: field.extraProps?.tooltip }}
            />
          </div>
        );

      case 'json':
        return (
          <JsonEditor
            style={commonProps.style}
            className={commonProps.className}
            readOnly={isDisabled}
            height={field.height || 150}
            onChange={(parsed: any) => handleFieldChange(field, parsed)}
          />
        );

      case 'color-picker':
        return (
          <ColorPicker
            {...commonProps}
            onChange={(color) => handleFieldChange(field, color.toHexString())}
          />
        );

      case 'icon-picker':
        return (
          <IconPicker
            {...commonProps}
            onChange={(value) => handleFieldChange(field, value)}
          />
        );

      case 'rate':
        return (
          <Rate
            {...commonProps}
            onChange={(value) => handleFieldChange(field, value)}
          />
        );

      case 'upload':
        return (
          <Upload
            {...commonProps}
            onChange={(info) => handleFieldChange(field, info.fileList)}
          >
            <Button>上传文件</Button>
          </Upload>
        );

      case 'region-picker':
        return (
          <RegionPicker
            {...commonProps}
            onChange={(value) => handleFieldChange(field, value)}
            allowClear={field.clearable}
          />
        );

      case 'custom':
        return field.render
          ? field.render({
              ...commonProps,
              value: formData[field.fieldName],
              onChange: (value: any) => handleFieldChange(field, value),
            })
          : null;

      default:
        return <div></div>; // 渲染一个空 div 占位
    }
  };

  // 处理提交
  const handleSubmit = async () => {
    try {
      await form.validateFields();
      const values = form.getFieldsValue();

      // 应用转换函数
      const transformedValues = { ...values };
      fields.forEach((field) => {
        if (
          field.transformFunction &&
          transformedValues[field.fieldName] !== undefined
        ) {
          transformedValues[field.fieldName] = field.transformFunction(
            transformedValues[field.fieldName],
          );
        }
      });

      if (onSubmit) {
        await onSubmit(transformedValues);
        message.success(`${mode === 'create' ? '创建' : '更新'}成功`).then();
      }
    } catch (error) {
      console.error('Submit error:', error);
      if (error instanceof Error) {
        message.error(error.message).then();
      }
    } finally {
      setSubmitConfirmVisible(false);
    }
  };

  // 渲染表单项
  const renderFormItems = () => {
    return fields
      .filter(
        (field) => !field.visibleFunction || field.visibleFunction(formData),
      )
      .map((field) => {
        const span = field.fullWidth ? 24 : field.span || colSpan;

        return (
          <Col key={field.fieldName} span={span}>
            <Form.Item
              name={field.fieldName}
              className="mb-2! px-4!"
              label={
                <div style={{ fontSize: sizeConfig.fontSize }}>
                  {field.label}
                </div>
              }
              rules={[
                ...(field.required
                  ? [{ required: true, message: `请输入${field.label}` }]
                  : []),
                ...(field.rules || []),
              ]}
              initialValue={field.defaultValue}
            >
              {renderFormItem(field)}
            </Form.Item>
          </Col>
        );
      });
  };

  const tabItems = [
    {
      key: 'form',
      label: '基本信息',
      children: (
        <Form
          form={form}
          layout="vertical"
          className={`dynamic-form dynamic-form-${size}`}
          style={{ fontSize: sizeConfig.fontSize }}
        >
          <Row gutter={[16, 0]}>{renderFormItems()}</Row>
        </Form>
      ),
    },
  ];

  if (mode === 'view' && historyData) {
    tabItems.push({
      key: 'history',
      label: '历史记录',
      children: <HistoryTab data={historyData} />,
    });
  }

  return (
    <div className="w-full h-full overflow-auto scrollbar-none">
      {/* 内容容器：正常流式布局 */}
      <div className="w-full flex flex-col">
        {mode === 'view' ? (
          <Tabs
            activeKey={activeTab}
            onChange={setActiveTab}
            items={tabItems}
          />
        ) : (
          <Form
            form={form}
            layout="vertical"
            className={`dynamic-form dynamic-form-${size} shrink-0`}
            style={{ fontSize: sizeConfig.fontSize }}
          >
            <Row gutter={[16, 0]}>{renderFormItems()}</Row>
          </Form>
        )}

        {mode !== 'view' && (
          <div className="dynamic-form-actions w-full mt-4 pr-4 flex justify-end gap-2 shrink-0">
            <Button
              type="primary"
              onClick={handleSubmit}
              loading={loading}
              size={
                size === 'large'
                  ? 'large'
                  : size === 'small'
                    ? 'small'
                    : 'middle'
              }
            >
              {submitText} ({submitShortcut})
            </Button>
            <Button
              onClick={handleCancel}
              size={
                size === 'large'
                  ? 'large'
                  : size === 'small'
                    ? 'small'
                    : 'middle'
              }
              style={{ marginLeft: 8 }}
            >
              {cancelText} ({cancelShortcut})
            </Button>
          </div>
        )}

        <Modal
          title="确认提交"
          open={submitConfirmVisible}
          onOk={handleSubmit}
          onCancel={() => setSubmitConfirmVisible(false)}
          okText="确认"
          cancelText="取消"
        >
          确定要{mode === 'create' ? '创建' : '更新'}此记录吗？
        </Modal>
      </div>
    </div>
  );
};

export default DynamicForm;
