import {useCallback, useLayoutEffect, useReducer, useRef} from 'react';

export type ChatRegion = 'list' | 'chat' | 'settings';
export type ChatLayoutMode = 'three' | 'two' | 'single';
export const SIDE_MIN = 300;
export const CHAT_MIN = 480;
export const layoutMode = (width: number): ChatLayoutMode => width >= 1080 ? 'three' : width >= 780 ? 'two' : 'single';

export interface ChatLayoutState {
  width: number;
  mode: ChatLayoutMode;
  left: number;
  right: number;
  leftOpen: boolean;
  rightOpen: boolean;
  region: ChatRegion;
  lastSide: 'list' | 'settings';
}

type Action = { type: 'measure'; width: number } | { type: 'open' | 'close'; region: ChatRegion }
  | { type: 'resize'; sizes: number[] };
const clamp = (value: number, max: number) => Math.max(SIDE_MIN, Math.min(value, max));

export function initialChatLayout(width = 1080): ChatLayoutState {
  const mode = layoutMode(width);
  return {
    width, mode, left: SIDE_MIN, right: SIDE_MIN, leftOpen: mode !== 'single', rightOpen: mode === 'three',
    region: 'chat', lastSide: 'list'
  };
}

function constrain(value: ChatLayoutState): ChatLayoutState {
  if (value.mode === 'single') return value;
  if (value.mode === 'two' && value.leftOpen && value.rightOpen) value = {
    ...value,
    leftOpen: value.lastSide === 'list', rightOpen: value.lastSide === 'settings'
  };
  const left = value.leftOpen ? clamp(value.left, Math.min(value.width / 2, value.width - CHAT_MIN - (value.rightOpen ? SIDE_MIN : 0))) : value.left;
  const right = value.rightOpen ? clamp(value.right, Math.min(value.width / 2, value.width - CHAT_MIN - (value.leftOpen ? left : 0))) : value.right;
  return {...value, left, right};
}

export function chatLayoutReducer(state: ChatLayoutState, action: Action): ChatLayoutState {
  if (action.type === 'measure') {
    if (!Number.isFinite(action.width) || action.width <= 0 || Math.abs(action.width - state.width) < 0.5) return state;
    const mode = layoutMode(action.width);
    if (mode === state.mode) return constrain({...state, width: action.width});
    return constrain({
      ...state, width: action.width, mode,
      leftOpen: mode === 'three' || (mode === 'two' && (state.mode === 'single' ? state.region !== 'settings' : state.lastSide !== 'settings')),
      rightOpen: mode === 'three' || (mode === 'two' && (state.mode === 'single' ? state.region === 'settings' : state.lastSide === 'settings')),
      region: mode === 'single' ? 'chat' : state.region
    });
  }
  if (action.type === 'resize') {
    if (state.mode === 'single' || action.sizes.length !== 3 || action.sizes.some(n => !Number.isFinite(n) || n < 0)) return state;
    const [left, , right] = action.sizes;
    const openingLeft = !state.leftOpen && left > 0;
    const openingRight = !state.rightOpen && right > 0;
    const lastSide = openingLeft && !openingRight ? 'list' : openingRight ? 'settings' : state.lastSide;
    return constrain({
      ...state, lastSide, left: left > 0 ? left : state.left, right: right > 0 ? right : state.right,
      leftOpen: left > 0, rightOpen: right > 0
    });
  }
  if (action.region === 'chat') return {...state, region: 'chat'};
  if (action.type === 'close') return {
    ...state, region: 'chat',
    ...(action.region === 'list' ? {leftOpen: false} : {rightOpen: false})
  };
  return constrain({
    ...state, region: action.region, lastSide: action.region,
    ...(action.region === 'list' ? {leftOpen: true, ...(state.mode === 'two' ? {rightOpen: false} : {})}
      : {rightOpen: true, ...(state.mode === 'two' ? {leftOpen: false} : {})})
  });
}

/** 容器宽度决定模式；仅页面内记忆尺寸，不读取或写入旧浏览器布局偏好。 */
export function useChatLayout() {
  const container = useRef<HTMLDivElement>(null);
  const [state, dispatch] = useReducer(chatLayoutReducer, undefined, () => initialChatLayout());
  useLayoutEffect(() => {
    const root = container.current;
    if (!root) return;
    const measure = (width: number) => dispatch({type: 'measure', width});
    measure(root.clientWidth);
    const observer = new ResizeObserver(entries => {
      const entry = entries.find(item => item.target === root);
      if (entry) measure(entry.contentRect.width);
    });
    observer.observe(root);
    return () => observer.disconnect();
  }, []);
  const visible = {
    list: state.mode === 'single' ? state.region === 'list' : state.leftOpen,
    chat: state.mode !== 'single' || state.region === 'chat',
    settings: state.mode === 'single' ? state.region === 'settings' : state.rightOpen,
  };
  const sizes = state.mode === 'single'
    ? [visible.list ? state.width : 0, visible.chat ? state.width : 0, visible.settings ? state.width : 0]
    : [visible.list ? state.left : 0, state.width - (visible.list ? state.left : 0) - (visible.settings ? state.right : 0), visible.settings ? state.right : 0];
  return {
    container, ...state, visible, sizes,
    open: useCallback((region: ChatRegion) => dispatch({type: 'open', region}), []),
    close: useCallback((region: ChatRegion) => dispatch({type: 'close', region}), []),
    resize: useCallback((next: number[]) => dispatch({type: 'resize', sizes: next}), []),
  };
}

export type ChatLayout = ReturnType<typeof useChatLayout>;
