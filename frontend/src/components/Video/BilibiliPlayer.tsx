import {type CSSProperties, type ReactNode, useMemo, useState} from 'react';
import {cn} from '@/lib/utils';
import type {ResolvedVideoSource} from './resolveVideoSource';
import type {BilibiliPlayerOptions, VideoPlayerError, VideoPlayerProps,} from './VideoPlayer.types';

interface BilibiliPlayerProps {
  source: ResolvedVideoSource;
  title: string;
  autoPlay: boolean;
  muted: boolean;
  className?: string;
  style?: CSSProperties;
  options?: BilibiliPlayerOptions;
  loadingFallback?: ReactNode;
  onReady?: VideoPlayerProps['onReady'];
  onError: (error: VideoPlayerError) => void;
}

function setBooleanSearchParam(
  url: URL,
  name: string,
  value: boolean | undefined,
) {
  if (value !== undefined) url.searchParams.set(name, value ? '1' : '0');
}

function buildBilibiliPlayerUrl(
  source: ResolvedVideoSource,
  autoPlay: boolean,
  muted: boolean,
  options?: BilibiliPlayerOptions,
) {
  const url = new URL(source.src);
  setBooleanSearchParam(url, 'autoplay', autoPlay);
  setBooleanSearchParam(url, 'muted', muted);
  setBooleanSearchParam(url, 'danmaku', options?.danmaku);
  setBooleanSearchParam(url, 'poster', options?.showPoster);
  setBooleanSearchParam(url, 'refer', options?.refer);

  if (options?.page !== undefined) {
    url.searchParams.set('p', String(options.page));
  }
  if (options?.startTime !== undefined) {
    url.searchParams.set('t', String(options.startTime));
  }

  return url.toString();
}

export function BilibiliPlayer({
  source,
  title,
  autoPlay,
  muted,
  className,
  style,
  options,
  loadingFallback,
  onReady,
  onError,
}: BilibiliPlayerProps) {
  const src = useMemo(
    () => buildBilibiliPlayerUrl(source, autoPlay, muted, options),
    [autoPlay, muted, options, source],
  );
  const [loadedSrc, setLoadedSrc] = useState<string>();
  const isLoading = loadedSrc !== src;

  return (
    <>
      <iframe
        key={src}
        src={src}
        title={title}
        className={cn('universal-video-player__media', className)}
        style={{ width: '100%', height: '100%', ...style }}
        allow="autoplay; fullscreen; picture-in-picture"
        allowFullScreen
        sandbox="allow-scripts allow-same-origin allow-forms allow-presentation"
        referrerPolicy="strict-origin-when-cross-origin"
        onLoad={() => {
          setLoadedSrc(src);
          onReady?.();
        }}
        onError={(cause) => {
          setLoadedSrc(src);
          onError({
            kind: 'playback',
            message: 'Bilibili 视频加载失败，请检查地址和访问权限',
            source,
            cause,
          });
        }}
      />

      {isLoading && loadingFallback !== false && (
        <div className="universal-video-player__overlay">{loadingFallback}</div>
      )}
    </>
  );
}

export default BilibiliPlayer;
