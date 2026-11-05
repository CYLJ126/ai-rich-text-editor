import React from 'react';
import {Flex, Typography} from 'antd';

export default function ConfigurationTarget({name, scope, purpose}: {
  name?: string;
  scope?: { tenantId: string; workspaceId?: string };
  purpose: string;
}) {
  return <Flex vertical gap={4} aria-label="当前配置目标">
    <Typography.Text strong>正在配置：{name || '尚未选择配置目标'}</Typography.Text>
    <Typography.Text type="secondary">{purpose} · 选择配置对象仅影响本页</Typography.Text>
    {scope && <Typography.Text
      type="secondary">租户：{scope.tenantId}{scope.workspaceId ? ` · 空间：${scope.workspaceId}` : ''}</Typography.Text>}
  </Flex>;
}
