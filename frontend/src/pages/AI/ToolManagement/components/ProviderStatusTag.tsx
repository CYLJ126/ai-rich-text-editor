import {Tag} from 'antd';
import React from 'react';

export default function ProviderStatusTag({status}: { status: string }) {
  const presentation =
    status === 'enabled'
      ? {color: 'success', label: '已启用'}
      : status === 'disabled'
        ? {color: 'error', label: '已禁用'}
        : {color: 'default', label: '未同步'};
  return <Tag color={presentation.color}>{presentation.label}</Tag>;
}
