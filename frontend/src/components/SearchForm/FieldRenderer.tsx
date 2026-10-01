import {
  Cascader,
  Checkbox,
  ColorPicker,
  DatePicker,
  Input,
  InputNumber,
  Radio,
  Rate,
  Select,
  Slider,
  Switch,
  TreeSelect,
  Upload,
} from 'antd';
import React, {useCallback, useMemo} from 'react';
import {i18nText} from '@/utils/i18n';
import {useSearchFormStyles} from './SearchFormStyles';
import type {SearchFieldConfig, SizeType} from './SearchFormTypes';
import {createDebouncedFunction} from './searchFormUtils';

const {TextArea, Password} = Input;
const {RangePicker} = DatePicker;
const {Group: CheckboxGroup} = Checkbox;
const {Group: RadioGroup} = Radio;

interface FieldRendererProps {
  field: SearchFieldConfig;
  value?: any;
  onChange?: (value: any) => void;
  size: SizeType;
  formValues: Record<string, any>;
}

export const FieldRenderer: React.FC<FieldRendererProps> = ({
                                                              field,
                                                              value,
                                                              onChange,
                                                              size,
                                                              formValues,
                                                            }) => {
  const styleSize = size === 'medium' ? 'middle' : size;
  const {styles} = useSearchFormStyles(styleSize);

  const {
    fieldType,
    placeholder,
    format,
    options = [],
    disabled,
    clearable = true,
    debounce: debounceDelay,
    extraProps = {},
    onSearch,
    onFocus,
    onBlur,
    render,
  } = field;

  // 创建防抖函数
  const debouncedOnChange = useMemo(() => {
    if (debounceDelay && onChange) {
      return createDebouncedFunction(onChange, debounceDelay);
    }
    return onChange;
  }, [onChange, debounceDelay]);

  const handleChange = useCallback(
    (val: any) => {
      const changeHandler = debounceDelay ? debouncedOnChange : onChange;
      changeHandler?.(val);
      field.onChange?.(val, field.fieldName);
    },
    [debouncedOnChange, onChange, debounceDelay, field],
  );

  // 自定义渲染
  if (render) {
    return render({value, onChange: handleChange, field, formValues});
  }

  const commonProps = {
    value,
    onChange: handleChange,
    placeholder,
    disabled,
    size,
    onFocus,
    onBlur,
    ...extraProps,
  };

  const clearableProps = clearable ? {allowClear: true} : {};

  switch (fieldType) {
    case 'input':
      return (
        <Input
          className={styles.formItem}
          {...commonProps}
          {...clearableProps}
          onPressEnter={() => onSearch?.(value)}
        />
      );

    case 'inputNumber':
      return (
        <InputNumber
          {...commonProps}
          {...clearableProps}
          style={{width: '100%'}}
          onPressEnter={() => onSearch?.(value)}
        />
      );

    case 'password':
      return (
        <Password
          {...commonProps}
          {...clearableProps}
          onPressEnter={() => onSearch?.(value)}
        />
      );

    case 'textarea':
      return (
        <TextArea
          {...commonProps}
          {...clearableProps}
          autoSize={{minRows: 2, maxRows: 4}}
        />
      );

    case 'select':
      return (
        <Select
          {...commonProps}
          {...clearableProps}
          options={options}
          showSearch
          filterOption={(input, option) =>
            (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
          }
        />
      );

    case 'multiSelect':
      return (
        <Select
          {...commonProps}
          {...clearableProps}
          mode="multiple"
          options={options}
          showSearch
          filterOption={(input, option) =>
            (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
          }
        />
      );

    case 'checkboxGroup':
      return <CheckboxGroup {...commonProps} options={options}/>;

    case 'treeSelect':
      return (
        <TreeSelect
          {...commonProps}
          {...clearableProps}
          treeData={options}
          showSearch
          treeDefaultExpandAll
        />
      );

    case 'cascader':
      return (
        <Cascader
          {...commonProps}
          {...clearableProps}
          options={options}
          showSearch
        />
      );

    case 'switch':
      return (
        <Switch
          checked={Boolean(value)}
          disabled={disabled}
          size={size === 'small' ? 'small' : 'medium'}
          onChange={handleChange}
          {...extraProps}
        />
      );

    case 'radio':
      return <RadioGroup {...commonProps} options={options}/>;

    case 'datePicker':
      return (
        <DatePicker
          {...commonProps}
          {...clearableProps}
          format={format}
          style={{width: '100%'}}
        />
      );

    case 'dateRangePicker':
      return (
        <RangePicker
          value={value}
          onChange={handleChange}
          placeholder={placeholder ? [placeholder, placeholder] : undefined}
          format={format}
          disabled={disabled}
          size={size}
          {...clearableProps}
          {...extraProps}
          style={{width: '100%'}}
        />
      );

    case 'numberRange':
      return (
        <div className={styles.numberRange}>
          <InputNumber
            value={value?.[0]}
            onChange={(val) => handleChange([val, value?.[1]])}
            placeholder={i18nText(
              'app.common.searchform.fieldrenderer.2c40f64a',
            )}
            size={styleSize}
          />
          <span className="range-separator">~</span>
          <InputNumber
            value={value?.[1]}
            onChange={(val) => handleChange([value?.[0], val])}
            placeholder={i18nText(
              'app.common.searchform.fieldrenderer.f0a25ed0',
            )}
            size={styleSize}
          />
        </div>
      );

    case 'slider':
      return <Slider range {...commonProps} style={{width: '100%'}}/>;

    case 'colorPicker':
      return (
        <ColorPicker
          value={value}
          onChange={handleChange}
          disabled={disabled}
          size={size}
          {...extraProps}
        />
      );

    case 'rate':
      return (
        <Rate
          value={value}
          onChange={handleChange}
          disabled={disabled}
          {...extraProps}
        />
      );

    case 'upload':
      return (
        <Upload disabled={disabled} {...extraProps}>
          <Input placeholder={placeholder} readOnly/>
        </Upload>
      );

    default:
      return <Input {...commonProps} {...clearableProps} />;
  }
};
