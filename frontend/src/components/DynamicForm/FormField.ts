import React from "react";

export interface FormFieldConfig {
  fieldName: string;
  fieldType:
      | 'input'
      | 'textarea'
      | 'number'
      | 'password'
      | 'select'
      | 'checkbox-group'
      | 'tree-select'
      | 'cascader'
      | 'switch'
      | 'radio'
      | 'date-picker'
      | 'date-range-picker'
      | 'number-range'
      | 'slider'
      | 'sliderRange'
      | 'json'
      | 'color-picker'
      | 'icon-picker'
      | 'rate'
      | 'upload'
      | 'region-picker'
      | 'custom';
  label: string;
  required?: boolean;
  canEdit?: boolean;
  transformFunction?: (value: any) => any;
  placeholder?: string;
  defaultValue?: any;
  options?: Array<{
    label: string;
    value: any;
    [key: string]: any
  }>;
  optionUrl?: string;
  optionKeyMap?: {
    label: string;
    value: string
  };
  width?: number;
  height?: number;
  clearable?: boolean;
  dependOn?: string[];
  loadOptionsFunc?: (field: FormFieldConfig, dependentValues?: Record<string, any>) => Promise<any>;
  visibleFunction?: (formData: any) => boolean;
  rules?: any[];
  render?: (props: any) => React.ReactNode;
  debounce?: number;
  extraProps?: Record<string, any>;
  extraClassName?: string;
  extraStyle?: React.CSSProperties;
  onChange?: (value: any, field: string, formData: any) => void;
  fullWidth?: boolean; // 是否占满一行
  span?: number; // 自定义列宽
}

export interface DynamicFormProps {
  fields: FormFieldConfig[];
  initialValues?: Record<string, any>;
  onSubmit?: (values: Record<string, any>) => Promise<void>;
  onCancel?: () => void;
  mode?: 'create' | 'edit' | 'view';
  size?: 'small' | 'medium' | 'large';
  columns?: 2 | 3 | 4;
  submitText?: string;
  cancelText?: string;
  submitShortcut?: string; // 如 'Ctrl+S'
  cancelShortcut?: string; // 如 'Escape'
  loading?: boolean;
  historyData?: {
    createTime?: string;
    createBy?: string;
    updateTime?: string;
    updateBy?: string;
  };
}

export type FormSize = 'small' | 'medium' | 'large';
