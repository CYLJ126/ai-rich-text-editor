import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {Button, Col, Form, Row, Space} from 'antd';
import {DownOutlined, ReloadOutlined, SearchOutlined, UpOutlined} from '@ant-design/icons';
import {SearchFieldConfig, SearchFormProps} from './SearchFormTypes';
import {FieldRenderer} from './FieldRenderer';
import {useSearchFormStyles} from './SearchFormStyles';
import {formatFormValues, getDefaultValues} from './searchFormUtils';

const SearchForm: React.FC<SearchFormProps> = ({
                                                   fields,
                                                   size = 'middle',
                                                   onSearch,
                                                   onReset,
                                                   loading = false,
                                                   collapsible = true,
                                                   defaultCollapsed = false,
                                                   collapsedRows = 1,
                                                   searchText = '查询',
                                                   resetText = '重置',
                                                   searchShortcut,
                                                   resetShortcut,
                                                   className,
                                                   style,
                                                   gutter = [8, 8],
                                                   labelCol = 7,
                                                   wrapperCol = 18,
                                               }) => {
    const [form] = Form.useForm();
    const [collapsed, setCollapsed] = useState(defaultCollapsed);
    const [optionsCache, setOptionsCache] = useState<Record<string, any[]>>({});
    const [loadingOptions, setLoadingOptions] = useState<Set<string>>(new Set());
    const {styles} = useSearchFormStyles(size);
    const loadingRef = useRef<Set<string>>(new Set());

    // 计算可见字段
    const visibleFields = useMemo(() => {
        return fields.filter((field) => {
            if (field.visibleFunction) {
                return field.visibleFunction(form.getFieldsValue());
            }
            return true;
        });
    }, [fields, form]);

    // 计算折叠显示的字段
    const displayFields = useMemo(() => {
        if (!collapsible || !collapsed) {
            return visibleFields;
        }

        const alwaysShowFields = visibleFields.filter((field) => field.alwaysShow);
        const normalFields = visibleFields.filter((field) => !field.alwaysShow);

        // 计算每行可显示的字段数（假设24栅格，每个字段占6格）
        const fieldsPerRow = Math.floor(24 / 6);
        const maxFields = fieldsPerRow * collapsedRows;

        return [
            ...alwaysShowFields,
            ...normalFields.slice(0, Math.max(0, maxFields - alwaysShowFields.length)),
        ];
    }, [visibleFields, collapsed, collapsible, collapsedRows]);

    const blankFields = useMemo(() => {
        let blankForms = [];
        const mod = displayFields.length % 4;
        if (mod !== 3) {
            for (let i = 0; i < 3 - mod; i++) {
                // 增加占位，使按钮在最右侧显示
                blankForms.push({
                    fieldName: `_${i}`,
                    fieldType: 'custom' as const,
                    label: '',
                    extraStyle: {
                        display: 'none',
                    },
                });
            }
        }
        return blankForms;
    }, [displayFields.length]);

    // 加载选项数据
    const loadOptions = useCallback(
        async (field: SearchFieldConfig, searchValues: Record<string, any>) => {
            if (!field.optionsLoader) return;

            const fieldName = field.fieldName;

            // 如果正在加载，则跳过
            if (loadingRef.current.has(fieldName)) return;

            try {
                loadingRef.current.add(fieldName);
                setLoadingOptions((prev) => new Set(prev).add(fieldName));

                // 调用外部提供的选项加载器
                const options = await field.optionsLoader(searchValues);

                setOptionsCache((prev) => ({
                    ...prev,
                    [fieldName]: options,
                }));
            } catch (error) {
                console.error(`加载下拉列表失败，字段：${fieldName}。`, error);
            } finally {
                loadingRef.current.delete(fieldName);
                setLoadingOptions((prev) => {
                    const newSet = new Set(prev);
                    newSet.delete(fieldName);
                    return newSet;
                });
            }
        },
        [],
    );

    // 初始化默认值（只在组件首次加载时执行）
    useEffect(() => {
        const defaultValues = getDefaultValues(fields);
        form.setFieldsValue(defaultValues);
    }, []); // 移除 fields 和 form 依赖，只在组件挂载时执行一次

    // 初始化时加载有optionsLoader的字段的选项（只在组件首次加载时执行）
    useEffect(() => {
        const initializeOptions = async () => {
            const currentValues = form.getFieldsValue();

            // 使用 Promise.all 并行加载所有选项
            const loadPromises = fields
                .filter((field) => field.optionsLoader)
                .map((field) => loadOptions(field, currentValues));

            await Promise.all(loadPromises);
        };

        initializeOptions();
    }, []); // 只在组件挂载时执行一次

    // 处理字段值变化
    const handleFieldChange = useCallback(
        async (fieldName: string, value: any) => {
            const currentValues = form.getFieldsValue();
            const updatedValues = {...currentValues, [fieldName]: value};

            // 查找有optionsLoader的字段，重新加载它们的选项
            const fieldsToReload = fields.filter(
                (field) => field.optionsLoader && field.fieldName !== fieldName,
            );

            // 并行加载所有需要重新加载的字段选项
            const loadPromises = fieldsToReload.map((field) => loadOptions(field, updatedValues));

            await Promise.all(loadPromises);
        },
        [fields, form, loadOptions],
    );

    // 处理搜索
    const handleSearch = useCallback(() => {
        form
            .validateFields()
            .then((values) => {
                const formattedValues = formatFormValues(values, fields);
                onSearch(formattedValues);
            })
            .catch((error) => {
                console.error('Form validation failed:', error);
            });
    }, [form, fields, onSearch]);

    // 处理重置
    const handleReset = useCallback(async () => {
        const defaultValues = getDefaultValues(fields);
        form.resetFields();
        form.setFieldsValue(defaultValues);

        // 重置后重新加载所有带optionsLoader的字段选项
        const fieldsWithLoader = fields.filter((field) => field.optionsLoader);
        const loadPromises = fieldsWithLoader.map((field) => loadOptions(field, defaultValues));

        await Promise.all(loadPromises);

        const formattedValues = formatFormValues(defaultValues, fields);
        onReset?.(formattedValues);
        onSearch(formattedValues);
    }, [fields, form, onReset, onSearch, loadOptions]);

    // 快捷键处理
    useEffect(() => {
        const handleKeyDown = (event: KeyboardEvent) => {
            if (searchShortcut && event.key === searchShortcut && (event.ctrlKey || event.metaKey)) {
                event.preventDefault();
                handleSearch();
            }
            if (resetShortcut && event.key === resetShortcut && (event.ctrlKey || event.metaKey)) {
                event.preventDefault();
                handleReset();
            }
        };

        document.addEventListener('keydown', handleKeyDown);
        return () => document.removeEventListener('keydown', handleKeyDown);
    }, [searchShortcut, resetShortcut, handleSearch, handleReset]);

    // 搜索框渲染
    const renderField = useCallback(
        (field: SearchFieldConfig) => {
            const fieldOptions = field.options || optionsCache[field.fieldName] || [];
            const isLoading = loadingOptions.has(field.fieldName);

            return (
                <Col key={field.fieldName} span={field.span || 6}>
                    <Form.Item
                        name={field.fieldName}
                        label={field.label}
                        rules={field.rules}
                        className={`${styles.formItem} ${field.extraClassName || ''}`}
                        style={field.extraStyle}
                        labelCol={{span: labelCol}}
                        wrapperCol={{span: wrapperCol}}
                        colon={false}
                    >
                        <FieldRenderer
                            field={{
                                ...field,
                                options: fieldOptions,
                                // 如果正在加载选项，则禁用字段
                                disabled: field.disabled || isLoading,
                            }}
                            size={size}
                            formValues={form.getFieldsValue()}
                            onChange={(value) => handleFieldChange(field.fieldName, value)}
                        />
                    </Form.Item>
                </Col>
            );
        },
        [
            styles.formItem,
            labelCol,
            wrapperCol,
            optionsCache,
            loadingOptions,
            size,
            form,
            handleFieldChange,
        ],
    );

    return (
        <div className={`${styles.searchForm} ${className || ''}`} style={style}>
            <Form form={form} layout="horizontal">
                <Row gutter={gutter}>
                    {displayFields.map(renderField)}

                    {/* 占位，使按钮在最右侧显示 */}
                    {blankFields.map(renderField)}

                    {/* 操作按钮 */}
                    <Col span={6}>
                        <Form.Item wrapperCol={{span: 24}} colon={false}>
                            <div className={styles.buttonGroup}>
                                <Space>
                                    <Button
                                        type="primary"
                                        icon={<SearchOutlined/>}
                                        loading={loading}
                                        onClick={handleSearch}
                                        title={searchShortcut ? `快捷键: Ctrl+${searchShortcut}` : undefined}
                                    >
                                        {searchText}
                                    </Button>
                                    <Button
                                        icon={<ReloadOutlined/>}
                                        onClick={handleReset}
                                        title={resetShortcut ? `快捷键: Ctrl+${resetShortcut}` : undefined}
                                    >
                                        {resetText}
                                    </Button>

                                    {/* 折叠按钮 */}
                                    {collapsible && (
                                        <span
                                            className={styles.collapseButton}
                                            onClick={() => setCollapsed(!collapsed)}
                                        >
                      {collapsed ? (
                          <>
                              展开 <DownOutlined/>
                          </>
                      ) : (
                          <>
                              收起 <UpOutlined/>
                          </>
                      )}
                    </span>
                                    )}
                                </Space>
                            </div>
                        </Form.Item>
                    </Col>
                </Row>
            </Form>
        </div>
    );
};

export default SearchForm;
