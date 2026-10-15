import React, {useEffect, useRef} from 'react';
import {Button, Dropdown, Tooltip} from 'antd';
import {MyDynamicIcon as DynamicIcon} from '@/components/DynamicIcon';
import {sameScope} from '@/features/ainew/storage';
import type {WorkspaceScope} from '@/services/platform/workspace';
import {protectWorkspaceChange, useWorkspaceDirectory} from './useWorkspace';

/** 快捷下拉与工作空间管理复用同一账号目录及切换保护，不维护第二份空间状态。 */
export default function WorkspaceQuickSelect({userId, scope, name, onManage, onConnect, onPlatformConfig}: {
  userId: string; scope?: WorkspaceScope; name?: string; onManage: () => void;
  onConnect?: () => void; onPlatformConfig?: () => void;
}) {
  const directory = useWorkspaceDirectory(userId);
  const selection = useRef(0);
  useEffect(() => () => {
    selection.current++;
  }, []);
  const currentName = name ?? directory.current?.name ?? '选择工作空间';
  return <Dropdown trigger={['click']} menu={{
    selectedKeys: directory.records.filter(item => sameScope(item.scope, scope ?? directory.current?.scope)).map(item => `space:${JSON.stringify(item.scope)}`),
    items: [
      ...directory.records.map(item => ({
        key: `space:${JSON.stringify(item.scope)}`, label: item.name,
        disabled: directory.selecting || directory.unsafeToLeave || directory.recoveryBlocked
      })),
      ...(!directory.records.length ? [{
        key: 'empty',
        label: directory.loading ? '正在读取空间…' : '暂无可选空间',
        disabled: true
      }] : []),
      ...(!directory.exhausted && directory.nextCursor ? [{
        key: 'more',
        label: '加载更多工作空间',
        disabled: directory.loading
      }] : []),
      {type: 'divider' as const}, {key: 'manage', label: '管理工作空间'},
      ...(onConnect ? [{key: 'connect', label: '连接已有 AI 工作空间'}] : []),
      ...(onPlatformConfig ? [{key: 'platform', label: '平台 AI 配置'}] : []),
    ],
    onClick: ({key}) => {
      if (key === 'manage') {
        onManage();
        return;
      }
      if (key === 'connect') {
        onConnect?.();
        return;
      }
      if (key === 'platform') {
        onPlatformConfig?.();
        return;
      }
      if (key === 'more') {
        void directory.loadMore();
        return;
      }
      const target = directory.records.find(item => `space:${JSON.stringify(item.scope)}` === key);
      if (target) protectWorkspaceChange(userId, target.scope, () => {
        const attempt = ++selection.current;
        void directory.select(target.scope).then(selected => {
          if (!selected && attempt === selection.current) onManage();
        });
      });
    },
  }}>
    <Tooltip title={currentName}>
      <Button className="ainew-workspace-select" aria-label="选择工作空间" loading={directory.selecting}>
        <span>{currentName}</span><DynamicIcon iconName="DownOutlined" size={12}/>
      </Button>
    </Tooltip>
  </Dropdown>;
}
