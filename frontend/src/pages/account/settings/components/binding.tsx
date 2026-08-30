import {i18nText} from '@/utils/i18n';
import {AlipayOutlined, DingdingOutlined, TaobaoOutlined,} from '@ant-design/icons';
import {Button, List} from 'antd';
import React from 'react';

const bindingData = [
  {
    title: i18nText("app.account.components.binding.bdf50ec9"),
    description: i18nText("app.account.components.binding.49b79681"),
    actions: [
      <Button key="Bind" type="link">
        {i18nText("app.account.components.binding.1f42edd3")}
      </Button>,
    ],
    avatar: <TaobaoOutlined className="taobao"/>,
  },
  {
    title: i18nText("app.account.components.binding.e992c0d8"),
    description: i18nText("app.account.components.binding.690c91e8"),
    actions: [
      <Button key="Bind" type="link">
        {i18nText("app.account.components.binding.1f42edd3")}
      </Button>,
    ],
    avatar: <AlipayOutlined className="alipay"/>,
  },
  {
    title: i18nText("app.account.components.binding.74b81b21"),
    description: i18nText("app.account.components.binding.18193cfa"),
    actions: [
      <Button key="Bind" type="link">
        {i18nText("app.account.components.binding.1f42edd3")}
      </Button>,
    ],
    avatar: <DingdingOutlined className="dingding"/>,
  },
];

const BindingView: React.FC = () => {
  return (
    <List
      itemLayout="horizontal"
      dataSource={bindingData}
      renderItem={(item) => (
        <List.Item actions={item.actions}>
          <List.Item.Meta
            avatar={item.avatar}
            title={item.title}
            description={item.description}
          />
        </List.Item>
      )}
    />
  );
};

export default BindingView;
