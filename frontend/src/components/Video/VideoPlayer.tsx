import {i18nText} from '@/utils/i18n';
import {forwardRef, type ReactNode, useCallback, useEffect, useMemo, useRef, useState,} from 'react';
import ReactPlayer from 'react-player';
import type {VideoPlayerError, VideoPlayerProps, VideoPlayerRef,} from '@/components';
import {resolveVideoSource} from '@/components';
import {cn} from '@/lib/utils';
import {BilibiliPlayer} from './BilibiliPlayer';
import './VideoPlayer.css';

const DEFAULT_ASPECT_RATIO = '16 / 9';

function DefaultLoadingFallback() {
  return (
    <div className="universal-video-player__status" role="status">
      <span className="universal-video-player__spinner" aria-hidden="true" />
      <span>{i18nText("app.common.video.videoplayer.dfa2c97a")}</span>
    </div>
  );
}

function DefaultErrorFallback({ error }: { error: VideoPlayerError }) {
  return (
    <div className="universal-video-player__status" role="alert">
      <span className="universal-video-player__error-icon" aria-hidden="true">
        !
      </span>
      <span>{error.message}</span>
    </div>
  );
}

function renderErrorFallback(
  fallback: VideoPlayerProps['errorFallback'],
  error: VideoPlayerError,
): ReactNode {
  if (typeof fallback === 'function') return fallback(error);
  if (fallback !== undefined) return fallback;
  return <DefaultErrorFallback error={error} />;
}

export const VideoPlayer = forwardRef<VideoPlayerRef, VideoPlayerProps>(
  function VideoPlayer(
    {
      src,
      title = i18nText("app.common.video.videoplayer.0b99a4e7"),
      poster,
      controls = true,
      autoPlay = false,
      playing,
      muted = false,
      loop = false,
      playsInline = true,
      preload = 'metadata',
      light = false,
      width = '100%',
      height,
      aspectRatio = DEFAULT_ASPECT_RATIO,
      className,
      style,
      playerClassName,
      playerStyle,
      bilibiliOptions,
      loadingFallback,
      errorFallback,
      onReady,
      onPlay,
      onPause,
      onEnded,
      onError,
      playerProps,
    },
    ref,
  ) {
    const source = useMemo(() => resolveVideoSource(src), [src]);
    const [playbackError, setPlaybackError] = useState<VideoPlayerError>();
    const reportedInvalidSource = useRef<string | undefined>(undefined);

    // ReactPlayer v3.4.0 没有把 poster 和 title 转发给实际媒体元素。
    // 在 ref 回调中补写属性，确保浏览器首次绘制时已经获得封面。
    const setPlayerRef = useCallback(
      (element: VideoPlayerRef | null) => {
        if (element) {
          if (poster) element.setAttribute('poster', poster);
          else element.removeAttribute('poster');
          element.setAttribute('title', title);
        }

        if (typeof ref === 'function') ref(element);
        else if (ref) ref.current = element;
      },
      [poster, ref, title],
    );

    useEffect(() => {
      setPlaybackError(undefined);
      reportedInvalidSource.current = undefined;
    }, [source.src]);

    const validationError = useMemo<VideoPlayerError | undefined>(() => {
      if (source.isValid) return undefined;
      return {
        kind: 'invalid-source',
        message: source.error ?? i18nText("app.common.video.videoplayer.3bd19798"),
        source,
      };
    }, [source]);

    useEffect(() => {
      if (
        validationError &&
        reportedInvalidSource.current !== validationError.source.src
      ) {
        reportedInvalidSource.current = validationError.source.src;
        onError?.(validationError);
      }
    }, [onError, validationError]);

    const visibleError = validationError ?? playbackError;
    const containerStyle = {
      width,
      ...(height === undefined
        ? { aspectRatio }
        : { height, aspectRatio: undefined }),
      ...style,
    };

    return (
      <div
        className={cn('universal-video-player', className)}
        style={containerStyle}
        data-video-provider={source.provider}
        data-video-recognized={source.isRecognized || undefined}
      >
        {!validationError && source.provider === 'bilibili' && (
          <BilibiliPlayer
            source={source}
            title={title}
            autoPlay={autoPlay}
            muted={muted}
            className={playerClassName}
            style={playerStyle}
            options={bilibiliOptions}
            loadingFallback={
              loadingFallback === undefined ? (
                <DefaultLoadingFallback />
              ) : (
                loadingFallback
              )
            }
            onReady={() => {
              setPlaybackError(undefined);
              onReady?.();
            }}
            onError={(error) => {
              setPlaybackError(error);
              onError?.(error);
            }}
          />
        )}

        {!validationError && source.provider !== 'bilibili' && (
          <ReactPlayer
            {...playerProps}
            key={source.src}
            ref={setPlayerRef}
            src={source.src}
            className={cn('universal-video-player__media', playerClassName)}
            style={{ width: '100%', height: '100%', ...playerStyle }}
            width="100%"
            height="100%"
            title={title}
            poster={poster}
            controls={controls}
            autoPlay={autoPlay}
            playing={playing}
            muted={muted}
            loop={loop}
            playsInline={playsInline}
            preload={preload}
            light={light}
            fallback={
              loadingFallback === undefined ? (
                <DefaultLoadingFallback />
              ) : (
                loadingFallback
              )
            }
            onReady={() => {
              setPlaybackError(undefined);
              onReady?.();
            }}
            onPlay={onPlay}
            onPause={onPause}
            onEnded={onEnded}
            onError={(cause) => {
              const error: VideoPlayerError = {
                kind: 'playback',
                message: i18nText("app.common.video.videoplayer.91b20a66"),
                source,
                cause,
              };
              setPlaybackError(error);
              onError?.(error);
            }}
          />
        )}

        {visibleError && (
          <div className="universal-video-player__overlay">
            {renderErrorFallback(errorFallback, visibleError)}
          </div>
        )}
      </div>
    );
  },
);

VideoPlayer.displayName = 'VideoPlayer';

export default VideoPlayer;
