import React, {useCallback, useEffect, useRef,} from 'react';
import styles from './index.less';

export type Direction = 'horizontal' | 'vertical';

export interface DraggableLineProps {
  /**
   * horizontal：鼠标横向移动，改变宽度，显示为竖线
   * vertical：鼠标纵向移动，改变高度，显示为横线
   */
  direction?: Direction;
  /** 当前尺寸 */
  size: number;
  /** 尺寸变化回调 */
  onSizeChange: (size: number) => void;
  /** 最小尺寸 */
  minSize?: number;
  /** 最大尺寸 */
  maxSize?: number;
  /** 自定义线条类名 */
  lineClassName?: string;
  /** 自定义容器类名 */
  className?: string;
  /** 是否显示拖拽图标 */
  showDragIcon?: boolean;
}

const DraggableLine: React.FC<DraggableLineProps> = ({
                                                       direction = 'vertical',
                                                       size,
                                                       onSizeChange,
                                                       minSize = 100,
                                                       maxSize = Infinity,
                                                       lineClassName,
                                                       className,
                                                       showDragIcon = true,
                                                     }) => {
  const isDraggingRef = useRef(false);
  const pointerIdRef = useRef<number | null>(null);
  const startPositionRef = useRef(0);
  const startSizeRef = useRef(0);

  const previousBodyUserSelectRef = useRef('');
  const previousBodyWebkitUserSelectRef = useRef('');

  const isVertical = direction === 'vertical';

  /**
   * 恢复 body 样式。
   *
   * 这里只处理 user-select，不再设置 body.cursor，
   * 避免整个页面一直显示 resize 光标。
   */
  const restoreBodyStyle = useCallback(() => {
    document.body.style.userSelect =
      previousBodyUserSelectRef.current;

    document.body.style.webkitUserSelect =
      previousBodyWebkitUserSelectRef.current;
  }, []);

  const finishDragging = useCallback(() => {
    if (!isDraggingRef.current) {
      return;
    }

    isDraggingRef.current = false;
    pointerIdRef.current = null;

    restoreBodyStyle();
  }, [restoreBodyStyle]);

  const handlePointerDown = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      // 鼠标只响应左键
      if (event.pointerType === 'mouse' && event.button !== 0) {
        return;
      }

      event.preventDefault();
      event.stopPropagation();

      isDraggingRef.current = true;
      pointerIdRef.current = event.pointerId;
      startPositionRef.current = isVertical
        ? event.clientY
        : event.clientX;
      startSizeRef.current = size;

      // 捕获指针，避免鼠标离开拖动线后收不到事件
      event.currentTarget.setPointerCapture(event.pointerId);

      previousBodyUserSelectRef.current =
        document.body.style.userSelect;

      previousBodyWebkitUserSelectRef.current =
        document.body.style.webkitUserSelect;

      document.body.style.userSelect = 'none';
      document.body.style.webkitUserSelect = 'none';

      // 不要在这里设置 document.body.style.cursor
    },
    [isVertical, size],
  );

  const handlePointerMove = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      if (
        !isDraggingRef.current ||
        pointerIdRef.current !== event.pointerId
      ) {
        return;
      }

      event.preventDefault();

      const currentPosition = isVertical
        ? event.clientY
        : event.clientX;

      const delta =
        currentPosition - startPositionRef.current;

      const nextSize = Math.min(
        maxSize,
        Math.max(
          minSize,
          startSizeRef.current + delta,
        ),
      );

      onSizeChange(Math.round(nextSize));
    },
    [
      isVertical,
      minSize,
      maxSize,
      onSizeChange,
    ],
  );

  const handlePointerUp = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      if (
        pointerIdRef.current !== null &&
        pointerIdRef.current !== event.pointerId
      ) {
        return;
      }

      if (
        event.currentTarget.hasPointerCapture(
          event.pointerId,
        )
      ) {
        event.currentTarget.releasePointerCapture(
          event.pointerId,
        );
      }

      finishDragging();
    },
    [finishDragging],
  );

  const handlePointerCancel = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      if (
        event.currentTarget.hasPointerCapture(
          event.pointerId,
        )
      ) {
        event.currentTarget.releasePointerCapture(
          event.pointerId,
        );
      }

      finishDragging();
    },
    [finishDragging],
  );

  const handleLostPointerCapture = useCallback(() => {
    finishDragging();
  }, [finishDragging]);

  /**
   * 处理窗口失焦、切换标签页、组件卸载等情况，
   * 避免 user-select 残留。
   */
  useEffect(() => {
    const handleWindowBlur = () => {
      finishDragging();
    };

    const handleVisibilityChange = () => {
      if (document.hidden) {
        finishDragging();
      }
    };

    window.addEventListener('blur', handleWindowBlur);
    document.addEventListener(
      'visibilitychange',
      handleVisibilityChange,
    );

    return () => {
      window.removeEventListener('blur', handleWindowBlur);
      document.removeEventListener(
        'visibilitychange',
        handleVisibilityChange,
      );

      finishDragging();
    };
  }, [finishDragging]);

  return (
    <div
      className={[
        styles.container,
        isVertical
          ? styles.vertical
          : styles.horizontal,
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      role="separator"
      aria-orientation={
        isVertical ? 'horizontal' : 'vertical'
      }
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerCancel}
      onLostPointerCapture={handleLostPointerCapture}
    >
      <hr
        className={[
          isVertical
            ? styles.lineVertical
            : styles.lineHorizontal,
          lineClassName,
        ]
          .filter(Boolean)
          .join(' ')}
      />

      {showDragIcon && (
        <span className={styles.dragIcon}>
          {isVertical ? '⋮' : '⠿'}
        </span>
      )}
    </div>
  );
};

export default DraggableLine;
