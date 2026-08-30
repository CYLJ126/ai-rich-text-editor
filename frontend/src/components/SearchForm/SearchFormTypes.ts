import {FormRule} from 'antd';
import {type SizeType} from 'antd/es/config-provider/SizeContext';

// 选项项接口
export interface OptionItem {
    label: string;
    value: any;
    disabled?: boolean;
    children?: OptionItem[];
}

// 选项键映射
export interface OptionKeyMap {
    label?: string;
    value?: string;
    children?: string;
}

// 字段配置接口
export interface SearchFieldConfig {
    fieldName: string;
    fieldType:
        | 'input'
        | 'inputNumber'
        | 'password'
        | 'textarea'
        | 'select'
        | 'multiSelect'
        | 'checkboxGroup'
        | 'treeSelect'
        | 'cascader'
        | 'switch'
        | 'radio'
        | 'datePicker'
        | 'dateRangePicker'
        | 'numberRange'
        | 'slider'
        | 'colorPicker'
        | 'rate'
        | 'upload'
        | 'custom';
    label: string;
    span?: number; // 占用的栅格数
    placeholder?: string;
    format?: string;
    options?: OptionItem[];
    disabled?: boolean;
    clearable?: boolean;
    debounce?: number;
    rules?: FormRule[];
    defaultValue?: any;
    extraProps?: Record<string, any>;
    extraClassName?: string;
    extraStyle?: React.CSSProperties;
    alwaysShow?: boolean; // 是否始终显示（不受折叠影响）
    visibleFunction?: (formValues: Record<string, any>) => boolean;
    joinFunction?: (value: any[]) => string;
    transformFunction?: (value: any) => Record<string, any>;
    onChange?: (value: any, fieldName: string) => void;
    onSearch?: (value: any) => void;
    onFocus?: (e: any) => void;
    onBlur?: (e: any) => void;
    render?: (params: {
        value: any;
        onChange: (value: any) => void;
        field: SearchFieldConfig;
        formValues: Record<string, any>;
    }) => React.ReactNode;
    optionKeyMap?: OptionKeyMap;
    // 新增：选项加载器
    optionsLoader?: (searchValues: Record<string, any>) => Promise<OptionItem[]>;
}

// 搜索表单属性
export interface SearchFormProps {
    fields: SearchFieldConfig[];
    size?: SizeType;
    onSearch: (values: Record<string, any>) => void;
    onReset?: (values: Record<string, any>) => void;
    loading?: boolean;
    collapsible?: boolean;
    defaultCollapsed?: boolean;
    collapsedRows?: number;
    searchText?: string;
    resetText?: string;
    searchShortcut?: string;
    resetShortcut?: string;
    className?: string;
    style?: React.CSSProperties;
    gutter?: [number, number];
    labelCol?: number;
    wrapperCol?: number;
}

export {SizeType};
