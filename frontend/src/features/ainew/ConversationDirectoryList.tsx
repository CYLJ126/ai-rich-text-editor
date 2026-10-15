import React, {memo, useEffect, useLayoutEffect, useRef, useState} from 'react';
import {Alert, Button, Dropdown, Empty, Input} from 'antd';
import {MyDynamicIcon as DynamicIcon} from '@/components/DynamicIcon';
import type {ConversationListItem} from '@/services/arte-ai';
import type {ConversationDirectory, DirectoryAnchor, TitleEditor} from './useConversationDirectory';

interface RowActions {
  open: (id: string) => void;
  edit: (id: string) => void;
  pin: (item: ConversationListItem) => void;
  change: (text: string) => void;
  save: (explicit?: boolean) => void;
  cancel: () => void;
}

function dateLabel(value: string | null) {
  if (!value || !Number.isFinite(Date.parse(value))) return '—';
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Shanghai',
    year: '2-digit',
    month: '2-digit'
  }).formatToParts(new Date(value));
  return `${parts.find(p => p.type === 'year')?.value}-${parts.find(p => p.type === 'month')?.value}`;
}

const DirectoryRow = memo(function DirectoryRow({
                                                  item,
                                                  selected,
                                                  editor,
                                                  actions,
                                                  writable,
                                                  pinFull,
                                                  busy,
                                                  menuOpen,
                                                  onMenu
                                                }: {
  item: ConversationListItem; selected: boolean; editor: TitleEditor | null;
  actions: React.RefObject<RowActions>; writable: boolean; pinFull: boolean; busy: boolean;
  menuOpen: boolean; onMenu: (id: string | null) => void;
}) {
  const input = useRef<import('antd').InputRef>(null);
  const composing = useRef(false);
  useEffect(() => {
    if (editor && editor.status !== 'saving') input.current?.focus({preventScroll: true});
  }, [!!editor, editor?.status, editor?.error]);
  return <li className={`ainew-directory-row${selected ? ' is-selected' : ''}`}
             data-conversation-id={item.conversationId} data-normal-id={item.pinned ? undefined : item.conversationId}
             aria-current={selected ? 'true' : undefined}
             onClick={() => actions.current.open(item.conversationId)}>
    <Button type="text" className="ainew-directory-avatar" aria-label={`打开会话：${item.title}`}
            icon={<DynamicIcon iconName="CommentOutlined" size={22}/>}
            onClick={event => {
              event.stopPropagation();
              actions.current.open(item.conversationId);
            }}/>
    <div className="ainew-directory-text">
      {editor ? <div className="ainew-directory-edit" onClick={event => event.stopPropagation()}>
        <Input ref={input} aria-label={`会话名称：${item.conversationId}`} autoFocus
               value={editor.draft} maxLength={256}
               readOnly={editor.status === 'saving' || editor.status === 'uncertain'}
               status={editor.error ? 'error' : undefined}
               onChange={event => actions.current.change(event.target.value)}
               onCompositionStart={() => {
                 composing.current = true;
               }} onCompositionEnd={() => {
          composing.current = false;
        }}
               onKeyDown={event => {
                 event.stopPropagation();
                 if (composing.current || event.nativeEvent.isComposing || event.keyCode === 229) return;
                 if (event.key === 'Escape') {
                   event.preventDefault();
                   actions.current.cancel();
                 }
                 if (event.key === 'Enter') {
                   event.preventDefault();
                   actions.current.save(true);
                 }
               }} onBlur={() => actions.current.save(false)}/>
        {editor.error && <div className="ainew-directory-edit-error" role="alert">{editor.error}
          {editor.ready && <div>当前名称：{editor.target.title}</div>}</div>}
        {editor.status === 'saving' ? <span role="status">正在保存…</span> : editor.status !== 'uncertain' &&
          <div className="ainew-directory-edit-actions">
            <Button size="small" onMouseDown={event => event.preventDefault()}
                    onClick={() => actions.current.save(true)}>
              {editor.status === 'error' ? '重新保存' : '保存'}</Button>
            <Button size="small" onMouseDown={event => event.preventDefault()}
                    onClick={() => actions.current.cancel()}>取消</Button>
          </div>}
      </div> : <button type="button" className="ainew-directory-title" disabled={!writable}
                       aria-label={`修改会话名称：${item.title}`}
                       onClick={event => {
                         event.stopPropagation();
                         actions.current.edit(item.conversationId);
                       }}>{item.title}</button>}
      {item.previewText !== item.title && <p
        className="ainew-directory-preview">{item.previewText || (item.userMessageCount === 0 ? '暂无消息……' : '—')}</p>}
    </div>
    <div className="ainew-directory-facts">
      <div className={`ainew-directory-date${menuOpen ? ' is-open' : ''}`} onClick={event => event.stopPropagation()}>
        <time dateTime={item.lastMessageGeneratedAt ?? undefined}>{dateLabel(item.lastMessageGeneratedAt)}</time>
        <Dropdown trigger={['click']} open={menuOpen} onOpenChange={open => onMenu(open ? item.conversationId : null)}
                  menu={{
                    items: [
                      {
                        key: 'pin', label: item.pinned ? '取消置顶' : pinFull ? '置顶（已达7个上限）' : '置顶',
                        disabled: !writable || busy || (!item.pinned && pinFull)
                      },
                      {key: 'group', label: '分组（暂未开放）', disabled: true},
                      {key: 'archive', label: '归档（暂未开放）', disabled: true},
                      {key: 'delete', label: '删除（暂未开放）', disabled: true},
                    ], onClick: ({key, domEvent}) => {
                      domEvent.stopPropagation();
                      onMenu(null);
                      if (key === 'pin') actions.current.pin(item);
                    }
                  }}>
          <Button type="text" size="small" aria-label={`会话操作：${item.title}`}
                  icon={<DynamicIcon iconName="MoreOutlined" size={18}/>}/>
        </Dropdown>
      </div>
      <span>{item.userMessageCount == null ? '—' : `${item.userMessageCount}轮`}</span>
    </div>
  </li>;
});

