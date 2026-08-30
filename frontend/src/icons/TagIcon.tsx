import {TagOutlined} from '@ant-design/icons';
import {createStyles} from 'antd-style';
import React from "react";

export type TagIconProps = {
  width?: number,
  height?: number,
  color?: string,
  margin?: string,
  onClick?: (e: React.MouseEvent) => void
};

const useStyle = function tagStyle(tagIconProps: TagIconProps) {
  const {width, height, color, margin} = tagIconProps;
  return createStyles(({ css }) => ({
    tag: css`
      margin: ${margin};

      :hover {
        cursor: pointer;
      }

      svg {
        width: ${width}px;
        height: ${height}px;
        fill: ${color};
      }
    `,
  }))();
};

export default function TagIcon(tagIconProps: TagIconProps) {
  const {styles} = useStyle(tagIconProps);
  return <TagOutlined onClick={tagIconProps.onClick} className={styles.tag}/>;
}
