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
        label: '北京市',
        children: [
            {
                value: '110100',
                label: '市辖区',
                children: [
                    {value: '110101', label: '东城区'},
                    {value: '110102', label: '西城区'},
                    {value: '110105', label: '朝阳区'},
                    {value: '110106', label: '丰台区'},
                    // ... 更多区县
                ],
            },
        ],
    },
    {
        value: '310000',
        label: '上海市',
        children: [
            {
                value: '310100',
                label: '市辖区',
                children: [
                    {value: '310101', label: '黄浦区'},
                    {value: '310104', label: '徐汇区'},
                    {value: '310105', label: '长宁区'},
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
                                                       placeholder = '请选择省市区',
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
