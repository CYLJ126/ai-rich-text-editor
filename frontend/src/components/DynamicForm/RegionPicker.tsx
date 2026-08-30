import {i18nText} from '@/utils/i18n';
// components/DynamicForm/components/RegionPicker.tsx
import React from 'react';
import {Cascader} from 'antd';

interface RegionPickerProps {
    value?: string[];
    onChange?: (value: string[]) => void;
    disabled?: boolean;
    allowClear?: boolean;
    placeholder?: string;
    style?: React.CSSProperties;
    className?: string;
}

// 模拟省市区数据，实际使用时可以从API获取
const regionOptions = [
    {
        value: '110000',
        label: i18nText("app.common.dynamicform.regionpicker.f2b540c1"),
        children: [
            {
                value: '110100',
                label: i18nText("app.common.dynamicform.regionpicker.2fea4aaa"),
                children: [
                    {value: '110101', label: i18nText("app.common.dynamicform.regionpicker.4fe14009")},
                    {value: '110102', label: i18nText("app.common.dynamicform.regionpicker.0a15dc77")},
                    {value: '110105', label: i18nText("app.common.dynamicform.regionpicker.19150792")},
                    {value: '110106', label: i18nText("app.common.dynamicform.regionpicker.f1f57899")},
                    // ... 更多区县
                ],
            },
        ],
    },
    {
        value: '310000',
        label: i18nText("app.common.dynamicform.regionpicker.d8764a82"),
        children: [
            {
                value: '310100',
                label: i18nText("app.common.dynamicform.regionpicker.2fea4aaa"),
                children: [
                    {value: '310101', label: i18nText("app.common.dynamicform.regionpicker.9ed0b920")},
                    {value: '310104', label: i18nText("app.common.dynamicform.regionpicker.febd71ea")},
                    {value: '310105', label: i18nText("app.common.dynamicform.regionpicker.0d450b4f")},
                    // ... 更多区县
                ],
            },
        ],
    },
    // ... 更多省份
];

const RegionPicker: React.FC<RegionPickerProps> = ({
                                                       value,
                                                       onChange,
                                                       disabled,
                                                       allowClear,
                                                       placeholder = i18nText("app.common.dynamicform.regionpicker.4bfe0a8b"),
                                                       style,
                                                       className,
                                                   }) => {
    return (
        <Cascader
            value={value}
            onChange={onChange}
            options={regionOptions}
            disabled={disabled}
            allowClear={allowClear}
            placeholder={placeholder}
            style={style}
            className={className}
            expandTrigger="hover"
            displayRender={(labels) => labels.join(' / ')}
        />
    );
};

export default RegionPicker;
