import React from 'react';
import {Button, Card, Empty, Flex, Splitter, Tooltip} from 'antd';
import DynamicIcon from '@/components/DynamicIcon/MyDynamicIcon';
import './style.css';

export interface MasterDetailAction {
  key: string;
  label: string;
  icon: string;
  onClick: () => void;
  disabled?: boolean;
  loading?: boolean;
}

export interface MasterDetailItem {
  key: string;
  label: string;
  content: React.ReactNode;
  disabled?: boolean;
}

interface Props {
  label: string;
  style?: React.CSSProperties;
  actions: MasterDetailAction[];
  items: MasterDetailItem[];
  selectedKey?: string;
  onSelect: (key: string) => void;
  listHeader?: React.ReactNode;
  listFooter?: React.ReactNode;
  emptyText?: string;
  children: React.ReactNode;
}

/**
 * 通用目录／详情框架：左侧为操作图标、搜索框、列表，右侧为表单详情。
 * 选择、草稿、权限及保存行为由调用方控制。
 */
export default function MasterDetailLayout({
                                             label, style, actions, items, selectedKey, onSelect,
                                             listHeader, listFooter, emptyText = '暂无记录', children
                                           }: Props) {
  const editor = React.useRef<HTMLDivElement>(null);
  const [vertical, setVertical] = React.useState(() => window.matchMedia('(max-width: 640px)').matches);

  React.useEffect(() => {
    if (editor.current) editor.current.scrollTop = 0;
  }, [selectedKey]);

  React.useEffect(() => {
    const query = window.matchMedia('(max-width: 640px)');
    const update = () => setVertical(query.matches);
    query.addEventListener('change', update);
    update();
    return () => query.removeEventListener('change', update);
  }, []);

  return <section className="master-detail-layout" style={style} aria-label={label}>
    <Splitter orientation={vertical ? 'vertical' : 'horizontal'}>
      <Splitter.Panel defaultSize="40%" min="20%" max="80%">
        <div className="master-detail-directory">
          {actions.length > 0 &&
            <Flex className="master-detail-toolbar" gap={8} role="toolbar" aria-label={`${label}操作`}>
              {/* 左侧上方操作图标 */}
              {actions.map(action =>
                <Tooltip key={action.key} title={action.label}>
                  <Button aria-label={action.label} disabled={action.disabled} loading={action.loading}
                          icon={<DynamicIcon iconName={action.icon}/>} onClick={action.onClick}/>
                </Tooltip>)}
            </Flex>}
          <div className="master-detail-scroll" role="region" aria-label={`${label}列表`} tabIndex={0}>
            {listHeader}
            <Flex vertical gap={10}>
              {!items.length && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyText}/>}
              {/* 左侧 card 列表，点击时加载右侧表单详情 */}
              {items.map(item =>
                <Card key={item.key} size="small" role="button" aria-label={item.label}
                      aria-pressed={selectedKey === item.key}
                      aria-disabled={item.disabled || undefined}
                      tabIndex={item.disabled ? -1 : 0}
                      className={`master-detail-card${selectedKey === item.key ? ' master-detail-card-selected' : ''}`}
                      onClick={() => {
                        if (!item.disabled) onSelect(item.key);
                      }}
                      onKeyDown={event => {
                        if ((event.key === 'Enter' || event.key === ' ') && event.target === event.currentTarget) {
                          event.preventDefault();
                          if (!item.disabled) onSelect(item.key);
                        }
                      }}>{item.content}
                </Card>)}
            </Flex>
            {listFooter}
          </div>
        </div>
      </Splitter.Panel>
      <Splitter.Panel min="20%">
        <div ref={editor} className="master-detail-scroll master-detail-editor" role="region"
             aria-label={`${label}详情`}
             tabIndex={0}>
          {/* 右侧表单详情 */}
          {children}
        </div>
      </Splitter.Panel>
    </Splitter>
  </section>;
}
