import React, {useLayoutEffect, useRef} from 'react';
import './style.css';

/** 填满应用导航下的可用视口，内容滚动交给内部面板。 */
export default function ViewportPage({children, className = '', label}: {
  children: React.ReactNode; className?: string; label: string;
}) {
  const ref = useRef<HTMLElement>(null);
  useLayoutEffect(() => {
    const update = () => {
      if (!ref.current || !ref.current.getClientRects().length) return;
      const top = Math.max(0, ref.current.getBoundingClientRect().top);
      ref.current.style.height = `calc(100dvh - ${top}px)`;
    };
    update();
    const observer = new ResizeObserver(update);
    if (ref.current?.parentElement) observer.observe(ref.current.parentElement);
    window.addEventListener('resize', update);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', update);
    };
  }, []);
  return <main ref={ref} className={`viewport-page ${className}`} aria-label={label}>{children}</main>;
}
