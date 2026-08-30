/**
 * 视频播放组件
 * 使用示例：
 * <VideoPlayer
 *         src="https://vjs.zencdn.net/v/oceans.mp4"
 *         poster="https://picsum.photos/800/600"
 *         controls
 *         aspectRatio="16 / 9"
 *       />
 * 公用测试地址：
 * MP4: https://vjs.zencdn.net/v/oceans.mp4
 * WebM: https://media.w3.org/2010/05/sintel/trailer.webm
 * MOV: https://filesamples.com/samples/video/mov/sample_640x360.mov
 * M4V: https://fcit.usf.edu/matrix/wp-content/uploads/2017/11/sample.m4v
 * HLS: https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
 * HLS 单清晰度: https://test-streams.mux.dev/x36xhzz/url_6/193039199_mp4_h264_aac_hq_7.m3u8
 * DASH: https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd
 * DASH 直播: https://livesim2.dashif.org/livesim2/scte35_2/testpic_2s/Manifest.mpd
 * YouTube: https://www.youtube.com/watch?v=LXb3EKWsInQ
 * YouTube 短链: https://youtu.be/LXb3EKWsInQ
 * YouTube 嵌入: https://www.youtube.com/embed/LXb3EKWsInQ
 * Vimeo: https://vimeo.com/76979871
 * Vimeo 嵌入: https://player.vimeo.com/video/76979871
 * Vimeo 带 Hash: https://player.vimeo.com/video/76979871?h=8272103f6e
 * Mux: https://stream.mux.com/maVbJv2GSYNRgS02kPXOOGdJMWGU1mkA019ZUjYE7VU7k
 * Wistia: https://home.wistia.com/medias/e4a27b971d
 * Wistia 嵌入: https://fast.wistia.net/embed/iframe/e4a27b971d
 * Twitch: https://www.twitch.tv/twitch
 * Twitch 频道: https://www.twitch.tv/bethesda
 * TikTok: https://www.tiktok.com/@scout2015/video/6718335390845095173
 * Bilibili: https://www.bilibili.com/video/BV1B7411m7LV
 * Bilibili 多分 P: https://www.bilibili.com/video/BV1B7411m7LV?p=2
 *
 * 不支持：OGG、OGV
 */
export {
  type ResolvedVideoSource,
  resolveVideoSource,
  type VideoProvider,
} from './resolveVideoSource';
export { default, VideoPlayer } from './VideoPlayer';
export type {
  BilibiliPlayerOptions,
  VideoPlayerAdvancedProps,
  VideoPlayerError,
  VideoPlayerErrorKind,
  VideoPlayerProps,
  VideoPlayerRef,
} from './VideoPlayer.types';
