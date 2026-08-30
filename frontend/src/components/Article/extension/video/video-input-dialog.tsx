import {Button, Checkbox, Input, InputNumber, Modal, Select, Typography,} from 'antd';
import {useEffect, useState} from 'react';
import {resolveVideoSource} from '@/components/Video';

export interface VideoAttributes {
  src: string;
  title: string;
  poster: string;
  controls: boolean;
  autoplay: boolean;
  loop: boolean;
  muted: boolean;
  playsInline: boolean;
  preload: 'auto' | 'metadata' | 'none';
  aspectRatio: string;
  widthPercent: number;
  bilibiliDanmaku: boolean;
  bilibiliPage: number | null;
  bilibiliStartTime: number | null;
  bilibiliShowPoster: boolean;
}

export const DEFAULT_VIDEO_ATTRIBUTES: VideoAttributes = {
  src: '',
  title: '视频播放器',
  poster: '',
  controls: true,
  autoplay: false,
  loop: false,
  muted: false,
  playsInline: true,
  preload: 'metadata',
  aspectRatio: '16 / 9',
  widthPercent: 100,
  bilibiliDanmaku: true,
  bilibiliPage: null,
  bilibiliStartTime: null,
  bilibiliShowPoster: true,
};

interface VideoInputDialogProps {
  value?: Partial<VideoAttributes>;
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (attributes: VideoAttributes) => void;
}