export default function ConversationDirectoryList({directory, writable, readBlocked, onOpen, onCreate}: {
  directory: ConversationDirectory; writable: boolean; readBlocked: boolean;
  onOpen: (id: string) => void; onCreate: () => void;
}) {
  const scroller = useRef<HTMLElement>(null);
  const latest = useRef(directory);
  latest.current = directory;
  const [search, setSearch] = useState('');
  const [menu, setMenu] = useState<string | null>(null);
  const onMenu = React.useCallback((id: string | null) => setMenu(id), []);
  const handlers: RowActions = {
    open: onOpen, edit: id => {
      void directory.beginEdit(id);
    }, pin: item => {
      void directory.setPinned(item);
    },
    change: directory.changeDraft, save: explicit => {
      void directory.finishEdit(explicit);
    }, cancel: directory.cancelEdit
  };
  const actions = useRef(handlers);
  actions.current = handlers;
  const programmatic = useRef(false);
  const intent = useRef(false);
  const previousTop = useRef(0);
  const findAnchor = React.useCallback((exclude?: string): DirectoryAnchor | null => {
    const root = scroller.current;
    if (!root) return null;
    const box = root.getBoundingClientRect();
    const row = Array.from(root.querySelectorAll<HTMLElement>('[data-normal-id]')).find(node => node.dataset.normalId !== exclude && node.getBoundingClientRect().bottom > box.top && node.getBoundingClientRect().top < box.bottom);
    const id = row?.dataset.normalId;
    return row && id ? {id, offset: row.getBoundingClientRect().top - box.top} : null;
  }, []);
  useLayoutEffect(() => directory.captureAnchor(findAnchor), [directory.captureAnchor, findAnchor]);
  useLayoutEffect(() => {
    const root = scroller.current;
    if (!root) return;
    programmatic.current = true;
    intent.current = false;
    if (directory.placement.top) root.scrollTop = 0;
    else if (directory.placement.anchor) {
      const anchor = directory.placement.anchor;
      const row = Array.from(root.querySelectorAll<HTMLElement>('[data-normal-id]')).find(node => node.dataset.normalId === anchor.id);
      if (row) root.scrollTop += row.getBoundingClientRect().top - root.getBoundingClientRect().top - anchor.offset;
    }
    previousTop.current = root.scrollTop;
    const frame = requestAnimationFrame(() => {
      programmatic.current = false;
    });
    return () => cancelAnimationFrame(frame);
  }, [directory.revision]);
  useEffect(() => {
    const root = scroller.current;
    if (!root) return;
    const fill = () => {
      const value = latest.current;
      if (root.clientHeight > 0 && root.scrollHeight <= root.clientHeight && value.hasMore
        && !value.listLoading && !value.loadingMore && !value.listError && !value.moreError && !value.paused) void value.loadMore();
    };
    const frame = requestAnimationFrame(fill);
    const observer = new ResizeObserver(fill);
    observer.observe(root);
    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
    };
  }, [directory.revision, directory.listLoading, directory.loadingMore, directory.paused, directory.moreError]);
  const renderRow = (item: ConversationListItem) => <DirectoryRow key={item.conversationId} item={item}
                                                                  selected={directory.selectedId === item.conversationId}
                                                                  editor={directory.editor?.target.conversationId === item.conversationId ? directory.editor : null}
                                                                  actions={actions}
                                                                  writable={writable && !readBlocked && !directory.listLoading}
                                                                  busy={directory.commandBusy || !!directory.pendingCommand || directory.listLoading}
                                                                  pinFull={directory.pinned.length >= 7}
                                                                  menuOpen={menu === item.conversationId}
                                                                  onMenu={onMenu}/>;
  return <aside className="ainew-sidebar ainew-directory" aria-label="会话列表">
    <Button block type="primary" disabled={readBlocked || !writable || directory.creating}
            loading={directory.creating}
            onClick={onCreate}>{directory.hasPendingCreate ? '确认原创建操作' : '新建会话'}</Button>
    <Input aria-label="搜索会话" placeholder="搜索（暂未开放）" value={search} disabled={readBlocked}
           prefix={<DynamicIcon iconName="SearchOutlined"/>} onChange={event => setSearch(event.target.value)}/>
    {directory.createError != null && <Alert type="error" title="创建结果未确认或被拒绝，请确认原创建操作。"/>}
    {directory.pendingCommand && <Alert type="warning"
                                        title={directory.pendingCommand.request.kind === 'rename' ? '名称保存结果待确认' : '置顶结果待确认'}
                                        action={<Button size="small" loading={directory.commandBusy}
                                                        onClick={() => void directory.confirmCommand()}>确认原操作</Button>}/>}
    {directory.commandError && !directory.editor && <Alert type="error" title={directory.commandError}/>}
    {directory.editor && !directory.records.some(item => item.conversationId === directory.editor?.target.conversationId) &&
      <section aria-label="恢复名称编辑" onClick={event => event.stopPropagation()}>
        <p>恢复的名称草稿</p>
        <Input aria-label={`会话名称：${directory.editor.target.conversationId}`} value={directory.editor.draft}
               maxLength={256}
               readOnly={directory.editor.status === 'uncertain' || directory.editor.status === 'saving'}
               onChange={event => directory.changeDraft(event.target.value)}
               onBlur={() => void directory.finishEdit(false)} onKeyDown={event => {
          if (event.nativeEvent.isComposing || event.keyCode === 229) return;
          if (event.key === 'Escape') {
            event.preventDefault();
            directory.cancelEdit();
          }
          if (event.key === 'Enter') {
            event.preventDefault();
            void directory.finishEdit(true);
          }
        }}/>
        {directory.editor.error && <p role="alert">{directory.editor.error}</p>}
        {!directory.pendingCommand && <><Button loading={directory.commandBusy}
                                                onMouseDown={event => event.preventDefault()}
                                                onClick={() => void directory.finishEdit(true)}>保存草稿</Button>
          <Button onMouseDown={event => event.preventDefault()} onClick={directory.cancelEdit}>取消编辑</Button></>}
      </section>}
    {!readBlocked && directory.listError != null && <Alert type="error" title="会话列表暂时加载失败"
                                                           description="已有会话仍然保留，请重试读取。" action={<Button
      onClick={() => void directory.refreshFirst()}>重试加载</Button>}/>}
    {readBlocked && <p className="ainew-muted" role="status">当前账号暂时无法读取此空间的会话列表。</p>}
    {/* biome-ignore lint/a11y/noNoninteractiveTabindex: 命名滚动区域需支持键盘方向键及 Home/End。 */}
    <section ref={scroller} className="ainew-directory-scroll" aria-label="会话滚动列表" tabIndex={0}
             onWheel={event => {
               intent.current = true;
               programmatic.current = false;
               if (event.deltaY < 0 && event.currentTarget.scrollTop <= 1 && directory.headEvicted) {
                 intent.current = false;
                 void directory.refreshFirst();
               }
             }}
             onTouchMove={() => {
               intent.current = true;
               programmatic.current = false;
             }}
             onPointerDown={event => {
               if (event.target === event.currentTarget) {
                 intent.current = true;
                 programmatic.current = false;
               }
             }}
             onKeyDown={event => {
               if (event.target === event.currentTarget && ['Home', 'PageUp', 'ArrowUp', 'PageDown', 'ArrowDown', 'End'].includes(event.key)) {
                 intent.current = true;
                 programmatic.current = false;
                 if (event.key === 'Home' && directory.headEvicted && event.currentTarget.scrollTop === 0) {
                   event.preventDefault();
                   void directory.refreshFirst();
                 }
               }
             }} onScroll={event => {
      setMenu(null);
      const root = event.currentTarget, top = root.scrollTop;
      if (directory.headEvicted && top <= 1 && intent.current && !programmatic.current && top < previousTop.current) {
        intent.current = false;
        void directory.refreshFirst();
      } else if (root.scrollHeight - root.clientHeight - top < 100 && !directory.listLoading && !directory.listError) void directory.loadMore();
      previousTop.current = top;
    }}>
      <ul className="ainew-directory-rows" aria-label="置顶会话">{directory.pinned.map(renderRow)}</ul>
      <ul className="ainew-directory-rows" aria-label="普通会话">{directory.normal.map(renderRow)}</ul>
      {!readBlocked && !directory.listLoading && !directory.listError && directory.records.length === 0 &&
        <Empty description="此空间还没有会话。"/>}
      {directory.listLoading && <p role="status" className="ainew-directory-status">正在读取会话列表…</p>}
      {directory.loadingMore && <p role="status" className="ainew-directory-status">正在加载下一页…</p>}
      {directory.paused && <p role="status" className="ainew-directory-status">请先完成当前名称编辑，再加载后续会话。</p>}
      {directory.moreError && <div className="ainew-directory-status" role="alert">{directory.moreError}
        <Button size="small" onClick={() => void directory.loadMore(true)}>重试下一页</Button></div>}
      {!readBlocked && !directory.listLoading && !directory.listError && !directory.hasMore && directory.records.length > 0 &&
        <p className="ainew-directory-status">— 已加载全部 —</p>}
      {directory.headEvicted && <p className="ainew-directory-status ainew-muted">前部会话可返回顶部重新读取。</p>}
    </section>
    <Button className="ainew-directory-backtop" shape="circle" aria-label="返回会话顶部" loading={directory.listLoading}
            icon={<DynamicIcon iconName="VerticalAlignTopOutlined" size={20}/>}
            onClick={() => void directory.refreshFirst()}/>
  </aside>;
}
