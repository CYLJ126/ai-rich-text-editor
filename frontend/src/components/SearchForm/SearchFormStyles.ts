import { createStyles } from 'antd-style';

export const useSearchFormStyles = createStyles(
  ({ token, css }, size: 'small' | 'middle' | 'large') => {
    const sizeConfig = {
      small: { height: 24, valueWidth: 160, fontSize: 13 },
      middle: { height: 28, valueWidth: 271, fontSize: 16 },
      large: { height: 32, valueWidth: 300, fontSize: 20 },
    };

    const currentSize = sizeConfig[size];

    return {
      searchForm: css`
              padding: 5px;
              border: 1px solid var(--color-border);
              border-radius: 5px;

              .ant-form-item {
                margin-bottom: 0;

                .ant-form-item-no-colon::after {
                  // 使标签和框之间间距正好合适
                  width: 1px;
                }
              }

              .ant-form-item-label {
                word-wrap: break-word;
                white-space: normal;
                line-height: 2;

                label {
                  font-size: ${currentSize.fontSize}px !important;
                  height: auto;
                }
              }
            `,

      formItem: css`
        // 输入框样式
        .ant-form-item-control-input {
          width: ${currentSize.valueWidth}px !important;
          font-size: ${currentSize.fontSize}px !important;

          .ant-input {
            height: ${currentSize.height - 1}px !important;
          }
        }

        .ant-picker,
        .ant-select,
        .ant-input-number {
          width: ${currentSize.valueWidth}px !important;
          height: ${currentSize.height}px !important;
          font-size: ${currentSize.fontSize}px !important;
        }

        .ant-select-selection-item {
          font-size: ${currentSize.fontSize}px;
          line-height: ${currentSize.height - 2}px;
        }

        .ant-input-affix-wrapper {
          padding: 0 2px 0 10px;
        }
      `,

      buttonGroup: css`
        display: flex;
        width: 100%;
        justify-content: flex-end;
        gap: 8px;
        align-items: center;
        padding-right: 5px;

        .ant-btn {
          height: ${currentSize.height}px;
          font-size: ${currentSize.fontSize}px;
          padding: 0 16px;
        }
      `,

      collapseButton: css`
        color: ${token.colorPrimary};
        cursor: pointer;
        user-select: none;

        &:hover {
          color: ${token.colorPrimaryHover};
        }
      `,

      numberRange: css`
        display: flex;
        align-items: center;
        gap: 8px;

        .ant-input-number {
          flex: 1;
        }

        .range-separator {
          color: ${token.colorTextSecondary};
        }
      `,
    };
  },
);