export function VideoInputDialog({
  value,
  isOpen,
  onOpenChange,
  onSubmit,
}: VideoInputDialogProps) {
  const [attributes, setAttributes] = useState<VideoAttributes>({
    ...DEFAULT_VIDEO_ATTRIBUTES,
    ...value,
  });
  const [error, setError] = useState<string>();

  useEffect(() => {
    if (!isOpen) return;

    setAttributes({ ...DEFAULT_VIDEO_ATTRIBUTES, ...value });
    setError(undefined);
  }, [isOpen, value]);

  const updateAttribute = <Key extends keyof VideoAttributes>(
    key: Key,
    nextValue: VideoAttributes[Key],
  ) => {
    setAttributes((current) => ({ ...current, [key]: nextValue }));
    if (key === 'src') setError(undefined);
  };

  const handleSubmit = () => {
    const src = attributes.src.trim();
    if (!src) {
      setError('请输入视频地址');
      return;
    }

    const source = resolveVideoSource(src);
    if (!source.isValid) {
      setError(source.error ?? '请输入有效的视频地址');
      return;
    }

    onSubmit({
      ...attributes,
      src,
      title: attributes.title.trim() || DEFAULT_VIDEO_ATTRIBUTES.title,
      poster: attributes.poster.trim(),
      widthPercent: Math.min(
        100,
        Math.max(
          1,
          attributes.widthPercent || DEFAULT_VIDEO_ATTRIBUTES.widthPercent,
        ),
      ),
    });
  };

  const fieldStyle = {
    display: 'flex',
    flexDirection: 'column' as const,
    gap: 6,
  };

  return (
    <Modal
      open={isOpen}
      title={value ? '修改视频' : '插入视频'}
      width={720}
      mask={{ closable: false }}
      onCancel={() => onOpenChange(false)}
      destroyOnHidden
      footer={
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={() => onOpenChange(false)}>取消</Button>
          <Button type="primary" onClick={handleSubmit}>
            {value ? '更新' : '插入'}
          </Button>
        </div>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <label htmlFor="video-src" style={fieldStyle}>
          <span>视频地址</span>
          <Input
            id="video-src"
            value={attributes.src}
            status={error ? 'error' : undefined}
            placeholder="MP4、HLS、DASH、YouTube、Vimeo、Bilibili 等链接"
            autoFocus
            onChange={(event) => updateAttribute('src', event.target.value)}
            onPressEnter={handleSubmit}
          />
          {error && <Typography.Text type="danger">{error}</Typography.Text>}
        </label>

        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
            gap: 16,
          }}
        >
          <label htmlFor="video-title" style={fieldStyle}>
            <span>播放器标题</span>
            <Input
              id="video-title"
              value={attributes.title}
              placeholder="视频播放器"
              onChange={(event) => updateAttribute('title', event.target.value)}
            />
          </label>

          <label htmlFor="video-poster" style={fieldStyle}>
            <span>封面地址</span>
            <Input
              id="video-poster"
              value={attributes.poster}
              placeholder="https://.../poster.jpg（可选）"
              onChange={(event) =>
                updateAttribute('poster', event.target.value)
              }
            />
          </label>

          <label htmlFor="video-aspect-ratio" style={fieldStyle}>
            <span>宽高比</span>
            <Select
              id="video-aspect-ratio"
              value={attributes.aspectRatio}
              options={[
                { value: '16 / 9', label: '16:9 - 横屏视频' },
                { value: '4 / 3', label: '4:3 - 传统视频' },
                { value: '1 / 1', label: '1:1 - 方形视频' },
                { value: '9 / 16', label: '9:16 - 竖屏视频' },
              ]}
              onChange={(nextValue) =>
                updateAttribute('aspectRatio', nextValue)
              }
            />
          </label>

          <label htmlFor="video-width-percent" style={fieldStyle}>
            <span>视频宽度</span>
            <InputNumber
              id="video-width-percent"
              aria-label="视频宽度"
              style={{ width: '100%' }}
              min={10}
              max={100}
              step={5}
              changeOnWheel
              precision={0}
              suffix="%"
              value={attributes.widthPercent}
              onChange={(nextValue) =>
                updateAttribute(
                  'widthPercent',
                  nextValue ?? DEFAULT_VIDEO_ATTRIBUTES.widthPercent,
                )
              }
            />
          </label>

          <label htmlFor="video-preload" style={fieldStyle}>
            <span>preload</span>
            <Select
              id="video-preload"
              value={attributes.preload}
              options={[
                { value: 'metadata', label: 'metadata - 预加载元数据' },
                { value: 'auto', label: 'auto - 自动预加载视频' },
                { value: 'none', label: 'none - 不预加载' },
              ]}
              onChange={(nextValue) => updateAttribute('preload', nextValue)}
            />
          </label>
        </div>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '10px 20px' }}>
          <Checkbox
            checked={attributes.controls}
            onChange={(event) =>
              updateAttribute('controls', event.target.checked)
            }
          >
            显示控制栏
          </Checkbox>
          <Checkbox
            checked={attributes.autoplay}
            onChange={(event) =>
              updateAttribute('autoplay', event.target.checked)
            }
          >
            自动播放
          </Checkbox>
          <Checkbox
            checked={attributes.loop}
            onChange={(event) => updateAttribute('loop', event.target.checked)}
          >
            循环播放
          </Checkbox>
          <Checkbox
            checked={attributes.muted}
            onChange={(event) => updateAttribute('muted', event.target.checked)}
          >
            静音
          </Checkbox>
          <Checkbox
            checked={attributes.playsInline}
            onChange={(event) =>
              updateAttribute('playsInline', event.target.checked)
            }
          >
            移动端行内播放
          </Checkbox>
        </div>

        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 12,
            padding: 12,
            border: '1px solid #d9d9d9',
            borderRadius: 6,
          }}
        >
          <Typography.Text strong>Bilibili 专属参数</Typography.Text>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
              gap: 16,
            }}
          >
            <label htmlFor="video-bilibili-page" style={fieldStyle}>
              <span>分 P</span>
              <InputNumber
                id="video-bilibili-page"
                style={{ width: '100%' }}
                min={1}
                precision={0}
                placeholder="使用链接中的分 P"
                value={attributes.bilibiliPage}
                onChange={(nextValue) =>
                  updateAttribute('bilibiliPage', nextValue)
                }
              />
            </label>

            <label htmlFor="video-bilibili-start-time" style={fieldStyle}>
              <span>起始时间</span>
              <InputNumber
                id="video-bilibili-start-time"
                style={{ width: '100%' }}
                min={0}
                precision={0}
                suffix="秒"
                placeholder="0"
                value={attributes.bilibiliStartTime}
                onChange={(nextValue) =>
                  updateAttribute('bilibiliStartTime', nextValue)
                }
              />
            </label>
          </div>

          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '10px 20px' }}>
            <Checkbox
              checked={attributes.bilibiliDanmaku}
              onChange={(event) =>
                updateAttribute('bilibiliDanmaku', event.target.checked)
              }
            >
              显示弹幕
            </Checkbox>
            <Checkbox
              checked={attributes.bilibiliShowPoster}
              onChange={(event) =>
                updateAttribute('bilibiliShowPoster', event.target.checked)
              }
            >
              显示 Bilibili 封面
            </Checkbox>
          </div>
        </div>
      </div>
    </Modal>
  );
}
