// src/hooks/useTabTitle.ts
import { useEffect } from 'react';
import { useLocation } from '@umijs/max';

export const useTabTitle = (title: string) => {
  const location = useLocation();

  useEffect(() => {
    // 这里可以通过事件或状态管理来通知标签栏更新标题
    const event = new CustomEvent('updateTabTitle', {
      detail: {
        path: location.pathname,
        search: location.search,
        title,
      },
    });
    window.dispatchEvent(event);
  }, [title, location]);
};
