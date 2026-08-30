import {debounce} from 'lodash';
import {OptionItem, OptionKeyMap} from './SearchFormTypes';

export const transformOptions = (data: any[], keyMap: OptionKeyMap = {}): OptionItem[] => {
    const {label = 'label', value = 'value', children = 'children'} = keyMap;

    return data.map((item) => ({
        label: item[label],
        value: item[value],
        disabled: item.disabled,
        children: item[children] ? transformOptions(item[children], keyMap) : undefined,
    }));
};

export const createDebouncedFunction = (fn: (...args: any) => any, delay: number) => {
    return debounce(fn, delay);
};

export const formatFormValues = (
    values: Record<string, any>,
    fields: any[],
): Record<string, any> => {
    let result = {...values};

    fields.forEach((field) => {
        const {fieldName, transformFunction, joinFunction} = field;
        const value = result[fieldName];

        if (value !== undefined && value !== null && value !== '') {
            // 应用连接函数
            if (joinFunction && Array.isArray(value)) {
                result[fieldName] = joinFunction(value);
            }

            // 应用转换函数
            if (transformFunction) {
                delete result[fieldName];
                result = {...result, ...transformFunction(value)};
            }
        }
    });

    return result;
};

export const getDefaultValues = (fields: any[]): Record<string, any> => {
    const defaultValues: Record<string, any> = {};

    fields.forEach((field) => {
        if (field.defaultValue !== undefined) {
            defaultValues[field.fieldName] = field.defaultValue;
        }
    });

    return defaultValues;
};
