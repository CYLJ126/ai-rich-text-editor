import { i18nText } from '@/utils/i18n';
import React, { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Button, Card, message, Spin } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import DynamicForm from '@/components/DynamicForm';
import { FormFieldConfig } from '@/components';
import {
  addMenu,
  getMenuByCode,
  updateMenu,
} from '@/services/ant-design-pro/rbac';
import {useComponentHeight} from '@/utils/useDynamicHeight';

const MenuForm: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const mode = (searchParams.get('mode') as 'create' | 'edit' | 'view') || 'create';
  const menuCode = searchParams.get('menuCode');
  const id = searchParams.get('id');
  const componentHeight = useComponentHeight();

  const [loading, setLoading] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [initialValues, setInitialValues] = useState<Record<string, any>>({});
  const [historyData, setHistoryData] = useState<any>(null);

  // 表单字段配置
  const fields: FormFieldConfig[] = [
    {
      fieldName: 'menuCode',
      fieldType: 'input',
      label: i18nText('app.administration.menumanagement.menuform.3d152d3b'),
      required: true,
      placeholder: i18nText(
        'app.administration.menumanagement.menuform.6d49e858',
      ),
      rules: [
        {
          min: 2,
          max: 32,
          message: i18nText(
            'app.administration.menumanagement.menuform.06cde785',
          ),
        },
        {
          pattern: /^[a-zA-Z0-9_]+$/,
          message: i18nText(
            'app.administration.menumanagement.menuform.4910a4fb',
          ),
        },
      ],
      canEdit: mode === 'create', // 编辑时不允许修改用户名
    },
    {
      fieldName: 'menuName',
      fieldType: 'input',
      label: i18nText('app.administration.menumanagement.menuform.732e088b'),
      required: true,
      placeholder: i18nText(
        'app.administration.menumanagement.menuform.f69c11b9',
      ),
      rules: [
        {
          min: 2,
          max: 32,
          message: i18nText(
            'app.administration.menumanagement.menuform.f87bdbda',
          ),
        },
        {
          pattern: /^[a-zA-Z0-9_ \u4e00-\u9fa5]+$/,
          message: i18nText(
            'app.administration.menumanagement.menuform.997708e7',
          ),
        },
      ],
      canEdit: mode === 'create', // 编辑时不允许修改用户名
    },
    {
      fieldName: 'icon',
      fieldType: 'select',
      label: i18nText('app.administration.menumanagement.menuform.30879b5a'),
      required: false,
      placeholder: i18nText(
        'app.administration.menumanagement.menuform.e854750c',
      ),
      options: [
        { label: 'smile', value: 'smile' },
        { label: 'crown', value: 'crown' },
        { label: 'userOutlined', value: 'userOutlined' },
        { label: 'tool', value: 'tool' },
        { label: 'tag', value: 'tag' },
        { label: 'loading', value: 'loading' },
        { label: 'book', value: 'book' },
        { label: 'administration', value: 'administration' },
      ],
    },
    {
      fieldName: 'menuUrl',
      fieldType: 'input',
      label: i18nText('app.administration.menumanagement.menuform.962a70f1'),
      required: true,
      placeholder: i18nText(
        'app.administration.menumanagement.menuform.424d2893',
      ),
    },
    {
      fieldName: 'status',
      fieldType: 'select',
      label: i18nText('app.administration.menumanagement.menuform.dfaf5270'),
      required: true,
      options: [
        {
          label: i18nText(
            'app.administration.menumanagement.menuform.0e1668b0',
          ),
          value: 0,
        },
        {
          label: i18nText(
            'app.administration.menumanagement.menuform.3ef9e209',
          ),
          value: 1,
        },
        {
          label: i18nText(
            'app.administration.menumanagement.menuform.bbccf323',
          ),
          value: 2,
        },
        {
          label: i18nText(
            'app.administration.menumanagement.menuform.bbf306c6',
          ),
          value: 3,
        },
      ],
      defaultValue: mode === 'create' ? 0 : undefined,
    },
    {
      fieldName: 'fatherId',
      fieldType: 'select',
      label: i18nText('app.administration.menumanagement.menuform.8fde0775'),
      placeholder: i18nText(
        'app.administration.menumanagement.menuform.373fe867',
      ),
      clearable: true,
    },
    {
      fieldName: 'orderId',
      fieldType: 'input',
      label: i18nText('app.administration.menumanagement.menuform.32df64b0'),
      placeholder: i18nText(
        'app.administration.menumanagement.menuform.8e8aed74',
      ),
    },
    {
      fieldName: 'showFlag',
      fieldType: 'radio',
      label: i18nText('app.administration.menumanagement.menuform.f4e143e2'),
      options: [
        {
          label: i18nText(
            'app.administration.menumanagement.menuform.81b2d59a',
          ),
          value: 1,
        },
        {
          label: i18nText(
            'app.administration.menumanagement.menuform.d4a2ba41',
          ),
          value: 0,
        },
      ],
      defaultValue: 0,
    },
    {
      fieldName: 'description',
      fieldType: 'textarea',
      label: i18nText('app.administration.menumanagement.menuform.376479f1'),
      placeholder: i18nText(
        'app.administration.menumanagement.menuform.9438d897',
      ),
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
      const { ...submitData } = values;

      let response;
      if (mode === 'create') {
        response = await addMenu(submitData);
      } else {
        response = await updateMenu({
          id: id,
          menuCode: menuCode,
          ...submitData,
        });
      }

      if (response) {
        message.success(
          i18nText('app.administration.menumanagement.menuform.f9aff1e3', {
            value0: i18nText(
              mode === 'create'
                ? 'app.administration.menumanagement.menuform.6d4337cd'
                : 'app.administration.menumanagement.menuform.b6c1c726',
            ),
          }),
        );
        navigate('/Administration/MenuManagement');
      } else {
        message.error(
          i18nText('app.administration.menumanagement.menuform.5ff84228', {
            value0: i18nText(
              mode === 'create'
                ? 'app.administration.menumanagement.menuform.6d4337cd'
                : 'app.administration.menumanagement.menuform.b6c1c726',
            ),
          }),
        ).then();
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
        return i18nText('app.administration.menumanagement.menuform.6e5fd88c');
      case 'edit':
        return i18nText('app.administration.menumanagement.menuform.e077f78d');
      case 'view':
        return i18nText('app.administration.menumanagement.menuform.ba308cbd');
      default:
        return i18nText('app.administration.menumanagement.menuform.ab2deb94');
    }
  };

  if (loading) {
    return (
      <div style={{ padding: '50px 0', textAlign: 'center' }}>
        <Spin
          size="large"
          description={i18nText('app.administration.menumanagement.menuform.f5bead53')}
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
              {i18nText('app.administration.menumanagement.menuform.e72fd20f')}
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
              ? i18nText('app.administration.menumanagement.menuform.6d4337cd')
              : i18nText('app.administration.menumanagement.menuform.b6c1c726')
          }
          cancelText={i18nText(
            'app.administration.menumanagement.menuform.7f295b47',
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

export default MenuForm;
