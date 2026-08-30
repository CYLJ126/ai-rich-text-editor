import React, {memo} from 'react';
import {Tooltip} from 'antd';
import {createStyles} from 'antd-style';

const useStyles = createStyles(({token, css}) => ({
  badge: css`
    display: inline-flex;
    align-items: center;
    gap: 3px;
    font-size: 11px;
    color: ${token.colorTextQuaternary};
    cursor: default;
    padding: 1px 6px;
    background: ${token.colorFillQuaternary};
    border-radius: 8px;
    border: 1px solid ${token.colorBorderSecondary};
    transition: color 0.15s, background 0.15s;

    &:hover {
      color: ${token.colorTextSecondary};
      background: ${token.colorFillTertiary};
    }
  `,
  dot: css`
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: ${token.colorPrimary};
    opacity: 0.6;
    flex-shrink: 0;
  `,
}));

interface TokenBadgeProps {
  prompt?: number;
  completion?: number;
  total?: number;
  think?: number;
}

// ─── 主组件：TokenBadge 徽标 ───
const TokenBadge: React.FC<TokenBadgeProps> = memo(
  ({prompt, completion, total, think}) => {
    const {styles} = useStyles();

    const tooltipContent = (
      <div style={{fontSize: 12, lineHeight: 1.8}}>
        {prompt != null && <div>提示词：{prompt.toLocaleString()} tokens</div>}
        {think != null && think > 0 && (
          <div>思考：{think.toLocaleString()} tokens</div>
        )}
        {completion != null && (
          <div>输出：{completion.toLocaleString()} tokens</div>
        )}
        {total != null && (
          <div style={{borderTop: '1px solid rgba(255,255,255,0.15)', marginTop: 4, paddingTop: 4}}>
            合计：{total.toLocaleString()} tokens
          </div>
        )}
      </div>
    );

    return (
      <Tooltip title={tooltipContent} placement="top">
        <span className={styles.badge}>
          <span className={styles.dot}/>
          {total != null ? `${total.toLocaleString()} tokens` : '—'}
        </span>
      </Tooltip>
    );
  },
);

export default TokenBadge;
