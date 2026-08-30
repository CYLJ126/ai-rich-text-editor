import type {CSSProperties, FC, MouseEvent, ReactNode} from 'react';
import React from 'react';
import type {TooltipProps} from 'antd';
import {Tooltip} from 'antd';

/** 单个图标项定义 */
export interface IconItem {
  /** 唯一标识 */
  key: string;
  /** 图标节点，由外部传入，可携带自定义样式和事件 */
  icon: ReactNode;
  /** 提示文字，有值则包裹 Tooltip，无值则直接渲染 */
  tip?: ReactNode;
  /** Tooltip 的额外配置，tip 有值时生效 */
  tipProps?: Omit<TooltipProps, 'title'>;
  /** 是否禁用，禁用时图标置灰且不可点击 */
  disabled?: boolean;
  /** 格子额外样式，优先级高于全局 cellStyle */
  itemCellStyle?: CSSProperties;
  /** 图标额外样式，优先级高于全局 iconStyle */
  itemIconStyle?: CSSProperties;
}

/** 子组件：单个图标渲染 */
interface IconCellProps {
  item: IconItem;
  cellWidth: number;
  cellHeight: number;
  globalCellStyle?: React.CSSProperties;
  globalIconStyle?: React.CSSProperties;
}

/**
 * IconGrid 组件 Props
 */
export interface IconGridProps {
  /** 每行放几个图标 */
  columns: number;
  /** 每个格子的 [宽, 高]，单位 px */
  cellSize: [number, number];
  /** 图标列表 */
  icons: IconItem[];
  /** 图标通用样式，会被图标自身 itemIconStyle 覆盖 */
  iconStyle?: CSSProperties;
  /** 每个格子的通用样式，会被 itemCellStyle 覆盖 */
  cellStyle?: CSSProperties;
}

/**
 * 将图标列表按 columns 分组成二维数组
 */
function chunkItems(items: IconItem[], columns: number): IconItem[][] {
  const rows: IconItem[][] = [];
  for (let i = 0; i < items.length; i += columns) {
    rows.push(items.slice(i, i + columns));
  }
  return rows;
}

/**
 * 阻止点击事件向上冒泡
 */
function stopPropagation(e: MouseEvent<HTMLDivElement>) {
  e.stopPropagation();
}

const IconCell: FC<IconCellProps> = ({
                                       item,
                                       cellWidth,
                                       cellHeight,
                                       globalCellStyle,
                                       globalIconStyle,
                                     }) => {
  const {icon, tip, tipProps, disabled, itemCellStyle, itemIconStyle} = item;
  // ── 格子样式：全局 cellStyle → itemCellStyle 覆盖 ──
  const cellStyle: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: cellWidth,
    height: cellHeight,
    flexShrink: 0,
    boxSizing: 'border-box',
    overflow: 'hidden',
    cursor: disabled ? 'not-allowed' : 'pointer',
    ...globalCellStyle,
    ...itemCellStyle,
    // 禁用时叠加半透明
    ...(disabled ? {opacity: 0.4, pointerEvents: 'none'} : {}),
  };
  // ── 图标包裹层样式：全局 iconStyle → itemIconStyle 覆盖 ──
  const iconWrapStyle: React.CSSProperties = {
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    ...globalIconStyle,
    ...itemIconStyle,
  };
  // ── 图标内容 ──
  const iconContent = (
    <span style={iconWrapStyle}>
      {icon}
    </span>
  );
  // ── 有 tip 则包裹 Tooltip，否则直接渲染 ──
  const content =
    tip !== undefined && tip !== null && tip !== '' ? (
      <Tooltip title={tip} {...tipProps}>
        {iconContent}
      </Tooltip>
    ) : (
      iconContent
    );
  return (
    <div style={cellStyle} onClick={stopPropagation}>
      {content}
    </div>
  );
};

// 主组件：图标网格，将图标规整排列
const IconGrid: FC<IconGridProps> = ({
                                       columns,
                                       cellSize,
                                       icons,
                                       iconStyle,
                                       cellStyle,
                                     }) => {
  const [cellWidth, cellHeight] = cellSize;
  if (columns <= 0) {
    console.warn('[IconGrid] columns 必须大于 0');
    return null;
  }
  const rows = chunkItems(icons, columns);
  const containerStyle: React.CSSProperties = {
    display: 'flex',
    flexDirection: 'column',
    width: Math.min(icons.length, columns) * cellWidth,
    height: rows.length * cellHeight,
    boxSizing: 'border-box',
  };
  const rowStyle: React.CSSProperties = {
    display: 'flex',
    flexDirection: 'row',
    flexShrink: 0,
  };
  return (
    <div style={containerStyle}>
      {rows.map((rowItems, rowIndex) => (
        <div key={rowIndex} style={rowStyle}>
          {rowItems.map((item) => (
            <IconCell
              key={item.key}
              item={item}
              cellWidth={cellWidth}
              cellHeight={cellHeight}
              globalCellStyle={cellStyle}
              globalIconStyle={iconStyle}
            />
          ))}
        </div>
      ))}
    </div>
  );
};
export default IconGrid;
