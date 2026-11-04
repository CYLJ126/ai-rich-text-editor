import React from 'react';
import {Alert, Button, Dropdown, Flex, Tooltip} from 'antd';
import {MyDynamicIcon as DynamicIcon} from '@/components/DynamicIcon';
import {sameScope} from '@/features/ainew/storage';
import type {WorkspaceScope} from '@/services/platform/workspace';
import {protectWorkspaceChange, useWorkspaceDirectory} from './useWorkspace';

/** 只负责空间选择；目录读取与切换保护复用账号级状态，管理功能由独立配置页承担。 */
export default function WorkspaceQuickSelect({userId, scope, name, onConnect, onPlatformConfig, open, onOpenChange}: {
  userId: string; scope?: WorkspaceScope; name?: string;
  onConnect?: () => void; onPlatformConfig?: () => void;
  open?: boolean; onOpenChange?: (open: boolean) => void;
}) {
  const directory = useWorkspaceDirectory(userId);
  const currentName = name ?? directory.current?.name ?? '选择工作空间';
  const notice = directory.error != null ? directory.error instanceof Error ? directory.error.message : '空间读取或切换失败，请重新读取后再选择。'
    : directory.warning;
  return <Flex vertical gap={4} style={{minWidth: 0, maxWidth: '100%'}}>
    <Dropdown open={open} onOpenChange={onOpenChange} trigger={['click']} menu={{
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
        ...(directory.error != null || directory.cursorExpired ? [{
          key: 'reload',
          label: '重新读取工作空间',
          disabled: directory.loading
        }] : []),
        ...(onConnect || onPlatformConfig ? [{type: 'divider' as const}] : []),
        ...(onConnect ? [{key: 'connect', label: '连接已有 AI 工作空间'}] : []),
        ...(onPlatformConfig ? [{key: 'platform', label: '平台 AI 配置'}] : []),
      ],
      onClick: ({key}) => {
        onOpenChange?.(false);
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
        if (key === 'reload') {
          void directory.load();
          return;
        }
        const target = directory.records.find(item => `space:${JSON.stringify(item.scope)}` === key);
        if (target) protectWorkspaceChange(userId, target.scope, () => {
          void directory.select(target.scope);
        });
      },
    }}>
      <Tooltip title={currentName}>
        <Button className="ainew-workspace-select" aria-label="选择工作空间" loading={directory.selecting}>
          <span>{currentName}</span><DynamicIcon iconName="DownOutlined" size={12}/>
        </Button>
      </Tooltip>
    </Dropdown>
    {notice && <Alert type="warning" title={notice}/>}
  </Flex>;
}
