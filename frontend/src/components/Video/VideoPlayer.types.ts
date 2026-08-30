import type {ComponentProps, CSSProperties, ReactNode} from 'react';
import type ReactPlayer from 'react-player';
import type {ResolvedVideoSource} from './resolveVideoSource';

type ReactPlayerProps = ComponentProps<typeof ReactPlayer>;

type ManagedReactPlayerProps =
  | 'src'
  | 'width'
  | 'height'
  | 'style'
  | 'className'
  | 'controls'
  | 'autoPlay'
  | 'muted'
  | 'loop'
  | 'playsInline'
  | 'preload'
  | 'poster'
  | 'playing'
  | 'light'
  | 'fallback'
  | 'onReady'
  | 'onPlay'
  | 'onPause'
  | 'onEnded'
  | 'onError';

/** 低频或平台专属参数，可直接透传给 ReactPlayer。 */
export type VideoPlayerAdvancedProps = Omit<
  ReactPlayerProps,
  ManagedReactPlayerProps
>;

export type VideoPlayerErrorKind = 'invalid-source' | 'playback';

export interface VideoPlayerError {
  kind: VideoPlayerErrorKind;
  message: string;
  source: ResolvedVideoSource;
  cause?: unknown;
}

export interface BilibiliPlayerOptions {
  /** 是否显示弹幕；不传时使用 Bilibili 播放器默认设置。 */
  danmaku?: boolean;
  /** 多分 P 视频的集数，从 1 开始；会覆盖链接中的 p 参数。 */
  page?: number;
  /** 视频开始播放的时间，单位为秒；会覆盖链接中的 t 参数。 */
  startTime?: number;
  /** 是否显示 Bilibili 自带封面；不能用于设置自定义封面地址。 */
  showPoster?: boolean;
  /** 是否显示来源信息；不传时使用 Bilibili 播放器默认设置。 */
  refer?: boolean;
}

export interface VideoPlayerProps {
  /** 视频文件、流媒体清单、Bilibili 或受 ReactPlayer 支持的平台链接。 */
  src: string;
  /** 播放器标题，用于媒体元素的 title 属性；默认“视频播放器”。 */
  title?: string;
  /** 视频播放前显示的封面图片地址；第三方平台是否采用取决于平台播放器。 */
  poster?: string;
  /** 是否显示播放器控制栏；默认 true，Bilibili 始终使用平台自己的控制栏。 */
  controls?: boolean;
  /** 是否在媒体加载后自动播放；默认 false，浏览器通常只允许静音自动播放。 */
  autoPlay?: boolean;
  /** 受控播放状态：true 播放、false 暂停；Bilibili 官方 iframe 不支持此项。 */
  playing?: boolean;
  /** 是否静音；默认 false。 */
  muted?: boolean;
  /** 是否循环播放；默认 false，Bilibili 官方 iframe 不支持此项。 */
  loop?: boolean;
  /** 是否在移动端页面内播放，而不是自动进入全屏；默认 true。 */
  playsInline?: boolean;
  /** 原生媒体预加载策略；默认 metadata，第三方平台可能忽略。 */
  preload?: ReactPlayerProps['preload'];
  /** 是否启用轻量预览模式，也可直接传入预览图片地址或 React 元素。 */
  light?: ReactPlayerProps['light'];
  /** 播放器容器宽度；默认 100%。 */
  width?: CSSProperties['width'];
  /** 播放器容器固定高度；传入后 aspectRatio 不再参与高度计算。 */
  height?: CSSProperties['height'];
  /** 播放器宽高比；默认 16 / 9，仅在未指定 height 时生效。 */
  aspectRatio?: CSSProperties['aspectRatio'];
  /** 播放器外层容器的类名。 */
  className?: string;
  /** 播放器外层容器的内联样式，可覆盖 width、height 或 aspectRatio。 */
  style?: CSSProperties;
  /** ReactPlayer 实际媒体元素的类名。 */
  playerClassName?: string;
  /** ReactPlayer 实际媒体元素的内联样式。 */
  playerStyle?: CSSProperties;
  /** Bilibili 官方嵌入播放器的专属参数，仅对 Bilibili 地址生效。 */
  bilibiliOptions?: BilibiliPlayerOptions;
  /** ReactPlayer 异步加载实际播放器时显示的内容。传 false 可禁用 Suspense。 */
  loadingFallback?: ReactPlayerProps['fallback'];
  /** 播放失败时的自定义占位；函数形式可以根据错误渲染。 */
  errorFallback?: ReactNode | ((error: VideoPlayerError) => ReactNode);
  /** 媒体准备完成时触发；Bilibili 在官方 iframe 页面加载完成时触发。 */
  onReady?: ReactPlayerProps['onReady'];
  /** 媒体开始或恢复播放时触发；Bilibili 官方 iframe 不支持此回调。 */
  onPlay?: ReactPlayerProps['onPlay'];
  /** 媒体暂停时触发；Bilibili 官方 iframe 不支持此回调。 */
  onPause?: ReactPlayerProps['onPause'];
  /** 媒体播放结束时触发；Bilibili 官方 iframe 不支持此回调。 */
  onEnded?: ReactPlayerProps['onEnded'];
  /** 地址校验失败或媒体加载、播放失败时触发。 */
  onError?: (error: VideoPlayerError) => void;
  /** 不常用的 ReactPlayer v3 参数。受组件管理的关键参数不能在此覆盖。 */
  playerProps?: VideoPlayerAdvancedProps;
}

export type VideoPlayerRef = HTMLVideoElement;
