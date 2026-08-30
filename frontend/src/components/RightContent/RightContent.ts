import {createStyles} from "antd-style";

export const useRightContentStyles = createStyles(({token, css}) => ({
  action: css`
    display: inline-flex !important;
    align-items: center !important;
    justify-content: center !important;
    height: 30px !important;
    min-width: 30px;
    padding-inline: 4px !important;
    padding-block: 0 !important;
    border-radius: ${token.borderRadius}px !important;
  `,
  popoverContent: css`
    display: flex;
    flex-direction: column;
    gap: ${token.marginSM}px;
    min-width: 200px;
  `,
  divider: css`
    width: 100%;
    height: 1px;
    background-color: ${token.colorBorderSecondary};
    margin: ${token.marginXXS}px 0;
  `,
  switchRow: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: ${token.marginSM}px;
  `,
  switchLabel: css`
    font-size: ${token.fontSize}px;
    font-weight: bold;
    color: ${token.colorText};
  `,
}));
