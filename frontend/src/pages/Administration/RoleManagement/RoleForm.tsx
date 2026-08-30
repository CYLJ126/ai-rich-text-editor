import React, {useEffect, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Button, Card, message, Spin} from 'antd';
import {ArrowLeftOutlined} from '@ant-design/icons';
import DynamicForm from '@/components/DynamicForm';
import {FormFieldConfig} from '@/components';
import {addRole, getRoleByCode, updateRole} from '@/services/ant-design-pro/rbac';

const RoleForm: React.FC = () => {
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    const mode = (searchParams.get('mode') as 'create' | 'edit' | 'view') || 'create';
    const roleCode = searchParams.get('roleCode');
    const id = searchParams.get('id');

    const [loading, setLoading] = useState(false);
    const [submitLoading, setSubmitLoading] = useState(false);
    const [initialValues, setInitialValues] = useState<Record<string, any>>({});
    const [historyData, setHistoryData] = useState<any>(null);

    // 表单字段配置
    const fields: FormFieldConfig[] = [
        {
            fieldName: 'roleCode',
            fieldType: 'input',
            label: '角色编码',
            required: true,
            placeholder: '请输入角色编码',
            rules: [
                {min: 2, max: 32, message: '角色编码长度应在2-32个字符之间'},
                {
                    pattern: /^[a-zA-Z0-9_]+$/,
                    message: '角色编码只能包含字母、数字、下划线',
                },
            ],
            canEdit: mode === 'create', // 编辑时不允许修改用户名
        },
        {
            fieldName: 'roleName',
            fieldType: 'input',
            label: '角色名称',
            required: true,
            placeholder: '请输入角色名称',
            rules: [
                {min: 2, max: 32, message: '角色名称长度应在2-32个字符之间'},
                {
                    pattern: /^[a-zA-Z0-9_ \u4e00-\u9fa5]+$/,
                    message: '角色名称只能包含字母、数字、下划线、空格和中文',
                },
            ],
            canEdit: mode === 'create', // 编辑时不允许修改用户名
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
            fieldName: 'description',
            fieldType: 'textarea',
            label: '描述',
            placeholder: '请输入角色描述信息',
            fullWidth: true,
            extraProps: {
                rows: 4,
                maxLength: 500,
                showCount: true,
            },
        },
    ];

    const loadRoleData = async () => {
        if (!roleCode) return;

        setLoading(true);
        try {
            const roleData = await getRoleByCode(roleCode);
            if (roleData) {
                setInitialValues(roleData);
                if (mode === 'view') {
                    setHistoryData({
                        createTime: roleData.createTime,
                        createBy: roleData.createBy,
                        updateTime: roleData.updateTime,
                        updateBy: roleData.updateBy,
                    });
                }
            } else {
                console.error('获取角色信息失败');
                navigate('/Administration/RoleManagement');
            }
        } catch (error) {
            console.error('获取角色信息异常：', error);
            navigate('/Administration/RoleManagement');
        } finally {
            setLoading(false);
        }
    };

    // 获取角色数据
    useEffect(() => {
        if (roleCode && (mode === 'edit' || mode === 'view')) {
            loadRoleData().then();
        }
    }, [roleCode, mode]);

    // 处理表单提交
    const handleSubmit = async (values: Record<string, any>) => {
        setSubmitLoading(true);
        try {
            const {...submitData} = values;

            let response;
            if (mode === 'create') {
                response = await addRole(submitData);
            } else {
                response = await updateRole({id: id, roleCode: roleCode, ...submitData});
            }

            if (response) {
                message.success(`${mode === 'create' ? '创建' : '更新'}角色成功`);
                navigate('/Administration/RoleManagement');
            } else {
                message.error(`${mode === 'create' ? '创建' : '更新'}角色失败`);
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
        navigate('/Administration/RoleManagement');
    };

    // 获取页面标题
    const getPageTitle = () => {
        switch (mode) {
            case 'create':
                return '新增角色';
            case 'edit':
                return '编辑角色';
            case 'view':
                return '查看角色';
            default:
                return '角色管理';
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

export default RoleForm;
