import React, {useCallback, useEffect, useRef, useState,} from 'react';
import {createStyles} from 'antd-style';
import {CheckOutlined, CloseOutlined, CopyOutlined} from '@ant-design/icons';
import {Tooltip} from 'antd';

const useStyles = createStyles(({token, css}) => ({
  wrapper: css`
    position: fixed;
    z-index: 9999;
    pointer-events: all;
  `,
  container: css`
    background: ${token.colorBgElevated};
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    box-shadow: ${token.boxShadowSecondary};
    padding: 12px 14px;
    max-width: 360px;
    min-width: 200px;
    word-break: break-word;
    transition: opacity 0.4s ease;
  `,
  header: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8px;
    gap: 8px;
  `,
  label: css`
    font-size: 12px;
    color: ${token.colorTextTertiary};
    font-weight: 500;
    flex: 1;
  `,
  actions: css`
    display: flex;
    align-items: center;
    gap: 4px;
    flex-shrink: 0;
  `,
  iconBtn: css`
    display: flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    border-radius: ${token.borderRadiusSM}px;
    cursor: pointer;
    color: ${token.colorTextTertiary};
    transition: all 0.2s;
    font-size: 13px;
    border: none;
    background: transparent;
    padding: 0;

    &:hover {
      color: ${token.colorText};
      background: ${token.colorFillSecondary};
    }
  `,
  content: css`
    font-size: 14px;
    color: ${token.colorText};
    line-height: 1.6;
  `,
  divider: css`
    width: 1px;
    height: 14px;
    background: ${token.colorBorderSecondary};
    margin: 0 2px;
  `,
}));

export interface FloatingResultViewProps {
  content: string;
  position: { top: number; left: number };
  label?: string;
  onClose: () => void;
}

const HIDE_DELAY = 2000;

const FloatingResultView: React.FC<FloatingResultViewProps> = ({
                                                                 content,
                                                                 position,
                                                                 label = '结果',
                                                                 onClose,
                                                               }) => {
  const {styles} = useStyles();
  const [opacity, setOpacity] = useState(1);
  const [copied, setCopied] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const fadeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isMouseInsideRef = useRef(false);

  // ---- 清理定时器 ----
  const clearTimers = useCallback(() => {
    if (hideTimerRef.current) {
      clearTimeout(hideTimerRef.current);
      hideTimerRef.current = null;
    }
    if (fadeTimerRef.current) {
      clearTimeout(fadeTimerRef.current);
      fadeTimerRef.current = null;
    }
  }, []);

  // ---- 启动消失倒计时 ----
  const startHideCountdown = useCallback(() => {
    clearTimers();
    setOpacity(1);
    hideTimerRef.current = setTimeout(() => {
      setOpacity(0);
      fadeTimerRef.current = setTimeout(() => {
        onClose();
      }, 400); // 等待 fade-out 动画结束
    }, HIDE_DELAY);
  }, [clearTimers, onClose]);

  // ---- 鼠标离开编辑器区域时触发（由父级通知） ----
  // 内部：鼠标进入悬浮框 → 停止计时
  const handleMouseEnter = useCallback(() => {
    isMouseInsideRef.current = true;
    clearTimers();
    setOpacity(1);
  }, [clearTimers]);

  // 内部：鼠标离开悬浮框 → 重新开始倒计时
  const handleMouseLeave = useCallback(() => {
    isMouseInsideRef.current = false;
    startHideCountdown();
  }, [startHideCountdown]);

  // ---- 内容更新或首次显示时，重置并启动倒计时 ----
  useEffect(() => {
    setOpacity(1);
    startHideCountdown();
    return () => {
      clearTimers();
    };
  }, [content]); // eslint-disable-line react-hooks/exhaustive-deps

  // ---- 复制 ----
  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // fallback
      const el = document.createElement('textarea');
      el.value = content;
      document.body.appendChild(el);
      el.select();
      document.execCommand('copy');
      document.body.removeChild(el);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }
  }, [content]);

  // ---- 关闭 ----
  const handleClose = useCallback(() => {
    clearTimers();
    onClose();
  }, [clearTimers, onClose]);

  return (
    <div
      className={styles.wrapper}
      style={{top: position.top, left: position.left}}
      ref={containerRef}
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
    >
      <div
        className={styles.container}
        style={{opacity, transition: 'opacity 0.4s ease'}}
      >
        {/* Header */}
        <div className={styles.header}>
          <span className={styles.label}>{label}</span>
          <div className={styles.actions}>
            <Tooltip title={copied ? '已复制' : '复制'} placement="top">
              <button
                className={styles.iconBtn}
                onClick={handleCopy}
                type="button"
              >
                {copied ? (
                  <CheckOutlined style={{color: '#52c41a'}}/>
                ) : (
                  <CopyOutlined/>
                )}
              </button>
            </Tooltip>
            <div className={styles.divider}/>
            <Tooltip title="关闭" placement="top">
              <button
                className={styles.iconBtn}
                onClick={handleClose}
                type="button"
              >
                <CloseOutlined/>
              </button>
            </Tooltip>
          </div>
        </div>
        {/* Content */}
        <div className={styles.content}>{content}</div>
      </div>
    </div>
  );
};

export default FloatingResultView;
