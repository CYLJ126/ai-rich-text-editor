import {describe, expect, it} from 'vitest';
import {resolveVideoSource} from './resolveVideoSource';

describe('resolveVideoSource', () => {
  it.each([
    ['https://cdn.example.com/video.mp4?token=1', 'file'],
    ['https://cdn.example.com/live.m3u8', 'hls'],
    ['https://cdn.example.com/manifest.mpd', 'dash'],
    ['https://www.youtube.com/watch?v=LXb3EKWsInQ', 'youtube'],
    ['https://youtu.be/LXb3EKWsInQ', 'youtube'],
    ['https://vimeo.com/123456', 'vimeo'],
    ['https://stream.mux.com/example', 'mux'],
    ['https://www.twitch.tv/videos/123456', 'twitch'],
  ] as const)('identifies %s as %s', (src, provider) => {
    expect(resolveVideoSource(src)).toMatchObject({
      src,
      provider,
      isValid: true,
      isRecognized: true,
    });
  });

  it('keeps extensionless signed URLs available for the native fallback', () => {
    expect(
      resolveVideoSource('https://cdn.example.com/play?id=1'),
    ).toMatchObject({
      provider: 'unknown',
      isValid: true,
      isRecognized: false,
    });
  });

  it('supports relative file URLs', () => {
    expect(resolveVideoSource('/media/example.webm')).toMatchObject({
      provider: 'file',
      isValid: true,
    });
  });

  it.each([
    [
      'https://www.bilibili.com/video/BV1B7411m7LV?p=2&t=30',
      'https://player.bilibili.com/player.html?bvid=BV1B7411m7LV&p=2&t=30',
    ],
    [
      'https://m.bilibili.com/video/av170001',
      'https://player.bilibili.com/player.html?aid=170001',
    ],
    [
      'https://player.bilibili.com/player.html?bvid=BV1B7411m7LV&p=3&autoplay=1&unsafe=value',
      'https://player.bilibili.com/player.html?bvid=BV1B7411m7LV&p=3&autoplay=1',
    ],
  ])('normalizes Bilibili URL to the official player: %s', (src, expected) => {
    expect(resolveVideoSource(src)).toMatchObject({
      src: expected,
      provider: 'bilibili',
      isValid: true,
      isRecognized: true,
    });
  });

  it.each([
    'https://fast.wistia.net/embed/iframe/e4a27b971d',
    'https://fast.wistia.com/embed/iframe/e4a27b971d',
    'https://home.wistia.com/medias/e4a27b971d?autoplay=1',
  ])('normalizes Wistia URL for wistia-video-element: %s', (src) => {
    expect(resolveVideoSource(src)).toMatchObject({
      src: 'https://home.wistia.com/medias/e4a27b971d',
      provider: 'wistia',
      isValid: true,
      isRecognized: true,
    });
  });

  it.each(['', '   ', 'javascript:alert(1)', 'ftp://example.com/video.mp4'])(
    'rejects invalid or unsafe source: %s',
    (src) => {
      expect(resolveVideoSource(src).isValid).toBe(false);
    },
  );
});
