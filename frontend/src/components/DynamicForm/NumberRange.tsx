import React from 'react';
import {InputNumber, Space} from 'antd';

interface NumberRangeProps {
    value?: [number?, number?];
    onChange?: (value: [number?, number?]) => void;
    placeholder?: [string, string];
    disabled?: boolean;
    style?: React.CSSProperties;
    className?: string;
}

const NumberRange: React.FC<NumberRangeProps> = ({
                                                     value = [],
                                                     onChange,
                                                     placeholder = ['最小值', '最大值'],
                                                     disabled,
                                                     style,
                                                     className,
                                                 }) => {
    const [min, max] = value;

    const handleMinChange = (newMin: number | null) => {
        if (onChange) {
            onChange([newMin || undefined, max]);
        }
    };

    const handleMaxChange = (newMax: number | null) => {
        if (onChange) {
            onChange([min, newMax || undefined]);
        }
    };

    return (
        <div className={className} style={style}>
            <Space.Compact>
                <InputNumber
                    value={min}
                    onChange={handleMinChange}
                    placeholder={placeholder[0]}
                    disabled={disabled}
                    style={{width: '50%'}}
                />
                <InputNumber
                    value={max}
                    onChange={handleMaxChange}
                    placeholder={placeholder[1]}
                    disabled={disabled}
                    style={{width: '50%'}}
                />
            </Space.Compact>
        </div>
    );
};

export default NumberRange;
