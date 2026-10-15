import React, {useLayoutEffect, useRef} from 'react';
import {Button, Splitter, Tooltip} from 'antd';
import {MyDynamicIcon as DynamicIcon} from '@/components/DynamicIcon';
import {CHAT_MIN, type ChatLayout, type ChatRegion, SIDE_MIN} from './useChatLayout';

export default function ChatWorkspaceLayout({layout, list, settings, children}: {
  layout: ChatLayout; list: React.ReactNode; settings: React.ReactNode;
  children: React.ReactNode;
}) {
  const root = useRef<HTMLDivElement>(null);
  const focusRegion = useRef<ChatRegion | null>(null);
  useLayoutEffect(() => {
    const active = document.activeElement;
    let target = focusRegion.current;
    focusRegion.current = null;
    if (!target && active instanceof HTMLElement && root.current?.contains(active) && active.closest('[hidden]')) {
      const hiddenRegion = active.closest<HTMLElement>('[data-region]')?.dataset.region as ChatRegion;
      target = layout.mode === 'single' ? layout.region : hiddenRegion;
    }
    if (!target) return;
    const selector = layout.visible[target] ? `[data-region="${target}"]` : `[data-region-entry="${target}"]`;
    root.current?.querySelector<HTMLElement>(selector)?.focus({preventScroll: true});
  }, [layout.mode, layout.region, layout.visible.list, layout.visible.chat, layout.visible.settings]);
  const openRegion = (region: ChatRegion) => {
    focusRegion.current = region;
    layout.open(region);
  };
  const single = layout.mode === 'single';

  return <div ref={root} className={`ainew-workspace-shell${single ? ' is-single' : ''}`}>
    <div ref={layout.container} className="ainew-layout" data-layout-mode={layout.mode}>
      <Splitter onResize={layout.resize}
                destroyOnHidden={false}
                className="ainew-splitter" styles={{dragger: {default: single ? {display: 'none'} : undefined}}}>
        <Splitter.Panel size={layout.sizes[0]} min={SIDE_MIN} max={single ? '100%' : '50%'}
                        resizable={!single && layout.visible.list} collapsible={!single}>
          <div className="ainew-region ainew-region-side" hidden={!layout.visible.list} inert={!layout.visible.list}
               data-region="list" tabIndex={-1}>
            {single && <div className="ainew-return-chat"><Button type="text" aria-label="返回聊天"
                                                                  icon={<DynamicIcon iconName="ArrowLeftOutlined"/>}
                                                                  onClick={() => openRegion('chat')}>返回聊天</Button>
            </div>}
            <div className="ainew-region-body">{list}</div>
          </div>
        </Splitter.Panel>

        <Splitter.Panel size={layout.sizes[1]} min={single ? 0 : CHAT_MIN} resizable={!single}>
          <div className="ainew-region" hidden={!layout.visible.chat} inert={!layout.visible.chat}
               data-region="chat" tabIndex={-1}>{children}</div>
        </Splitter.Panel>

        <Splitter.Panel size={layout.sizes[2]} min={SIDE_MIN} max={single ? '100%' : '50%'}
                        resizable={!single && layout.visible.settings} collapsible={!single}>
          <div className="ainew-region ainew-region-side" hidden={!layout.visible.settings}
               inert={!layout.visible.settings}
               data-region="settings" tabIndex={-1}>
            {single && <div className="ainew-return-chat"><Button type="text" aria-label="返回聊天"
                                                                  icon={<DynamicIcon iconName="ArrowLeftOutlined"/>}
                                                                  onClick={() => openRegion('chat')}>返回聊天</Button>
            </div>}
            <div className="ainew-region-body">{settings}</div>
          </div>
        </Splitter.Panel>
      </Splitter>
      {!layout.visible.list && <Tooltip title="展开会话列表" placement="right">
        <Button type="text" className="ainew-edge-expand is-left" data-region-entry="list" aria-label="展开会话列表"
                aria-expanded={false}
                icon={<DynamicIcon iconName="MessageOutlined"/>} onClick={() => openRegion('list')}/>
      </Tooltip>}
      {!layout.visible.settings && <Tooltip title="展开设置侧栏" placement="left">
        <Button type="text" className="ainew-edge-expand is-right" data-region-entry="settings"
                aria-label="展开设置侧栏" aria-expanded={false}
                icon={<DynamicIcon iconName="SettingOutlined"/>} onClick={() => openRegion('settings')}/>
      </Tooltip>}
      {layout.width < SIDE_MIN &&
        <p className="ainew-width-notice" role="status">聊天区域至少需要 300px 宽度，请展开页面。</p>}
    </div>
  </div>;
}
