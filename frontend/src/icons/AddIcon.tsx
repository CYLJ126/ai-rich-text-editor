import { createStyles } from 'antd-style';
import { ReactComponent as AddSvg } from '@/assets/icon/add-square.svg';

const useStyle = function tagStyle(width, height, color) {
  return createStyles(({ css }) => ({
    add: css`
      width: ${width}px;
      height: ${height}px;
      fill: ${color};

      :hover {
        cursor: pointer;
      }
    `,
  }))();
};

export default function AddIcon({ width, height, color, onClick }) {
  const { styles } = useStyle(width, height, color);
  return <AddSvg onClick={onClick} className={styles.add} />;
}
