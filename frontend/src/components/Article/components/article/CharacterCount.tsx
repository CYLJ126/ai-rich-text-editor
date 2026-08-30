import React, {useEffect, useRef, useState} from 'react';
import ProgressBar from "@/components/ProgressBar";
import {createStyles} from "antd-style";

export interface CharacterCountProps {
  /** 当前文章字数 */
  characterCount: number,
}

// tiptap 在几十万字符下性能依旧 OK，先限制在 200000 字以内（Markdown格式 13万字）
export const MAX_CHARACTER_COUNT = 200000;

const useStyles = createStyles(({token, css}) => ({
  progressBar: css`
    .ant-progress-rail {
      color: red;
      background-color: ${token.colorFillSecondary} !important;
    }
  `
}));

const CharacterCount: React.FC<CharacterCountProps> = ({characterCount}) => {
  const { styles } = useStyles();
  const containerRef = useRef<HTMLDivElement>(null);
  const [progressBarWidth, setProgressBarWidth] = useState(300);
  const [percent, setPercent] = useState<number>(0)
  const [strokeColor, setStrokeColor] = useState<string>('#64bd89')

  useEffect(() => {
    // 取两位小数
    const percent = Math.round(characterCount / MAX_CHARACTER_COUNT * 100) / 100;
    setPercent(percent);
    if (percent < 0.3) {
      setStrokeColor('#64bd89');
    } else if (percent < 0.7) {
      setStrokeColor('#59aec6');
    } else {
      setStrokeColor('#f78922');
    }
  }, [characterCount])

  useEffect(() => {
    // 监听容器宽度变化，更新进度条宽度
    const container = containerRef.current;
    if (!container) return;
    const observer = new ResizeObserver(([entry]) => {
      setProgressBarWidth(Math.floor(entry.contentRect.width));
    });
    observer.observe(container);
    return () => observer.disconnect();
  }, []);

  return (
    <div ref={containerRef} className="w-full min-w-0 h-5">
      <ProgressBar
        progress={percent * 100}
        strokeColor={strokeColor}
        size={{height: 20, width: progressBarWidth}}
        showInfo={false}
        className={styles.progressBar}
      >
        <span>字数：{characterCount}/{MAX_CHARACTER_COUNT}</span>
      </ProgressBar>
    </div>
  )
};

export default CharacterCount;
