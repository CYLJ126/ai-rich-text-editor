import React, {useMemo, useState} from 'react';
import {Empty, Input, Popover, Tooltip} from 'antd';
import {SearchOutlined} from '@ant-design/icons';
import MyDynamicIcon from './MyDynamicIcon';
import {getIconNames} from './iconMap';
import {createStyles} from 'antd-style';

const useStyles = createStyles(({token, css}) => ({
  trigger: css`
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 4px 11px;
    border: 1px solid ${token.colorBorder};
    border-radius: ${token.borderRadius}px;
    cursor: pointer;
    min-width: 160px;
    transition: border-color 0.2s;
    background: ${token.colorBgContainer};

    &:hover {
      border-color: ${token.colorPrimary};
    }
  `,
  pickerContainer: css`
    width: 320px;
  `,
  iconGrid: css`
    display: grid;
    grid-template-columns: repeat(8, 1fr);
    gap: 4px;
    max-height: 240px;
    overflow-y: auto;
    margin-top: 8px;
    padding: 4px;
  `,
  iconItem: css`
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border-radius: ${token.borderRadiusSM}px;
    cursor: pointer;
    font-size: 18px;
    transition: all 0.2s;

    &:hover {
      background: ${token.colorPrimaryBg};
      color: ${token.colorPrimary};
    }
  `,
  iconItemActive: css`
    background: ${token.colorPrimaryBg};
    color: ${token.colorPrimary};
    border: 1px solid ${token.colorPrimary};
  `,
  selectedText: css`
    flex: 1;
    font-size: 14px;
    color: ${token.colorText};
  `,
  placeholder: css`
    flex: 1;
    font-size: 14px;
    color: ${token.colorTextPlaceholder};
  `,
}));

export interface IconPickerProps {
  /** 当前选中的图标名称 */
  value?: string;
  /** 选中图标后的回调 */
  onChange?: (iconName: string) => void;
  /** 占位提示 */
  placeholder?: string;
  /** 禁用状态 */
  disabled?: boolean;
}

const IconPicker: React.FC<IconPickerProps> = ({
                                                 value,
                                                 onChange,
                                                 placeholder = '请选择图标',
                                                 disabled = false,
                                               }) => {
  const {styles, cx} = useStyles();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');

  const allIconNames = useMemo(() => getIconNames(), []);

  const filteredIcons = useMemo(() => {
    if (!search.trim()) return allIconNames;
    return allIconNames.filter((name) =>
      name.toLowerCase().includes(search.toLowerCase()),
    );
  }, [search, allIconNames]);

  const handleSelect = (iconName: string) => {
    onChange?.(iconName);
    setOpen(false);
    setSearch('');
  };

  const content = (
    <div className={styles.pickerContainer}>
      <Input
        prefix={<SearchOutlined/>}
        placeholder="搜索图标名称..."
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        allowClear
        autoFocus
      />
      {filteredIcons.length === 0 ? (
        <Empty description="未找到图标" style={{margin: '16px 0'}}/>
      ) : (
        <div className={styles.iconGrid}>
          {filteredIcons.map((name) => (
            <Tooltip key={name} title={name} placement="top">
              <div
                className={cx(
                  styles.iconItem,
                  value === name && styles.iconItemActive,
                )}
                onClick={() => handleSelect(name)}
              >
                <MyDynamicIcon iconName={name} fallback={null}/>
              </div>
            </Tooltip>
          ))}
        </div>
      )}
    </div>
  );

  return (
    <Popover
      content={content}
      trigger="click"
      open={open && !disabled}
      onOpenChange={(v) => !disabled && setOpen(v)}
      placement="bottomLeft"
    >
      <div className={styles.trigger}>
        {value ? (
          <>
            <MyDynamicIcon iconName={value} fallback={null}/>
            <span className={styles.selectedText}>{value}</span>
          </>
        ) : (
          <span className={styles.placeholder}>{placeholder}</span>
        )}
      </div>
    </Popover>
  );
};

export default IconPicker;
