import {LogoutOutlined, SettingOutlined, SkinOutlined, UserDeleteOutlined,} from '@ant-design/icons';
import {history, useModel} from '@umijs/max';
import type {MenuProps} from 'antd';
import {Spin} from 'antd';
import React, {startTransition, useState} from 'react';
import {logout} from '@/services/ant-design-pro/base';
import HeaderDropdown from '../HeaderDropdown';
import ChangePasswordModal from "@/pages/account/settings/components/ChangePasswordModal";

type GlobalHeaderRightProps = {
  children?: React.ReactNode;
};

export const AvatarDropdown: React.FC<GlobalHeaderRightProps> = ({
                                                                   children,
                                                                 }) => {
  const loginOut = async () => {
    await logout();
    localStorage.removeItem('user_token');
    const {search, pathname} = window.location;
    const urlParams = new URL(window.location.href).searchParams;
    const searchParams = new URLSearchParams({
      redirect: pathname + search,
    });
    const redirect = urlParams.get('redirect');
    if (window.location.pathname !== '/user/login' && !redirect) {
      history.replace({
        pathname: '/user/login',
        search: searchParams.toString(),
      });
    }
  };
  const {initialState, setInitialState} = useModel('@@initialState');
  // 控制修改密码弹窗
  const [pwdModalOpen, setPwdModalOpen] = useState(false);

  const onMenuClick: MenuProps['onClick'] = (event) => {
    const {key} = event;
    if (key === 'logout') {
      startTransition(() => {
        setInitialState((s) => ({...s, currentUser: undefined}));
      });
      loginOut().then();
      return;
    }
    if (key === 'changePassword') {
      setPwdModalOpen(true)
      return;
    }
    if (key === 'theme') {
      setInitialState((s) => ({...s, settingDrawerOpen: true}));
      return;
    }
    history.push(`/account/${key}`);
  };

  if (!initialState) {
    return <Spin size="small"/>;
  }

  const {currentUser} = initialState;

  if (!currentUser) {
    return <Spin size="small"/>;
  }

  const menuItems: MenuProps['items'] = [
    {
      key: 'settings',
      icon: <SettingOutlined/>,
      label: '个人设置',
    },
    {
      key: 'theme',
      icon: <SkinOutlined/>,
      label: '主题设置',
    },
    {
      type: 'divider' as const,
    },
    {
      key: 'changePassword',
      icon: <UserDeleteOutlined/>,
      label: '修改密码',
    },
    {
      key: 'logout',
      icon: <LogoutOutlined/>,
      label: '退出登录',
    },
  ];

  return (
    <>
      <HeaderDropdown
        placement="bottomRight"
        menu={{
          selectedKeys: [],
          onClick: onMenuClick,
          items: menuItems,
        }}
        arrow
      >
        {children}
      </HeaderDropdown>
      <ChangePasswordModal
        open={pwdModalOpen}
        onCancel={() => setPwdModalOpen(false)}
      />
    </>
  );
};
