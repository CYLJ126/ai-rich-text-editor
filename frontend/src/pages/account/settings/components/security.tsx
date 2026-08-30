import { i18nText } from '@/utils/i18n';
import { List } from 'antd';
import React, { useState } from 'react';
import { useModel } from 'umi';
import ChangePasswordModal from './ChangePasswordModal';

type Unpacked<T> = T extends (infer U)[] ? U : T;

const passwordStrength = {
  strong: (
    <span className="strong">
      {i18nText('app.account.components.security.dca27984')}
    </span>
  ),
  medium: (
    <span className="medium">
      {i18nText('app.account.components.security.4f80067a')}
    </span>
  ),
  weak: (
    <span className="weak">
      {i18nText('app.account.components.security.67bef1fd')}
    </span>
  ),
};

const SecurityView: React.FC = () => {
  const { initialState } = useModel('@@initialState');
  const { currentUser } = initialState ?? {};

  // 控制修改密码弹窗
  const [pwdModalOpen, setPwdModalOpen] = useState(false);

  const getData = () => [
    {
      title: i18nText('app.account.components.security.b0edbb49'),
      description: (
        <>
          {i18nText('app.account.components.security.daae1714')}
          {passwordStrength.strong}
        </>
      ),
      actions: [
        <a
          key="Modify"
          onClick={(e) => {
            e.preventDefault();
            setPwdModalOpen(true);
          }}
        >
          {i18nText('app.account.components.security.10ec7e3e')}
        </a>,
      ],
    },
    {
      title: i18nText('app.account.components.security.fafa5019'),
      description: i18nText('app.account.components.security.686c37e1', {
        value0: currentUser?.mobile || i18nText('app.common.notBound'),
      }),
      actions: [
        <a key="Modify" href="#">
          {i18nText('app.account.components.security.10ec7e3e')}
        </a>,
      ],
    },
    {
      title: i18nText('app.account.components.security.2d675b1b'),
      description: i18nText('app.account.components.security.6e3aa914'),
      actions: [
        <a key="Set" href="#">
          {i18nText('app.account.components.security.1c202130')}
        </a>,
      ],
    },
    {
      title: i18nText('app.account.components.security.ed29990a'),
      description: i18nText('app.account.components.security.c16120ef', {
        value0: currentUser?.email || i18nText('app.common.notBound'),
      }),
      actions: [
        <a key="Modify" href="#">
          {i18nText('app.account.components.security.10ec7e3e')}
        </a>,
      ],
    },
    {
      title: i18nText('app.account.components.security.5242ba16'),
      description: i18nText('app.account.components.security.f38e46d0'),
      actions: [
        <a key="bind" href="#">
          {i18nText('app.account.components.security.4f263cce')}
        </a>,
      ],
    },
  ];

  const data = getData();

  return (
    <>
      <List<Unpacked<typeof data>>
        itemLayout="horizontal"
        dataSource={data}
        renderItem={(item) => (
          <List.Item actions={item.actions}>
            <List.Item.Meta title={item.title} description={item.description} />
          </List.Item>
        )}
      />

      <ChangePasswordModal
        open={pwdModalOpen}
        onCancel={() => setPwdModalOpen(false)}
      />
    </>
  );
};

export default SecurityView;
