import {i18nText} from '@/utils/i18n';
import {List, Switch} from 'antd';
import React from 'react';

type Unpacked<T> = T extends (infer U)[] ? U : T;

const notificationAction = (key: string) => (
  <Switch
    key={key}
    checkedChildren={i18nText("app.account.components.notification.9bc09d9a")}
    unCheckedChildren={i18nText("app.account.components.notification.dc1dd4e1")}
    defaultChecked
  />
);

const notificationData = [
  {
    key: 'user-message',
    title: i18nText("app.account.components.notification.74cf0b34"),
    description: i18nText("app.account.components.notification.ec0409fb"),
    actions: [notificationAction('user-message-switch')],
  },
  {
    key: 'system-message',
    title: i18nText("app.account.components.notification.ce412638"),
    description: i18nText("app.account.components.notification.1e8bd9cd"),
    actions: [notificationAction('system-message-switch')],
  },
  {
    key: 'todo-task',
    title: i18nText("app.account.components.notification.4f599a47"),
    description: i18nText("app.account.components.notification.a643a86c"),
    actions: [notificationAction('todo-task-switch')],
  },
];

const NotificationView: React.FC = () => {
  const data = notificationData;
  return (
    <List<Unpacked<typeof data>>
      rowKey="key"
      itemLayout="horizontal"
      dataSource={data}
      renderItem={(item) => (
        <List.Item actions={item.actions}>
          <List.Item.Meta title={item.title} description={item.description} />
        </List.Item>
      )}
    />
  );
};

export default NotificationView;
