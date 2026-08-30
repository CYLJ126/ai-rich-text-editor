import {useEffect, useState} from 'react';

/**
 * 动态计算组件高度
 * @param fixedOffset 固定要减去的高度
 * @param minHeight 最小高度
 * @returns 组件高度
 */
export const useComponentHeight = (
  fixedOffset: number = 80,
  minHeight: number = 300,
) => {
  const [componentHeight, setComponentHeight] = useState(window.innerHeight);

  const calculateHeight = () => {
    const windowHeight = window.innerHeight;
    const newHeight = windowHeight - fixedOffset;
    setComponentHeight(Math.max(newHeight, minHeight));
  };

  useEffect(() => {
    calculateHeight();

    const handleResize = () => {
      calculateHeight();
    };

    window.addEventListener('resize', handleResize);

    const resizeObserver = new ResizeObserver(() => {
      calculateHeight();
    });

    return () => {
      window.removeEventListener('resize', handleResize);
      resizeObserver.disconnect();
    };
  }, [fixedOffset, minHeight]);

  return componentHeight;
};
