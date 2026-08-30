import {List} from 'antd';
import React, {useState} from 'react';
import {useModel} from 'umi';
import ChangePasswordModal from "./ChangePasswordModal";

type Unpacked<T> = T extends (infer U)[] ? U : T;

const passwordStrength = {
  strong: <span className="strong">强</span>,
  medium: <span className="medium">中</span>,
  weak: <span className="weak">弱 Weak</span>,
};

const SecurityView: React.FC = () => {
  const {initialState} = useModel('@@initialState');
  const {currentUser} = initialState ?? {};

  // 控制修改密码弹窗
  const [pwdModalOpen, setPwdModalOpen] = useState(false);

  const getData = () => [
    {
      title: '账户密码',
      description: (
        <>
          当前密码强度：
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
          修改
        </a>,
      ],
    },
    {
      title: '密保手机',
      description: `已绑定手机：${currentUser?.mobile || '未绑定'}`,
      actions: [
        <a key="Modify" href="#">
          修改
        </a>,
      ],
    },
    {
      title: '密保问题',
      description: '未设置密保问题，密保问题可有效保护账户安全【未实现】',
      actions: [
        <a key="Set" href="#">
          设置
        </a>,
      ],
    },
    {
      title: '备用邮箱',
      description: `已绑定邮箱：${currentUser?.email || '未绑定'}`,
      actions: [
        <a key="Modify" href="#">
          修改
        </a>,
      ],
    },
    {
      title: 'MFA 设备',
      description: '未绑定 MFA 设备，绑定后，可以进行二次确认【未实现】',
      actions: [
        <a key="bind" href="#">
          绑定
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
            <List.Item.Meta title={item.title} description={item.description}/>
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
