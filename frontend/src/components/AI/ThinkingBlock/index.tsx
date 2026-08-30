import React, {memo, useState} from 'react';
import {createStyles} from 'antd-style';
import {LoadingOutlined, MinusOutlined, PlusOutlined} from '@ant-design/icons';

const useStyles = createStyles(({token, css}) => ({
  container: css`
    margin-bottom: 10px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 8px;
    overflow: hidden;
    background: ${token.colorFillQuaternary};
  `,
  header: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 7px 12px;
    cursor: pointer;
    user-select: none;
    transition: background 0.15s;

    &:hover {
      background: ${token.colorFillSecondary};
    }
  `,
  headerLeft: css`
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: ${token.colorTextSecondary};
    font-weight: 500;
  `,
  dot: css`
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: ${token.colorWarning};
    flex-shrink: 0;
  `,
  dotStreaming: css`
    background: ${token.colorPrimary};
    animation: pulse 1s ease-in-out infinite;
    @keyframes pulse {
      0%, 100% {
        opacity: 1;
        transform: scale(1);
      }
      50% {
        opacity: 0.5;
        transform: scale(1.3);
      }
    }
  `,
  toggleIcon: css`
    font-size: 11px;
    color: ${token.colorTextQuaternary};
  `,
  body: css`
    overflow: hidden;
    transition: max-height 0.25s ease;
  `,
  content: css`
    padding: 8px 12px 10px;
    font-size: 12.5px;
    line-height: 1.65;
    color: ${token.colorTextSecondary};
    white-space: pre-wrap;
    word-break: break-word;
    border-top: 1px solid ${token.colorBorderSecondary};
    font-family: ${token.fontFamilyCode};
    max-height: 320px;
    overflow-y: auto;

    &::-webkit-scrollbar {
      width: 4px;
    }

    &::-webkit-scrollbar-thumb {
      background: ${token.colorFillSecondary};
      border-radius: 2px;
    }
  `,
}));

interface ThinkingBlockProps {
  content: string;
  isStreaming?: boolean;
  defaultExpanded?: boolean;
}

// ─── 思考内容组件 ───
const ThinkingBlock: React.FC<ThinkingBlockProps> = memo(
  ({content, isStreaming = false, defaultExpanded = false}) => {
    const {styles, cx} = useStyles();
    const [expanded, setExpanded] = useState(defaultExpanded || isStreaming);

    // 流式结束后自动折叠
    React.useEffect(() => {
      if (!isStreaming && expanded) {
        const timer = setTimeout(() => setExpanded(false), 1200);
        return () => clearTimeout(timer);
      }
      return () => {
      };
    }, [isStreaming]);

    return (
      <div className={styles.container}>
        <div
          className={styles.header}
          onClick={() => setExpanded((v) => !v)}
        >
          <div className={styles.headerLeft}>
            <span
              className={cx(styles.dot, isStreaming && styles.dotStreaming)}
            />
            {isStreaming ? (
              <>
                <LoadingOutlined style={{fontSize: 11}}/>
                <span>正在思考中...</span>
              </>
            ) : (
              <span>思考过程</span>
            )}
          </div>
          <span className={styles.toggleIcon}>
            {expanded ? <MinusOutlined/> : <PlusOutlined/>}
          </span>
        </div>

        {expanded && (
          <div className={styles.body}>
            <div className={styles.content}>
              {content}
              {isStreaming && (
                <span
                  style={{
                    display: 'inline-block',
                    width: 2,
                    height: '1em',
                    background: 'currentColor',
                    marginLeft: 2,
                    verticalAlign: 'text-bottom',
                    animation: 'blink 0.8s steps(1) infinite',
                  }}
                />
              )}
            </div>
          </div>
        )}
      </div>
    );
  },
);

export default ThinkingBlock;
