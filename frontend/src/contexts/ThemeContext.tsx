import React, {createContext, useCallback, useContext, useEffect, useState,} from 'react';

export type ThemeAppearance = 'light' | 'dark';

export interface ThemeToken {
  colorPrimary?: string;
  borderRadius?: number;
}

interface ThemeContextValue {
  appearance: ThemeAppearance;
  isDark: boolean;
  themeToken: ThemeToken;
  toggleAppearance: () => void;
  setAppearanceMode: (mode: ThemeAppearance) => void;
  updateThemeToken: (token: Partial<ThemeToken>) => void;
}

const THEME_STORAGE_KEY = 'app-theme-appearance';

const ThemeContext = createContext<ThemeContextValue>({
  appearance: 'light',
  isDark: false,
  themeToken: {colorPrimary: '#1677ff', borderRadius: 6},
  toggleAppearance: () => {
  },
  setAppearanceMode: () => {
  },
  updateThemeToken: () => {
  },
});

function getInitialAppearance(): ThemeAppearance {
  try {
    const saved = localStorage.getItem(THEME_STORAGE_KEY);
    if (saved === 'dark' || saved === 'light') return saved;
    return window.matchMedia('(prefers-color-scheme: dark)').matches
      ? 'dark'
      : 'light';
  } catch {
    return 'light';
  }
}

export function ThemeContextProvider({
                                       children,
                                     }: {
  children: React.ReactNode;
}) {
  const [appearance, setAppearance] = useState<ThemeAppearance>(
    getInitialAppearance,
  );
  const [themeToken, setThemeToken] = useState<ThemeToken>({
    colorPrimary: '#1677ff',
    borderRadius: 6,
  });

  const isDark = appearance === 'dark';

  // 同步 dark class 到 html
  useEffect(() => {
    document.documentElement.classList.toggle('dark', isDark);
  }, [isDark]);

  // 监听系统主题变化
  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const handleChange = (e: MediaQueryListEvent) => {
      // 仅在用户未手动设置时跟随系统
      if (!localStorage.getItem(THEME_STORAGE_KEY)) {
        setAppearance(e.matches ? 'dark' : 'light');
      }
    };
    media.addEventListener('change', handleChange);
    return () => media.removeEventListener('change', handleChange);
  }, []);

  const toggleAppearance = useCallback(() => {
    setAppearance((prev) => {
      const next = prev === 'dark' ? 'light' : 'dark';
      localStorage.setItem(THEME_STORAGE_KEY, next);
      return next;
    });
  }, []);

  const setAppearanceMode = useCallback((mode: ThemeAppearance) => {
    setAppearance(mode);
    localStorage.setItem(THEME_STORAGE_KEY, mode);
  }, []);

  const updateThemeToken = useCallback((token: Partial<ThemeToken>) => {
    setThemeToken((prev) => ({...prev, ...token}));
  }, []);

  return (
    <ThemeContext.Provider
      value={{
        appearance,
        isDark,
        themeToken,
        toggleAppearance,
        setAppearanceMode,
        updateThemeToken,
      }}
    >
      {children}
    </ThemeContext.Provider>
  );
}

// 供组件使用的 Hook
export function useThemeContext() {
  return useContext(ThemeContext);
}
