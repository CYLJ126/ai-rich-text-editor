import {createStyles} from 'antd-style';
import {calculateStringValue} from '@/utils/stringUtil';

const useTreeStyles = createStyles(
  ({css}, {pageHeight}: { pageHeight: number }) => ({
    container: css`
      padding-left: 5px;
      height: ${pageHeight}px;
      overflow: hidden;
      display: flex;
      flex-direction: column;
    `,
    buttonBar: css`
      flex-shrink: 0;
      padding-left: 5px;
      height: 35px;
      align-items: center;
    `,
    toggleBtn: css`
      height: 25px;
      font-size: 14px;
    `,
    addBtn: css`
      height: 25px;
      padding: 0 12px;
      font-size: 14px;
    `,
    treeScrollWrapper: css`
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      overflow-x: hidden;
      /* ── 隐藏滚动条，保留滚动功能 ── */
      scrollbar-width: none;
      -ms-overflow-style: none;

      &::-webkit-scrollbar {
        display: none;
      }
    `,
    tagTree: css`
      overflow: visible !important;
    `,
  }),
);

const useTagStyles = createStyles(
  ({css}, {title, color, level}: { title: any; color: string; level: number }) => {
    let titleWidth = calculateStringValue(title);
    titleWidth = titleWidth < 40 ? 45 : titleWidth;
    let opacity = 1 - level / 10;
    opacity = opacity < 0.3 ? 0.3 : opacity;
    return {
      tag: css`
        svg {
          color: ${color};
          opacity: ${opacity};
        }
      `,
      title: css`
        width: ${titleWidth}px;
        height: 25px;
        color: #ffffff !important;
        background-color: ${color};
        opacity: ${opacity};
        text-align: center;
        border: none;
        font-size: 14px;

        &:hover {
          background-color: ${color};
        }

        &:focus {
          background-color: ${color};
        }
      `,
      addIcon: css`
        position: absolute;
        top: 4px;
      `,
      deleteIcon: css`
        padding-left: 15px;

        svg {
          width: 25px;
          height: 25px;
          fill: ${color};

          line {
            stroke: ${color};
          }

          rect {
            stroke: ${color};
          }
        }
      `,
    };
  },
);

export {useTreeStyles, useTagStyles};
