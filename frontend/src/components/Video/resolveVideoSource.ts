export type VideoProvider =
  | 'file'
  | 'hls'
  | 'dash'
  | 'mux'
  | 'bilibili'
  | 'youtube'
  | 'vimeo'
  | 'wistia'
  | 'twitch'
  | 'tiktok'
  | 'unknown';

export interface ResolvedVideoSource {
  /** 去除首尾空白后的地址。 */
  src: string;
  provider: VideoProvider;
  /** 地址是否非空且未使用危险或不支持的协议。 */
  isValid: boolean;
  /** 是否匹配已知平台、流媒体清单或常见视频扩展名。 */
  isRecognized: boolean;
  error?: string;
}

const ABSOLUTE_PROTOCOL_PATTERN = /^([a-z][a-z\d+.-]*):/i;
const ALLOWED_PROTOCOLS = new Set(['http:', 'https:', 'blob:']);
const VIDEO_FILE_PATTERN = /\.(?:mp4|m4v|mov|ogv|ogg|webm)(?:$|[?#])/i;
const HLS_PATTERN = /\.m3u8(?:$|[?#])/i;
const DASH_PATTERN = /\.mpd(?:$|[?#])/i;
const BILIBILI_BVID_PATTERN = /^BV[a-z\d]{10}$/i;
const BILIBILI_AID_PATTERN = /^av(\d+)$/i;

function isDomain(hostname: string, domain: string): boolean {
  return hostname === domain || hostname.endsWith(`.${domain}`);
}

function getHostname(src: string): string {
  try {
    return new URL(src, 'https://video.local').hostname.toLowerCase();
  } catch {
    return '';
  }
}

function getWistiaMediaId(src: string): string | undefined {
  try {
    const url = new URL(src, 'https://video.local');
    const hostname = url.hostname.toLowerCase();
    if (
      !isDomain(hostname, 'wistia.com') &&
      !isDomain(hostname, 'wistia.net') &&
      !isDomain(hostname, 'wi.st')
    ) {
      return undefined;
    }

    return (
      url.pathname.match(/^\/medias\/([a-z\d_-]+)/i)?.[1] ??
      url.pathname.match(/^\/embed\/(?:iframe\/)?([a-z\d_-]+)/i)?.[1]
    );
  } catch {
    return undefined;
  }
}

function getBilibiliEmbedUrl(src: string): string | undefined {
  try {
    const url = new URL(src, 'https://video.local');
    const hostname = url.hostname.toLowerCase();
    if (!isDomain(hostname, 'bilibili.com')) return undefined;

    const embedUrl = new URL('https://player.bilibili.com/player.html');
    const videoId = url.pathname.match(/^\/video\/([^/?#]+)/i)?.[1];
    const bvid = videoId?.match(BILIBILI_BVID_PATTERN)?.[0];
    const aid = videoId?.match(BILIBILI_AID_PATTERN)?.[1];

    if (bvid) embedUrl.searchParams.set('bvid', bvid);
    else if (aid) embedUrl.searchParams.set('aid', aid);
    else if (
      hostname === 'player.bilibili.com' &&
      url.pathname === '/player.html'
    ) {
      const supportedParams = [
        'bvid',
        'aid',
        'cid',
        'seasonId',
        'episodeId',
        'p',
        't',
        'autoplay',
        'muted',
        'danmaku',
        'poster',
        'refer',
      ];
      for (const param of supportedParams) {
        const value = url.searchParams.get(param);
        if (value !== null) embedUrl.searchParams.set(param, value);
      }
    } else {
      return undefined;
    }

    if (url.searchParams.has('p')) {
      embedUrl.searchParams.set('p', url.searchParams.get('p') ?? '1');
    }
    if (url.searchParams.has('t')) {
      embedUrl.searchParams.set('t', url.searchParams.get('t') ?? '0');
    }

    const hasVideoIdentifier = [
      'bvid',
      'aid',
      'cid',
      'seasonId',
      'episodeId',
    ].some((param) => embedUrl.searchParams.has(param));

    return hasVideoIdentifier ? embedUrl.toString() : undefined;
  } catch {
    return undefined;
  }
}

function detectProvider(src: string): VideoProvider {
  if (HLS_PATTERN.test(src)) return 'hls';
  if (DASH_PATTERN.test(src)) return 'dash';
  if (VIDEO_FILE_PATTERN.test(src)) return 'file';

  const hostname = getHostname(src);
  if (getBilibiliEmbedUrl(src)) return 'bilibili';
  if (
    isDomain(hostname, 'youtube.com') ||
    isDomain(hostname, 'youtube-nocookie.com') ||
    isDomain(hostname, 'youtu.be')
  ) {
    return 'youtube';
  }
  if (isDomain(hostname, 'vimeo.com')) return 'vimeo';
  if (getWistiaMediaId(src)) return 'wistia';
  if (isDomain(hostname, 'twitch.tv')) return 'twitch';
  if (isDomain(hostname, 'tiktok.com')) return 'tiktok';
  if (hostname === 'stream.mux.com') return 'mux';

  return 'unknown';
}

/**
 * 对视频地址做轻量、无网络请求的识别。
 *
 * unknown 不等同于无法播放：ReactPlayer 会用原生媒体元素继续尝试，
 * 这可以兼容没有文件扩展名的签名 URL。平台白名单应由业务层另行校验。
 */
export function resolveVideoSource(value: string): ResolvedVideoSource {
  const src = value.trim();
  if (!src) {
    return {
      src,
      provider: 'unknown',
      isValid: false,
      isRecognized: false,
      error: '未提供视频地址',
    };
  }

  const protocolMatch = src.match(ABSOLUTE_PROTOCOL_PATTERN);
  const protocol = protocolMatch?.[1]?.toLowerCase();
  if (protocol && !ALLOWED_PROTOCOLS.has(`${protocol}:`)) {
    return {
      src,
      provider: 'unknown',
      isValid: false,
      isRecognized: false,
      error: `不支持 ${protocol}: 协议的视频地址`,
    };
  }

  const provider = detectProvider(src);
  const normalizedSrc =
    provider === 'wistia'
      ? `https://home.wistia.com/medias/${getWistiaMediaId(src)}`
      : provider === 'bilibili'
        ? (getBilibiliEmbedUrl(src) ?? src)
        : src;
  return {
    src: normalizedSrc,
    provider,
    isValid: true,
    isRecognized: provider !== 'unknown',
  };
}
