import { ReactComponent as DeleteSvg } from '@/assets/icon/delete-garbage.svg';
import React, { CSSProperties } from 'react';

interface DeleteIconProps {
  className?: string;
  color?: string;
  size?: number | string;
  onClick?: React.MouseEventHandler<HTMLSpanElement>;
  style?: CSSProperties;
}

export default function DeleteIcon({
                                     className,
                                     onClick,
                                     color = 'currentColor',
                                     size = 16,
                                     style,
                                   }: DeleteIconProps) {
  return (
    <span
      className={className}
      onClick={onClick}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        cursor: onClick ? 'pointer' : 'default',
        color,   // ✅ 通过 color CSS属性传递颜色
        ...style,
      }}
    >
      <DeleteSvg
        width={size}
        height={size}
        fill={color}   // ✅ SVG 继承父元素 color
        style={{ display: 'block' }}
      />
    </span>
  );
}
