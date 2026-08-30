import { createStyles } from 'antd-style';
import { ReactComponent as CheckedSuccessSvg } from '@/assets/icon/checked-success.svg';

const useStyle = function tagStyle(width, height, color, margin) {
  return createStyles(({ css }) => ({
    success: css`
      width: ${width}px;
      height: ${height}px;
      fill: ${color};
      margin: ${margin};

      :hover {
        cursor: pointer;
      }
    `,
  }))();
};

interface SuccessIconProps {
  color?: string;
  height?: number;
  width?: number;
  margin?: number | string;
  onClick?: () => void;
  className?: string;
}

export default function SuccessIcon({
  width,
  height,
  color,
  margin,
  onClick,
  className,
}: SuccessIconProps) {
  const { styles } = useStyle(width, height, color, margin);
  return (
    <CheckedSuccessSvg
      onClick={onClick}
      className={`${styles.success} ${className}`}
    />
  );
}
