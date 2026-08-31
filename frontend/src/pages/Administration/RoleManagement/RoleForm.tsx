import { i18nText } from '@/utils/i18n';
import React, { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Button, Card, message, Spin } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import DynamicForm from '@/components/DynamicForm';
import { FormFieldConfig } from '@/components';
import {
  addRole,
  getRoleByCode,
  updateRole,
} from '@/services/ant-design-pro/rbac';
import {useComponentHeight} from '@/utils/useDynamicHeight';


const RoleForm: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const mode = (searchParams.get('mode') as 'create' | 'edit' | 'view') || 'create';
  const roleCode = searchParams.get('roleCode');
  const id = searchParams.get('id');
  const componentHeight = useComponentHeight();

  const [loading, setLoading] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [initialValues, setInitialValues] = useState<Record<string, any>>({});
  const [historyData, setHistoryData] = useState<any>(null);

  // 表单字段配置
  const fields: FormFieldConfig[] = [
    {
      fieldName: 'roleCode',
      fieldType: 'input',
      label: i18nText('app.administration.rolemanagement.roleform.c7593aa0'),
      required: true,
      placeholder: i18nText(
        'app.administration.rolemanagement.roleform.5962a1cf',
      ),
      rules: [
        {
          min: 2,
          max: 32,
          message: i18nText(
            'app.administration.rolemanagement.roleform.e703964d',
          ),
        },
        {
          pattern: /^[a-zA-Z0-9_]+$/,
          message: i18nText(
            'app.administration.rolemanagement.roleform.5dcbaa37',
          ),
        },
      ],
      canEdit: mode === 'create', // 编辑时不允许修改用户名
    },
    {
      fieldName: 'roleName',
      fieldType: 'input',
      label: i18nText('app.administration.rolemanagement.roleform.3c3f061e'),
      required: true,
      placeholder: i18nText(
        'app.administration.rolemanagement.roleform.be955653',
      ),
      rules: [
        {
          min: 2,
          max: 32,
          message: i18nText(
            'app.administration.rolemanagement.roleform.4c3d7aa2',
          ),
        },
        {
          pattern: /^[a-zA-Z0-9_ \u4e00-\u9fa5]+$/,
          message: i18nText(
            'app.administration.rolemanagement.roleform.3eff5ab2',
          ),
        },
      ],
      canEdit: mode === 'create', // 编辑时不允许修改用户名
    },
    {
      fieldName: 'status',
      fieldType: 'select',
      label: i18nText('app.administration.rolemanagement.roleform.ccf51344'),
      required: true,
      options: [
        {
          label: i18nText(
            'app.administration.rolemanagement.roleform.34419c56',
          ),
          value: 0,
        },
        {
          label: i18nText(
            'app.administration.rolemanagement.roleform.1a54e8d4',
          ),
          value: 1,
        },
        {
          label: i18nText(
            'app.administration.rolemanagement.roleform.843ee2fd',
          ),
          value: 2,
        },
        {
          label: i18nText(
            'app.administration.rolemanagement.roleform.8d51139e',
          ),
          value: 3,
        },
      ],
      defaultValue: mode === 'create' ? 0 : undefined,
    },
    {
      fieldName: 'description',
      fieldType: 'textarea',
      label: i18nText('app.administration.rolemanagement.roleform.6129c5ee'),
      placeholder: i18nText(
        'app.administration.rolemanagement.roleform.7e561a7e',
      ),
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
      const { ...submitData } = values;

      let response;
      if (mode === 'create') {
        response = await addRole(submitData);
      } else {
        response = await updateRole({
          id: id,
          roleCode: roleCode,
          ...submitData,
        });
      }

      if (response) {
        message.success(
          i18nText('app.administration.rolemanagement.roleform.89af1f10', {
            value0: i18nText(
              mode === 'create'
                ? 'app.administration.rolemanagement.roleform.ec6a795a'
                : 'app.administration.rolemanagement.roleform.e6712707',
            ),
          }),
        );
        navigate('/Administration/RoleManagement');
      } else {
        message.error(
          i18nText('app.administration.rolemanagement.roleform.9533a139', {
            value0: i18nText(
              mode === 'create'
                ? 'app.administration.rolemanagement.roleform.ec6a795a'
                : 'app.administration.rolemanagement.roleform.e6712707',
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
    navigate('/Administration/RoleManagement');
  };

  // 获取页面标题
  const getPageTitle = () => {
    switch (mode) {
      case 'create':
        return i18nText('app.administration.rolemanagement.roleform.663bfd49');
      case 'edit':
        return i18nText('app.administration.rolemanagement.roleform.99d060b3');
      case 'view':
        return i18nText('app.administration.rolemanagement.roleform.dd127eb6');
      default:
        return i18nText('app.administration.rolemanagement.roleform.41f237b2');
    }
  };

  if (loading) {
    return (
      <div style={{ padding: '50px 0', textAlign: 'center' }}>
        <Spin
          size="large"
          description={i18nText('app.administration.rolemanagement.roleform.53b9f065')}
        />
      </div>
    );
  }

  return (
    <div style={{ padding: '24px', height: componentHeight }}>
      <Card
        title={
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={handleCancel}
              style={{ padding: '4px 8px' }}
            >
              {i18nText('app.administration.rolemanagement.roleform.ca88ef30')}
            </Button>
            <span>{getPageTitle()}</span>
          </div>
        }
        style={{ height: componentHeight }}
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
              ? i18nText('app.administration.rolemanagement.roleform.ec6a795a')
              : i18nText('app.administration.rolemanagement.roleform.e6712707')
          }
          cancelText={i18nText(
            'app.administration.rolemanagement.roleform.b2ad9443',
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

export default RoleForm;
