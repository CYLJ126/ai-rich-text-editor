import type {MouseEventHandler} from 'react';
import { ReactComponent as ThumbsUp } from '@/assets/icon/thumbs-up.svg';

interface ThumbsUpIconProps {
  isUp?: boolean;
  width?: number;
  height?: number;
  color?: string;
  margin?: number | string;
  onClick?: MouseEventHandler<SVGSVGElement>;
}

export default function DeleteIcon({
  isUp,
  width,
  height,
  color,
  margin,
  onClick,
                                   }: ThumbsUpIconProps) {
  return (
    <ThumbsUp
      transform={isUp ? '' : 'scale(1, -1)'}
      onClick={onClick}
      className="hover-pointer"
      style={{
        width: `${width}px`,
        height: `${height}px`,
        fill: color,
        margin: margin,
      }}
    />
  );
}
