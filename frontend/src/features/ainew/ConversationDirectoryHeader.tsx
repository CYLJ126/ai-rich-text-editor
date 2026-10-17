import React, {useState} from 'react';
import {Alert, Button, Dropdown, Tooltip} from 'antd';
import {MyDynamicIcon as DynamicIcon} from '@/components/DynamicIcon';
import WorkspaceQuickSelect from '@/features/workspace/WorkspaceQuickSelect';
import type {AiScope} from '@/services/arte-ai';

export default function ConversationDirectoryHeader({
                                                      userId,
                                                      scope,
                                                      name,
                                                      writable,
                                                      creating,
                                                      pendingCreate,
                                                      refreshing,
                                                      searching = false,
                                                      onRefresh,
                                                      onCreate,
                                                      onCollapse,
                                                      onManage,
                                                      onConnect,
                                                      onPlatformConfig
                                                    }: {
  userId: string; scope: AiScope; name: string; writable: boolean; creating: boolean; pendingCreate: boolean;
  refreshing: boolean; onRefresh: () => void;
  searching?: boolean;
  onCreate: () => void; onCollapse: () => void; onManage: () => void;
  onConnect: () => void; onPlatformConfig: () => void;
}) {
  const [notice, setNotice] = useState('');
  const createLabel = pendingCreate ? '确认原创建操作' : '新建会话';
  return <>
    <header className="ainew-directory-header">
      <WorkspaceQuickSelect userId={userId} scope={scope} name={name} onManage={onManage}
                            onConnect={onConnect} onPlatformConfig={onPlatformConfig}/>
      <div className="ainew-directory-tools">
        <Tooltip title={searching ? '按 Enter 重新搜索' : '刷新会话列表'}>
          <Button type="text" size="small" aria-label="刷新会话列表" loading={refreshing} disabled={searching}
                  icon={<DynamicIcon iconName="ReloadOutlined" size={18} color="var(--ant-color-text-tertiary)"/>}
                  onClick={onRefresh}/>
        </Tooltip>
        <Tooltip title={createLabel}>
          <Button type="text" size="small" aria-label={createLabel} disabled={!writable || creating}
                  loading={creating}
                  icon={<DynamicIcon iconName="MessageFilled" size={18} color="var(--ant-color-text-tertiary)"/>}
                  onClick={onCreate}/>
        </Tooltip>
        <Tooltip title="临时会话（暂未开放）">
          <Button type="text" size="small" aria-label="临时会话"
                  icon={<DynamicIcon iconName="MessageOutlined" size={18} color="var(--ant-color-text-tertiary)"/>}
                  onClick={() => setNotice('临时会话暂未开放，当前聊天保持不变。')}/>
        </Tooltip>
        <Dropdown trigger={['hover', 'click']} menu={{
          items: [
            {key: 'date', label: '按日期'}, {key: 'type', label: '按类型'}, {key: 'group', label: '按归属组'},
          ], onClick: () => setNotice('分组暂未开放，当前排列保持不变。')
        }}>
          <Tooltip title="分组方式">
            <Button type="text" size="small" aria-label="分组方式"
                    icon={<DynamicIcon iconName="GroupOutlined" size={18} color="var(--ant-color-text-tertiary)"/>}/>
          </Tooltip>
        </Dropdown>
        <Tooltip title="收起会话列表">
          <Button type="text" size="small" aria-label="收起会话列表"
                  icon={<DynamicIcon iconName="MenuFoldOutlined" size={18} color="var(--ant-color-text-tertiary)"/>}
                  onClick={onCollapse}/>
        </Tooltip>
      </div>
    </header>
    {notice && <Alert type="info" title={notice} closable onClose={() => setNotice('')}/>}
  </>;
}
