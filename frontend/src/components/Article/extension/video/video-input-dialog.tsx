import {i18nText} from '@/utils/i18n';
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
  title: i18nText("app.article.video.videoinputdialog.bad440e7"),
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
      setError(i18nText("app.article.video.videoinputdialog.b27629e5"));
      return;
    }

    const source = resolveVideoSource(src);
    if (!source.isValid) {
      setError(source.error ?? i18nText("app.article.video.videoinputdialog.48235d52"));
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
      title={value ? i18nText("app.article.video.videoinputdialog.455aab44") : i18nText("app.article.video.videoinputdialog.827feba9")}
      width={720}
      mask={{ closable: false }}
      onCancel={() => onOpenChange(false)}
      destroyOnHidden
      footer={
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={() => onOpenChange(false)}>{i18nText("app.article.video.videoinputdialog.2188df59")}</Button>
          <Button type="primary" onClick={handleSubmit}>
            {value ? i18nText("app.article.video.videoinputdialog.c7a4068b") : i18nText("app.article.video.videoinputdialog.a1cdad55")}
          </Button>
        </div>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <label htmlFor="video-src" style={fieldStyle}>
          <span>{i18nText("app.article.video.videoinputdialog.2c31080c")}</span>
          <Input
            id="video-src"
            value={attributes.src}
            status={error ? 'error' : undefined}
            placeholder={i18nText("app.article.video.videoinputdialog.b98dd22a")}
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
            <span>{i18nText("app.article.video.videoinputdialog.fd717fc2")}</span>
            <Input
              id="video-title"
              value={attributes.title}
              placeholder={i18nText("app.article.video.videoinputdialog.bad440e7")}
              onChange={(event) => updateAttribute('title', event.target.value)}
            />
          </label>

          <label htmlFor="video-poster" style={fieldStyle}>
            <span>{i18nText("app.article.video.videoinputdialog.8813bf19")}</span>
            <Input
              id="video-poster"
              value={attributes.poster}
              placeholder={i18nText("app.article.video.videoinputdialog.010dd2f4")}
              onChange={(event) =>
                updateAttribute('poster', event.target.value)
              }
            />
          </label>

          <label htmlFor="video-aspect-ratio" style={fieldStyle}>
            <span>{i18nText("app.article.video.videoinputdialog.2d5ca29f")}</span>
            <Select
              id="video-aspect-ratio"
              value={attributes.aspectRatio}
              options={[
                { value: '16 / 9', label: i18nText("app.article.video.videoinputdialog.f78c4706") },
                { value: '4 / 3', label: i18nText("app.article.video.videoinputdialog.06a9c1b4") },
                { value: '1 / 1', label: i18nText("app.article.video.videoinputdialog.2892e362") },
                { value: '9 / 16', label: i18nText("app.article.video.videoinputdialog.71426b28") },
              ]}
              onChange={(nextValue) =>
                updateAttribute('aspectRatio', nextValue)
              }
            />
          </label>

          <label htmlFor="video-width-percent" style={fieldStyle}>
            <span>{i18nText("app.article.video.videoinputdialog.c5eaec7c")}</span>
            <InputNumber
              id="video-width-percent"
              aria-label={i18nText("app.article.video.videoinputdialog.c5eaec7c")}
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
                { value: 'metadata', label: i18nText("app.article.video.videoinputdialog.0e694370") },
                { value: 'auto', label: i18nText("app.article.video.videoinputdialog.8756a7f4") },
                { value: 'none', label: i18nText("app.article.video.videoinputdialog.6279631c") },
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
            {i18nText("app.article.video.videoinputdialog.86e1f5e4")}
          </Checkbox>
          <Checkbox
            checked={attributes.autoplay}
            onChange={(event) =>
              updateAttribute('autoplay', event.target.checked)
            }
          >
            {i18nText("app.article.video.videoinputdialog.6545d06e")}
          </Checkbox>
          <Checkbox
            checked={attributes.loop}
            onChange={(event) => updateAttribute('loop', event.target.checked)}
          >
            {i18nText("app.article.video.videoinputdialog.65d2e1d7")}
          </Checkbox>
          <Checkbox
            checked={attributes.muted}
            onChange={(event) => updateAttribute('muted', event.target.checked)}
          >
            {i18nText("app.article.video.videoinputdialog.50515f5a")}
          </Checkbox>
          <Checkbox
            checked={attributes.playsInline}
            onChange={(event) =>
              updateAttribute('playsInline', event.target.checked)
            }
          >
            {i18nText("app.article.video.videoinputdialog.fc1522d3")}
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
          <Typography.Text strong>{i18nText("app.article.video.videoinputdialog.964ed747")}</Typography.Text>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
              gap: 16,
            }}
          >
            <label htmlFor="video-bilibili-page" style={fieldStyle}>
              <span>{i18nText("app.article.video.videoinputdialog.7fcb924d")}</span>
              <InputNumber
                id="video-bilibili-page"
                style={{ width: '100%' }}
                min={1}
                precision={0}
                placeholder={i18nText("app.article.video.videoinputdialog.3fbee250")}
                value={attributes.bilibiliPage}
                onChange={(nextValue) =>
                  updateAttribute('bilibiliPage', nextValue)
                }
              />
            </label>

            <label htmlFor="video-bilibili-start-time" style={fieldStyle}>
              <span>{i18nText("app.article.video.videoinputdialog.be2d3ac6")}</span>
              <InputNumber
                id="video-bilibili-start-time"
                style={{ width: '100%' }}
                min={0}
                precision={0}
                suffix={i18nText("app.article.video.videoinputdialog.ca7fe8d8")}
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
              {i18nText("app.article.video.videoinputdialog.beeba062")}
            </Checkbox>
            <Checkbox
              checked={attributes.bilibiliShowPoster}
              onChange={(event) =>
                updateAttribute('bilibiliShowPoster', event.target.checked)
              }
            >
              {i18nText("app.article.video.videoinputdialog.2b0b63c4")}
            </Checkbox>
          </div>
        </div>
      </div>
    </Modal>
  );
}
