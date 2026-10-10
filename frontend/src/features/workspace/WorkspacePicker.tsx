import React, {useState} from 'react';
import {Alert, Button, Drawer, Empty, Input, Modal, Pagination, Space, Tag} from 'antd';
import {AiApiError} from '@/services/arte-ai';
import {sameScope} from '@/features/ainew/storage';
import {useWorkspaceDirectory} from './useWorkspace';
import type {WorkspaceScope} from '@/services/platform/workspace';

export default function WorkspacePicker({userId, beforeSelect}: {
  userId: string;
  beforeSelect?: (scope: WorkspaceScope, proceed: () => void) => void
}) {
  const directory = useWorkspaceDirectory(userId);
  const [open, setOpen] = useState(false);
  const [filter, setFilter] = useState('');
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const role = ['负责人', '管理员', '成员', '访客'];
  const state = ['正常', '已归档', '关闭处理中', '已删除'];
  const error = directory.error instanceof Error ? directory.error.message : '空间读取失败，请重试。';
  return <>
    <Button onClick={() => setOpen(true)}
            aria-label="选择工作空间">{directory.current?.name ?? '选择工作空间'} ▾</Button>
    <Drawer title="工作空间" open={open} onClose={() => setOpen(false)}>
      <Space orientation="vertical" style={{width: '100%'}}>
        {directory.warning && <Alert type="warning" title={directory.warning}/>}
        {directory.error != null && <Alert type="error" title={error}/>}
        <Button loading={directory.loading} onClick={() => void directory.load(directory.page)}>刷新空间目录</Button>
        {!directory.preference && <Button loading={directory.saving}
                                          disabled={!!directory.pending || (directory.error instanceof AiApiError && [401, 403].includes(directory.error.httpStatus))}
                                          onClick={() => void directory.initialize()}>初始化个人默认空间</Button>}
        {directory.pending &&
          <Alert type="warning" title="空间操作结果待确认" description="重试保持原空间、参数和操作键。"
                 action={<Button loading={directory.saving}
                                 onClick={() => void directory.retry()}>重试确认原操作</Button>}/>}
        <Input aria-label="筛选本页空间" placeholder="筛选本页空间" value={filter}
               onChange={event => setFilter(event.target.value)}/>
        <ul className="m-0 list-none space-y-3 p-0">
          {directory.records.filter(item => item.name.includes(filter)).map(item => <li key={JSON.stringify(item.scope)}
                                                                                        className="rounded border border-solid border-[var(--ant-color-border)] p-3">
            <Button type={sameScope(item.scope, directory.current?.scope) ? 'primary' : 'default'} onClick={() => {
              const proceed = () => {
                void directory.select(item.scope);
                setOpen(false);
              };
              if (beforeSelect) beforeSelect(item.scope, proceed); else proceed();
            }}>{item.name}</Button>
            <p><Tag>{role[item.currentRole]}</Tag><Tag>{state[item.state]}</Tag>{item.defaultWorkspace &&
              <Tag>个人默认空间</Tag>}</p>
            <Button size="small" disabled={!directory.preference || directory.saving || !!directory.pending}
                    onClick={() => void directory.updatePreference({favorites: directory.favorite(item.scope) ? directory.preference!.favorites.filter(scope => !sameScope(scope, item.scope)) : [...directory.preference!.favorites, item.scope]})}>{directory.favorite(item.scope) ? '取消收藏' : '收藏'}</Button>
            <Button size="small" disabled={!directory.preference || directory.saving || !!directory.pending}
                    onClick={() => void directory.updatePreference({startupScope: item.scope})}>{sameScope(item.scope, directory.preference?.startupScope) ? '已设为启动空间' : '设为启动空间'}</Button>
          </li>)}
        </ul>
        {!directory.loading && !directory.error && !directory.records.filter(item => item.name.includes(filter)).length &&
          <Empty description="当前页没有匹配空间，可切换页面。"/>}
        <Pagination current={directory.page} total={directory.total} pageSize={20} showSizeChanger={false}
                    onChange={page => void directory.load(page)}/>
        {directory.canCreate && <Button disabled={directory.saving || !!directory.pending}
                                        onClick={() => setCreating(true)}>新建工作空间</Button>}
        <p>邀请、成员管理及空间关闭将在对应能力接入后开放。模型和预算在进入聊天后检查。</p>
      </Space>
    </Drawer>
    <Modal title="新建工作空间" open={creating} onCancel={() => setCreating(false)} confirmLoading={directory.saving}
           okButtonProps={{disabled: !name.trim() || !!directory.pending}} onOk={() => {
      const proceed = () => {
        void directory.create(name.trim(), description.trim());
        setCreating(false);
      };
      if (beforeSelect && directory.current) beforeSelect(directory.current.scope, proceed); else proceed();
    }}>
      <p>保存到当前空间所属租户，不需要先配置模型或预算。</p>
      <Input aria-label="空间名称" placeholder="空间名称" maxLength={100} value={name}
             onChange={event => setName(event.target.value)}/>
      <Input.TextArea aria-label="空间描述" placeholder="描述（可选）" maxLength={1000} value={description}
                      onChange={event => setDescription(event.target.value)}/>
    </Modal>
  </>;
}
