import React, {useEffect, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {Button, Card, message, Spin} from 'antd';
import {ArrowLeftOutlined} from '@ant-design/icons';
import DynamicForm from '@/components/DynamicForm';
import {FormFieldConfig} from '@/components';
import {addUser, getUserByName, updateUser} from '@/services/ant-design-pro/rbac';

const UserForm: React.FC = () => {
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    const mode = (searchParams.get('mode') as 'create' | 'edit' | 'view') || 'create';
    const userName = searchParams.get('userName');
    const id = searchParams.get('id');

    const [loading, setLoading] = useState(false);
    const [submitLoading, setSubmitLoading] = useState(false);
    const [initialValues, setInitialValues] = useState<Record<string, any>>({});
    const [historyData, setHistoryData] = useState<any>(null);

    // 表单字段配置
    const fields: FormFieldConfig[] = [
        {
            fieldName: 'userName',
            fieldType: 'input',
            label: '用户名',
            required: true,
            placeholder: '请输入用户名',
            rules: [
                {min: 2, max: 20, message: '用户名长度应在2-20个字符之间'},
                {
                    pattern: /^[a-zA-Z0-9_\u4e00-\u9fa5]+$/,
                    message: '用户名只能包含字母、数字、下划线和中文',
                },
            ],
            canEdit: mode === 'create', // 编辑时不允许修改用户名
        },
        {
            fieldName: 'mobile',
            fieldType: 'input',
            label: '手机号',
            required: true,
            placeholder: '请输入手机号',
            rules: [{pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号格式'}],
        },
        {
            fieldName: 'email',
            fieldType: 'input',
            label: '邮箱',
            required: true,
            placeholder: '请输入邮箱地址',
            rules: [{type: 'email', message: '请输入正确的邮箱格式'}],
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
            fieldName: 'region',
            fieldType: 'region-picker',
            label: '所在地区',
            placeholder: '请选择所在地区',
            clearable: true,
        },
        {
            fieldName: 'avatar',
            fieldType: 'upload',
            label: '头像',
            extraProps: {
                listType: 'picture-card',
                maxCount: 1,
                accept: 'image/*',
                beforeUpload: (file: File) => {
                    const isImage = file.type.startsWith('image/');
                    if (!isImage) {
                        message.error('只能上传图片文件！');
                        return false;
                    }
                    const isLt2M = file.size / 1024 / 1024 < 2;
                    if (!isLt2M) {
                        message.error('图片大小不能超过2MB！');
                        return false;
                    }
                    return true;
                },
            },
        },
        {
            fieldName: 'birthDate',
            fieldType: 'date-picker',
            label: '出生日期',
            placeholder: '请选择出生日期',
            extraProps: {
                format: 'YYYY-MM-DD',
                disabledDate: (current: any) => current && current > Date.now(),
            },
            clearable: true,
        },
        {
            fieldName: 'gender',
            fieldType: 'radio',
            label: '性别',
            options: [
                {label: '男', value: 1},
                {label: '女', value: 2},
                {label: '未知', value: 0},
            ],
            defaultValue: 0,
        },
        {
            fieldName: 'description',
            fieldType: 'textarea',
            label: '描述',
            placeholder: '请输入用户描述信息',
            fullWidth: true,
            extraProps: {
                rows: 4,
                maxLength: 500,
                showCount: true,
            },
        },
        {
            fieldName: 'settings',
            fieldType: 'custom',
            label: '个人设置',
            fullWidth: true,
            render: (props) => (
                <div style={{padding: '12px', border: '1px solid #d9d9d9', borderRadius: '6px'}}>
                    <div style={{marginBottom: 8}}>
                        <label>
                            <input
                                type="checkbox"
                                checked={props.value?.emailNotification || false}
                                onChange={(e) =>
                                    props.onChange({
                                        ...props.value,
                                        emailNotification: e.target.checked,
                                    })
                                }
                            />
                            <span style={{marginLeft: 8}}>接收邮件通知</span>
                        </label>
                    </div>
                    <div>
                        <label>
                            <input
                                type="checkbox"
                                checked={props.value?.smsNotification || false}
                                onChange={(e) =>
                                    props.onChange({
                                        ...props.value,
                                        smsNotification: e.target.checked,
                                    })
                                }
                            />
                            <span style={{marginLeft: 8}}>接收短信通知</span>
                        </label>
                    </div>
                </div>
            ),
            defaultValue: {
                emailNotification: true,
                smsNotification: false,
            },
        },
    ];

    const loadUserData = async () => {
        if (!userName) return;

        setLoading(true);
        try {
            const userData = await getUserByName(userName);
            if (userData) {
                setInitialValues(userData);
                if (mode === 'view') {
                    setHistoryData({
                        createTime: userData.createTime,
                        createBy: userData.createBy,
                        updateTime: userData.updateTime,
                        updateBy: userData.updateBy,
                    });
                }
            } else {
                console.error('获取用户信息失败');
                navigate('/Administration/UserManagement');
            }
        } catch (error) {
            console.error('获取用户信息异常：', error);
            navigate('/Administration/UserManagement');
        } finally {
            setLoading(false);
        }
    };

    // 获取用户数据
    useEffect(() => {
        if (userName && (mode === 'edit' || mode === 'view')) {
            loadUserData().then();
        }
    }, [userName, mode]);

    // 处理表单提交
    const handleSubmit = async (values: Record<string, any>) => {
        setSubmitLoading(true);
        try {
            const {...submitData} = values;

            // 如果是编辑模式且密码为空，则不更新密码
            if (mode === 'edit' && !submitData.password) {
                delete submitData.password;
            }

            let response;
            if (mode === 'create') {
                response = await addUser(submitData);
            } else {
                response = await updateUser({id: id, userName: userName, ...submitData});
            }

            if (response) {
                message.success(`${mode === 'create' ? '创建' : '更新'}用户成功`);
                navigate('/Administration/UserManagement');
            } else {
                message.error(`${mode === 'create' ? '创建' : '更新'}用户失败`);
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
        navigate('/Administration/UserManagement');
    };

    // 获取页面标题
    const getPageTitle = () => {
        switch (mode) {
            case 'create':
                return '新增用户';
            case 'edit':
                return '编辑用户';
            case 'view':
                return '查看用户';
            default:
                return '用户管理';
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

export default UserForm;
