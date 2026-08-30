import React, {useEffect, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Button, Card, message, Spin} from 'antd';
import {ArrowLeftOutlined} from '@ant-design/icons';
import DynamicForm from '@/components/DynamicForm';
import {FormFieldConfig} from '@/components';
import {addMenu, getMenuByCode, updateMenu} from '@/services/ant-design-pro/rbac';

const MenuForm: React.FC = () => {
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    const mode = (searchParams.get('mode') as 'create' | 'edit' | 'view') || 'create';
    const menuCode = searchParams.get('menuCode');
    const id = searchParams.get('id');

    const [loading, setLoading] = useState(false);
    const [submitLoading, setSubmitLoading] = useState(false);
    const [initialValues, setInitialValues] = useState<Record<string, any>>({});
    const [historyData, setHistoryData] = useState<any>(null);

    // 表单字段配置
    const fields: FormFieldConfig[] = [
        {
            fieldName: 'menuCode',
            fieldType: 'input',
            label: '菜单编码',
            required: true,
            placeholder: '请输入菜单编码',
            rules: [
                {min: 2, max: 32, message: '菜单编码长度应在2-32个字符之间'},
                {
                    pattern: /^[a-zA-Z0-9_]+$/,
                    message: '菜单编码只能包含字母、数字、下划线',
                },
            ],
            canEdit: mode === 'create', // 编辑时不允许修改用户名
        },
        {
            fieldName: 'menuName',
            fieldType: 'input',
            label: '菜单名称',
            required: true,
            placeholder: '请输入菜单名称',
            rules: [
                {min: 2, max: 32, message: '菜单名称长度应在2-32个字符之间'},
                {
                    pattern: /^[a-zA-Z0-9_ \u4e00-\u9fa5]+$/,
                    message: '菜单名称只能包含字母、数字、下划线、空格和中文',
                },
            ],
            canEdit: mode === 'create', // 编辑时不允许修改用户名
        },
        {
            fieldName: 'icon',
            fieldType: 'select',
            label: '图标',
            required: false,
            placeholder: '请选择图标',
            options: [
                {label: 'smile', value: 'smile'},
                {label: 'crown', value: 'crown'},
                {label: 'userOutlined', value: 'userOutlined'},
                {label: 'tool', value: 'tool'},
                {label: 'tag', value: 'tag'},
                {label: 'loading', value: 'loading'},
                {label: 'book', value: 'book'},
                {label: 'administration', value: 'administration'},
            ],
        },
        {
            fieldName: 'menuUrl',
            fieldType: 'input',
            label: '菜单 URL',
            required: true,
            placeholder: '请输入菜单URL',
        },
        {
            fieldName: 'status',
            fieldType: 'select',
            label: '状态',
            required: true,
            options: [
                {label: '初始', value: 0},
                {label: '正常', value: 1},
                {label: '完成', value: 2},
                {label: '注销', value: 3},
            ],
            defaultValue: mode === 'create' ? 0 : undefined,
        },
        {
            fieldName: 'fatherId',
            fieldType: 'select',
            label: '父菜单ID',
            placeholder: '请选择父菜单ID',
            clearable: true,
        },
        {
            fieldName: 'orderId',
            fieldType: 'input',
            label: '排序ID',
            placeholder: '请输入排序ID',
        },
        {
            fieldName: 'showFlag',
            fieldType: 'radio',
            label: '显示标志',
            options: [
                {label: '是', value: 1},
                {label: '否', value: 0},
            ],
            defaultValue: 0,
        },
        {
            fieldName: 'description',
            fieldType: 'textarea',
            label: '描述',
            placeholder: '请输入菜单描述信息',
            fullWidth: true,
            extraProps: {
                rows: 4,
                maxLength: 500,
                showCount: true,
            },
        },
    ];

    const loadMenuData = async () => {
        if (!menuCode) return;

        setLoading(true);
        try {
            const menuData = await getMenuByCode(menuCode);
            if (menuData) {
                setInitialValues(menuData);
                if (mode === 'view') {
                    setHistoryData({
                        createTime: menuData.createTime,
                        createBy: menuData.createBy,
                        updateTime: menuData.updateTime,
                        updateBy: menuData.updateBy,
                    });
                }
            } else {
                console.error('获取菜单信息失败');
                navigate('/Administration/MenuManagement');
            }
        } catch (error) {
            console.error('获取菜单信息异常：', error);
            navigate('/Administration/MenuManagement');
        } finally {
            setLoading(false);
        }
    };

    // 获取菜单数据
    useEffect(() => {
        if (menuCode && (mode === 'edit' || mode === 'view')) {
            loadMenuData().then();
        }
    }, [menuCode, mode]);

    // 处理表单提交
    const handleSubmit = async (values: Record<string, any>) => {
        setSubmitLoading(true);
        try {
            const {...submitData} = values;

            let response;
            if (mode === 'create') {
                response = await addMenu(submitData);
            } else {
                response = await updateMenu({id: id, menuCode: menuCode, ...submitData});
            }

            if (response) {
                message.success(`${mode === 'create' ? '创建' : '更新'}菜单成功`);
                navigate('/Administration/MenuManagement');
            } else {
                message.error(`${mode === 'create' ? '创建' : '更新'}菜单失败`);
            }
        } catch (error) {
            console.error('Submit error:', error);
            throw error; // 让 DynamicForm 处理错误显示
        } finally {
            setSubmitLoading(false);
        }
    };

    // 处理取消操作
    const handleCancel = () => {
        navigate('/Administration/MenuManagement');
    };

    // 获取页面标题
    const getPageTitle = () => {
        switch (mode) {
            case 'create':
                return '新增菜单';
            case 'edit':
                return '编辑菜单';
            case 'view':
                return '查看菜单';
            default:
                return '菜单管理';
        }
    };

    if (loading) {
        return (
            <div style={{padding: '50px 0', textAlign: 'center'}}>
                <Spin size="large" tip="加载中..."/>
            </div>
        );
    }

    return (
        <div style={{padding: '24px'}}>
            <Card
                title={
                    <div style={{display: 'flex', alignItems: 'center', gap: '12px'}}>
                        <Button
                            type="text"
                            icon={<ArrowLeftOutlined/>}
                            onClick={handleCancel}
                            style={{padding: '4px 8px'}}
                        >
                            返回
                        </Button>
                        <span>{getPageTitle()}</span>
                    </div>
                }
                style={{minHeight: 'calc(100vh - 48px)'}}
            >
                <DynamicForm
                    fields={fields}
                    initialValues={initialValues}
                    onSubmit={handleSubmit}
                    onCancel={handleCancel}
                    mode={mode}
                    size="medium"
                    columns={3}
                    submitText={mode === 'create' ? '创建' : '更新'}
                    cancelText="取消"
                    submitShortcut="Ctrl+S"
                    cancelShortcut="Escape"
                    loading={submitLoading}
                    historyData={historyData}
                />
            </Card>
        </div>
    );
};

export default MenuForm;
