import { createStyles } from 'antd-style';
import { ReactComponent as AiSvg } from '@/assets/icon/ai.svg';

const useStyle = function tagStyle(width, height, color, margin) {
  return createStyles(({ css }) => ({
    ai: css`
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

export default function AiIcon({ width, height, color, margin, onClick }) {
  const { styles } = useStyle(width, height, color, margin);
  return <AiSvg onClick={onClick} className={styles.ai} />;
}
