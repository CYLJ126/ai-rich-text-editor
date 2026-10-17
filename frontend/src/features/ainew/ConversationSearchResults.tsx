import React, {useEffect, useLayoutEffect, useRef, useState} from 'react';
import {Alert, Button, Empty, Flex, Spin, Typography} from 'antd';
import type {ConversationSearchItem as SearchItem} from '@/services/arte-ai/conversation-search-contract';
import type {ConversationSearch} from './useConversationSearch';
import ConversationSearchItem from './ConversationSearchItem';

export default function ConversationSearchResults({search, onOpen}: {
  search: ConversationSearch;
  onOpen: (row: SearchItem, mode: 'message' | 'conversation') => void
}) {
  const root = useRef<HTMLElement>(null), programmatic = useRef(false), intent = useRef(false);
  const anchor = useRef<{ key: string; offset: number } | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [narrowPreview, setNarrowPreview] = useState(() => window.innerWidth < 600);
  useEffect(() => {
    const resize = () => setNarrowPreview(window.innerWidth < 600);
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
  }, []);
  const busy = search.loading || !!search.paging || !!search.busy;

  function capture() {
    const node = root.current;
    if (!node) return;
    const top = node.getBoundingClientRect().top;
    const row = [...node.querySelectorAll<HTMLElement>('[data-search-key]')].find(value => value.getBoundingClientRect().bottom > top);
    anchor.current = row ? {key: row.dataset.searchKey!, offset: row.getBoundingClientRect().top - top} : null;
  }

  function load(direction: 'before' | 'after' | 'first') {
    capture();
    setPreview(null);
    void search.load(direction);
  }

  useLayoutEffect(() => search.captureAnchor(capture), [search.captureAnchor]);

  useLayoutEffect(() => {
    const node = root.current;
    if (!node) return;
    programmatic.current = true;
    intent.current = false;
    if (search.placement === 'first') node.scrollTop = 0;
    else if (anchor.current) {
      const row = [...node.querySelectorAll<HTMLElement>('[data-search-key]')].find(value => value.dataset.searchKey === anchor.current!.key);
      if (row) node.scrollTop += row.getBoundingClientRect().top - node.getBoundingClientRect().top - anchor.current.offset;
    }
    anchor.current = null;
    const frame = requestAnimationFrame(() => {
      programmatic.current = false;
    });
    return () => cancelAnimationFrame(frame);
  }, [search.revision]);
  useEffect(() => {
    if (busy || !search.rows.some(row => row.entryKey === preview && !('unavailable' in row))) setPreview(null);
  }, [search.rows, busy, preview]);
  useEffect(() => {
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setPreview(null);
    };
    document.addEventListener('keydown', escape);
    return () => document.removeEventListener('keydown', escape);
  }, []);
  return <Flex vertical gap={8} className="ainew-search-content" aria-label="会话搜索结果" aria-busy={search.loading}>
    {search.expired && <Alert type="info" title="搜索已过期，按 Enter 重新搜索"/>}
    {search.error && (!search.expired || search.error !== '搜索已过期，按 Enter 重新搜索') &&
      <Alert type="error" title={search.error}/>}
    <Flex gap={4} wrap className="ainew-search-navigation">
      <Button size="small" disabled={busy || search.expired || !search.previous}
              onClick={() => load('before')}>加载前部结果</Button>
      <Button size="small" disabled={busy || search.expired || !search.pages.length}
              onClick={() => load('first')}>回到搜索首页</Button>
    </Flex>
    {!search.previous && search.pages.length > 0 &&
      <Typography.Text type="secondary" className="ainew-muted">已到搜索结果开头</Typography.Text>}
    <section ref={root} className="ainew-search-scroll" aria-label="搜索结果滚动列表" tabIndex={0}
             onWheel={() => {
               intent.current = true;
               programmatic.current = false;
             }}
             onTouchMove={() => {
               intent.current = true;
               programmatic.current = false;
             }}
             onKeyDown={event => {
               if (event.target === event.currentTarget && ['ArrowDown', 'PageDown', 'End'].includes(event.key)) intent.current = true;
             }}
             onScroll={event => {
               setPreview(null);
               const node = event.currentTarget;
               if (!intent.current || programmatic.current || node.clientHeight <= 0 || node.closest('[hidden]') || busy || search.error || search.partial || search.expired) return;
               if (node.scrollHeight - node.scrollTop - node.clientHeight < 100 && search.next) {
                 intent.current = false;
                 load('after');
               }
             }}>
      {!search.loading && !search.rows.length && !search.error && search.pages.length > 0 && !search.next && !search.partial &&
        <Empty description="未找到当前可检索内容"/>}
      {!search.loading && !search.pages.length && !search.error &&
        <Typography.Text type="secondary">输入关键词搜索；空输入按 Enter 返回会话列表。</Typography.Text>}
      {search.rows.length > 0 &&
        <ul className="ainew-search-rows">{search.rows.map(row => <ConversationSearchItem key={row.entryKey} row={row}
                                                                                          disabled={busy}
                                                                                          busy={search.busy?.key === row.entryKey ? search.busy.mode : null}
                                                                                          preview={preview === row.entryKey}
                                                                                          narrowPreview={narrowPreview}
                                                                                          onPreview={open => setPreview(open ? row.entryKey : null)}
                                                                                          onOpen={onOpen}/>)}</ul>}
      {search.partial && <Typography.Paragraph
        role="status">{search.rows.length ? '本次返回部分结果，可继续查找。' : '本次尚未找到可展示结果，可继续查找。'}</Typography.Paragraph>}
      {!search.loading && search.pages.length > 0 && !search.next && search.rows.length > 0 &&
        <Typography.Paragraph type="secondary">当前搜索结果已加载完</Typography.Paragraph>}
      {search.next && <Button block disabled={busy || search.expired} loading={search.paging === 'after'}
                              onClick={() => load('after')}>{search.partial ? '继续查找后部结果' : '加载后部结果'}</Button>}
      {search.paging === 'before' && <Typography.Paragraph role="status">正在加载前部结果…</Typography.Paragraph>}
    </section>
    <Typography.Text type="secondary" className="ainew-muted">新消息可能稍后出现在搜索中</Typography.Text>
    {search.loading && <div className="ainew-search-mask" role="status"><Spin/><span>正在搜索…</span></div>}
  </Flex>;
}
