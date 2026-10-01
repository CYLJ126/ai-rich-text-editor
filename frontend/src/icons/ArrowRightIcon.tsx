import type {MouseEventHandler} from 'react';
import { ReactComponent as ArrowRightFromArcSvg } from '@/assets/icon/arrow-right-from-arc.svg';

interface ArrowRightIconProps {
  className?: string;
  onClick?: MouseEventHandler<SVGSVGElement>;
}

export default function ArrowRightIcon({
                                         className,
                                         onClick,
                                       }: ArrowRightIconProps) {
  return (
    <span className={className}>
      <ArrowRightFromArcSvg onClick={onClick} />
    </span>
  );
}
