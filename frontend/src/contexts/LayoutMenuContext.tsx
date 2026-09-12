import type {Settings as LayoutSettings} from '@ant-design/pro-components';
import {useModel} from '@umijs/max';
import React, {createContext, useCallback, useContext, useMemo} from 'react';

export interface LayoutMenuState {
  visible: boolean;
  collapsed: boolean;
}

interface LayoutMenuContextValue extends LayoutMenuState {
  setMenuVisible: (visible: boolean) => void;
  setMenuCollapsed: (collapsed: boolean) => void;
  toggleMenu: () => void;
}

interface LayoutInitialState {
  settings?: Partial<LayoutSettings> & { collapsed?: boolean };
  menuVisible?: boolean;

  [key: string]: unknown;
}

export const LAYOUT_MENU_STORAGE_KEY = 'app-layout-menu-state';

const DEFAULT_LAYOUT_MENU_STATE: LayoutMenuState = {
  visible: true,
  collapsed: true,
};

const LayoutMenuContext = createContext<LayoutMenuContextValue | undefined>(
  undefined,
);

export function getStoredLayoutMenuState(): LayoutMenuState {
  if (typeof window === 'undefined') return DEFAULT_LAYOUT_MENU_STATE;

  try {
    const saved = JSON.parse(localStorage.getItem(LAYOUT_MENU_STORAGE_KEY) || 'null') as Partial<LayoutMenuState> | null;
    return {
      visible:
        typeof saved?.visible === 'boolean'
          ? saved.visible
          : DEFAULT_LAYOUT_MENU_STATE.visible,
      collapsed:
        typeof saved?.collapsed === 'boolean'
          ? saved.collapsed
          : DEFAULT_LAYOUT_MENU_STATE.collapsed,
    };
  } catch {
    return DEFAULT_LAYOUT_MENU_STATE;
  }
}

export function saveLayoutMenuState(state: LayoutMenuState) {
  try {
    localStorage.setItem(LAYOUT_MENU_STORAGE_KEY, JSON.stringify(state));
  } catch {
    // localStorage 不可用时仍保留当前会话中的菜单状态。
  }
}

export function LayoutMenuContextProvider({
                                            children,
                                          }: {
  children: React.ReactNode;
}) {
  const {initialState, setInitialState} = useModel('@@initialState');
  const appInitialState = initialState as LayoutInitialState | undefined;
  const visible = appInitialState?.menuVisible !== false;
  const collapsed = Boolean(appInitialState?.settings?.collapsed);

  const updateMenuState = useCallback(
    (nextState: LayoutMenuState) => {
      saveLayoutMenuState(nextState);
      setInitialState({
        ...appInitialState,
        menuVisible: nextState.visible,
        settings: {
          ...appInitialState?.settings,
          collapsed: nextState.collapsed,
        } as Partial<LayoutSettings>,
      });
    },
    [appInitialState, setInitialState],
  );

  const setMenuVisible = useCallback(
    (nextVisible: boolean) => {
      updateMenuState({
        visible: nextVisible,
        // 菜单重新显示时始终展开完整菜单名。
        collapsed: nextVisible ? false : collapsed,
      });
    },
    [collapsed, updateMenuState],
  );

  const setMenuCollapsed = useCallback(
    (nextCollapsed: boolean) => {
      updateMenuState({visible, collapsed: nextCollapsed});
    },
    [updateMenuState, visible],
  );

  const toggleMenu = useCallback(() => {
    setMenuVisible(!visible);
  }, [setMenuVisible, visible]);

  const value = useMemo(
    () => ({
      visible,
      collapsed,
      setMenuVisible,
      setMenuCollapsed,
      toggleMenu,
    }),
    [collapsed, setMenuCollapsed, setMenuVisible, toggleMenu, visible],
  );

  return (
    <LayoutMenuContext.Provider value={value}>
      {children}
    </LayoutMenuContext.Provider>
  );
}

export function useLayoutMenu() {
  const context = useContext(LayoutMenuContext);
  if (!context) {
    throw new Error(
      'useLayoutMenu must be used within LayoutMenuContextProvider',
    );
  }
  return context;
}
