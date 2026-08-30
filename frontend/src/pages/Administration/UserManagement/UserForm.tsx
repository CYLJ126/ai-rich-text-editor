import { i18nText } from '@/utils/i18n';
import React, { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Button, Card, message, Spin } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import DynamicForm from '@/components/DynamicForm';
import { FormFieldConfig } from '@/components';
import {
  addUser,
  getUserByName,
  updateUser,
} from '@/services/ant-design-pro/rbac';

const UserForm: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const mode =
    (searchParams.get('mode') as 'create' | 'edit' | 'view') || 'create';
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
      label: i18nText('app.administration.usermanagement.userform.2010d9bd'),
      required: true,
      placeholder: i18nText(
        'app.administration.usermanagement.userform.f64c1174',
      ),
      rules: [
        {
          min: 2,
          max: 20,
          message: i18nText(
            'app.administration.usermanagement.userform.dbc0d0ee',
          ),
        },
        {
          pattern: /^[a-zA-Z0-9_\u4e00-\u9fa5]+$/,
          message: i18nText(
            'app.administration.usermanagement.userform.bdde238b',
          ),
        },
      ],
      canEdit: mode === 'create', // 编辑时不允许修改用户名
    },
    {
      fieldName: 'mobile',
      fieldType: 'input',
      label: i18nText('app.administration.usermanagement.userform.282771f1'),
      required: true,
      placeholder: i18nText(
        'app.administration.usermanagement.userform.56f168db',
      ),
      rules: [
        {
          pattern: /^1[3-9]\d{9}$/,
          message: i18nText(
            'app.administration.usermanagement.userform.21c76f85',
          ),
        },
      ],
    },
    {
      fieldName: 'email',
      fieldType: 'input',
      label: i18nText('app.administration.usermanagement.userform.3e5db104'),
      required: true,
      placeholder: i18nText(
        'app.administration.usermanagement.userform.4f12da58',
      ),
      rules: [
        {
          type: 'email',
          message: i18nText(
            'app.administration.usermanagement.userform.f98b0a91',
          ),
        },
      ],
    },
    {
      fieldName: 'status',
      fieldType: 'select',
      label: i18nText('app.administration.usermanagement.userform.8a1217de'),
      required: true,
      options: [
        {
          label: i18nText(
            'app.administration.usermanagement.userform.473e0758',
          ),
          value: 0,
        },
        {
          label: i18nText(
            'app.administration.usermanagement.userform.ae33339c',
          ),
          value: 1,
        },
        {
          label: i18nText(
            'app.administration.usermanagement.userform.953cb973',
          ),
          value: 2,
        },
        {
          label: i18nText(
            'app.administration.usermanagement.userform.f607f504',
          ),
          value: 3,
        },
      ],
      defaultValue: mode === 'create' ? 0 : undefined,
    },
    {
      fieldName: 'region',
      fieldType: 'region-picker',
      label: i18nText('app.administration.usermanagement.userform.95e42584'),
      placeholder: i18nText(
        'app.administration.usermanagement.userform.3d6e374b',
      ),
      clearable: true,
    },
    {
      fieldName: 'avatar',
      fieldType: 'upload',
      label: i18nText('app.administration.usermanagement.userform.945abf39'),
      extraProps: {
        listType: 'picture-card',
        maxCount: 1,
        accept: 'image/*',
        beforeUpload: (file: File) => {
          const isImage = file.type.startsWith('image/');
          if (!isImage) {
            message.error(
              i18nText('app.administration.usermanagement.userform.fcd81126'),
            );
            return false;
          }
          const isLt2M = file.size / 1024 / 1024 < 2;
          if (!isLt2M) {
            message.error(
              i18nText('app.administration.usermanagement.userform.ed726e9b'),
            );
            return false;
          }
          return true;
        },
      },
    },
    {
      fieldName: 'birthDate',
      fieldType: 'date-picker',
      label: i18nText('app.administration.usermanagement.userform.70b2a5ea'),
      placeholder: i18nText(
        'app.administration.usermanagement.userform.3ec81285',
      ),
      extraProps: {
        format: 'YYYY-MM-DD',
        disabledDate: (current: any) => current && current > Date.now(),
      },
      clearable: true,
    },
    {
      fieldName: 'gender',
      fieldType: 'radio',
      label: i18nText('app.administration.usermanagement.userform.dc818210'),
      options: [
        {
          label: i18nText(
            'app.administration.usermanagement.userform.1973baac',
          ),
          value: 1,
        },
        {
          label: i18nText(
            'app.administration.usermanagement.userform.ebbe66a5',
          ),
          value: 2,
        },
        {
          label: i18nText(
            'app.administration.usermanagement.userform.4288e1de',
          ),
          value: 0,
        },
      ],
      defaultValue: 0,
    },
    {
      fieldName: 'description',
      fieldType: 'textarea',
      label: i18nText('app.administration.usermanagement.userform.e6a7787e'),
      placeholder: i18nText(
        'app.administration.usermanagement.userform.58bb3ea8',
      ),
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
      label: i18nText('app.administration.usermanagement.userform.d47d5f82'),
      fullWidth: true,
      render: (props) => (
        <div
          style={{
            padding: '12px',
            border: '1px solid #d9d9d9',
            borderRadius: '6px',
          }}
        >
          <div style={{ marginBottom: 8 }}>
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
              <span style={{ marginLeft: 8 }}>
                {i18nText(
                  'app.administration.usermanagement.userform.81f596aa',
                )}
              </span>
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
              <span style={{ marginLeft: 8 }}>
                {i18nText(
                  'app.administration.usermanagement.userform.06765ed4',
                )}
              </span>
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
      const { ...submitData } = values;

      // 如果是编辑模式且密码为空，则不更新密码
      if (mode === 'edit' && !submitData.password) {
        delete submitData.password;
      }

      let response;
      if (mode === 'create') {
        response = await addUser(submitData);
      } else {
        response = await updateUser({
          id: id,
          userName: userName,
          ...submitData,
        });
      }

      if (response) {
        message.success(
          i18nText('app.administration.usermanagement.userform.27a4aead', {
            value0: i18nText(
              mode === 'create'
                ? 'app.administration.usermanagement.userform.23ccb958'
                : 'app.administration.usermanagement.userform.0497bef2',
            ),
          }),
        );
        navigate('/Administration/UserManagement');
      } else {
        message.error(
          i18nText('app.administration.usermanagement.userform.838a6480', {
            value0: i18nText(
              mode === 'create'
                ? 'app.administration.usermanagement.userform.23ccb958'
                : 'app.administration.usermanagement.userform.0497bef2',
            ),
          }),
        );
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
        return i18nText('app.administration.usermanagement.userform.e787ffa3');
      case 'edit':
        return i18nText('app.administration.usermanagement.userform.864941c0');
      case 'view':
        return i18nText('app.administration.usermanagement.userform.92b81d65');
      default:
        return i18nText('app.administration.usermanagement.userform.d96d3a92');
    }
  };

  if (loading) {
    return (
      <div style={{ padding: '50px 0', textAlign: 'center' }}>
        <Spin
          size="large"
          tip={i18nText('app.administration.usermanagement.userform.309e4f1f')}
        />
      </div>
    );
  }

  return (
    <div style={{ padding: '24px' }}>
      <Card
        title={
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={handleCancel}
              style={{ padding: '4px 8px' }}
            >
              {i18nText('app.administration.usermanagement.userform.5c561906')}
            </Button>
            <span>{getPageTitle()}</span>
          </div>
        }
        style={{ minHeight: 'calc(100vh - 48px)' }}
      >
        <DynamicForm
          fields={fields}
          initialValues={initialValues}
          onSubmit={handleSubmit}
          onCancel={handleCancel}
          mode={mode}
          size="medium"
          columns={3}
          submitText={
            mode === 'create'
              ? i18nText('app.administration.usermanagement.userform.23ccb958')
              : i18nText('app.administration.usermanagement.userform.0497bef2')
          }
          cancelText={i18nText(
            'app.administration.usermanagement.userform.52436c95',
          )}
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
