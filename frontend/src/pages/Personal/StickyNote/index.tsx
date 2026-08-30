import React, {useCallback, useEffect, useRef, useState} from 'react';
import Header from './header';
import StickyNote from './stickyNote';
import {StickyNoteProvider, useStickyNoteData} from './stickyNoteContext';
import styles from './index.less';
import PageWrapper from "@/components/PageWrapper";
import {useComponentHeight} from "@/utils/useDynamicHeight";

function StickyWrap({offsetHeight}: { offsetHeight: number }) {
  const {stickies, isLoading} = useStickyNoteData();
  const containerRef = useRef<HTMLDivElement>(null);
  const headerRef = useRef<HTMLDivElement>(null);
  const [containerWidth, setContainerWidth] = useState<number>(0);
  let height = useComponentHeight(offsetHeight, 500);
  // 监听容器宽度变化
  const updateContainerWidth = useCallback(() => {
    if (containerRef.current) {
      const width = containerRef.current.clientWidth;
      setContainerWidth(width);
    }
  }, []);

  useEffect(() => {
    // 初始化获取宽度
    updateContainerWidth();

    // 创建 ResizeObserver 监听宽度变化
    const resizeObserver = new ResizeObserver(() => {
      updateContainerWidth();
    });

    if (containerRef.current) {
      resizeObserver.observe(containerRef.current);
    }
    if (headerRef.current) {
      resizeObserver.observe(headerRef.current);
    }

    // 监听窗口大小变化（兜底方案）
    const handleResize = () => {
      updateContainerWidth();
    };

    window.addEventListener('resize', handleResize);

    return () => {
      resizeObserver.disconnect();
      window.removeEventListener('resize', handleResize);
    };
  }, [updateContainerWidth]);

  // 计算每个 sticky 的位置
  const calculatePositions = useCallback(() => {
    if (!containerWidth || !stickies?.length) return [];

    const positions: Array<{ sticky: any; px: number; py: number }> = [];
    let px = 10;
    let py = 10;
    const rowHeight = 240; // 每行高度
    const margin = 10; // sticky 之间的间距

    stickies.forEach((sticky) => {
      const stickyWidth = sticky.width + margin;

      // 检查是否需要换行（预留一些边距）
      if (px + stickyWidth > containerWidth - 20 && px > 0) {
        px = 10;
        py += rowHeight;
      }

      positions.push({
        sticky,
        px,
        py,
      });

      px += stickyWidth;
    });

    return positions;
  }, [containerWidth, stickies]);

  const positions = calculatePositions();

  return (
    <div style={{height: height, position: 'relative'}}>
      {/* Header 区域 */}
      <div ref={headerRef} style={{position: 'relative', zIndex: 1}}>
        <hr className={styles.vertical} style={{height: height - 34}}/>
        <Header/>
        <hr className={styles.horizontal}/>
      </div>

      {/* 内容区域 */}
      <div
        ref={containerRef}
        style={{
          position: 'absolute',
          top: 35,
          left: 6,
          right: 0,
          bottom: 0,
          width: 'calc(100% - 10px)',
          overflowY: 'auto',
          scrollbarWidth: 'none',
          msOverflowStyle: 'none',
          opacity: isLoading ? 0.5 : 1,
          transition: 'opacity 0.2s ease-in-out',
        }}
        className={styles.hideScrollbar}
      >
        {!isLoading &&
          positions.map(({sticky, px, py}, index) => (
            <StickyNote
              key={sticky.id}
              stickyNoteInfo={sticky}
              px={px}
              py={py}
              initialZIndex={positions.length - index} // 先创建的 sticky 层级高
            />
          ))}
      </div>
    </div>
  );
}

export function StickyNotes({offsetHeight}: { offsetHeight: number }) {
  return (
    <StickyNoteProvider>
      <div style={{marginTop: 5}}>
        <StickyWrap offsetHeight={offsetHeight}/>
      </div>
    </StickyNoteProvider>
  );
}

export default function StickyPage() {
  return (
    <PageWrapper>
      <StickyNotes offsetHeight={45}/>
    </PageWrapper>
  )
}
