import {i18nText} from '@/utils/i18n';
import {Button, Input, InputNumber, Modal, Typography} from 'antd';
import {useEffect, useState} from 'react';

export interface ImageAttributes {
  src: string;
  alt: string | null;
  title: string | null;
  width: number | null;
  height: number | null;
}

export const DEFAULT_IMAGE_ATTRIBUTES: ImageAttributes = {
  src: '',
  alt: null,
  title: null,
  width: null,
  height: null,
};

function isValidImageSource(src: string) {
  try {
    const baseUrl =
      typeof window === 'undefined' ? 'http://localhost' : window.location.href;
    const url = new URL(src, baseUrl);
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
}

export function ImageInputDialog({
  value,
  isOpen,
  onOpenChange,
  onSubmit,
}: {
  value?: Partial<ImageAttributes>;
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (attributes: ImageAttributes) => void;
}) {
  const [attributes, setAttributes] = useState<ImageAttributes>({
    ...DEFAULT_IMAGE_ATTRIBUTES,
    ...value,
  });
  const [error, setError] = useState<string>();

  useEffect(() => {
    if (!isOpen) return;

    setAttributes({...DEFAULT_IMAGE_ATTRIBUTES, ...value});
    setError(undefined);
  }, [isOpen, value]);

  const updateAttribute = <Key extends keyof ImageAttributes>(
    key: Key,
    nextValue: ImageAttributes[Key],
  ) => {
    setAttributes((current) => ({...current, [key]: nextValue}));
    if (key === 'src') setError(undefined);
  };

  const handleSubmit = () => {
    const src = attributes.src.trim();
    if (!src) {
      setError(i18nText("app.article.image.imageinputdialog.498e200a"));
      return;
    }
    if (!isValidImageSource(src)) {
      setError(i18nText("app.article.image.imageinputdialog.c7da297b"));
      return;
    }

    onSubmit({
      ...attributes,
      src,
      alt: attributes.alt?.trim() || null,
      title: attributes.title?.trim() || null,
      width: attributes.width && attributes.width > 0 ? attributes.width : null,
      height:
        attributes.height && attributes.height > 0 ? attributes.height : null,
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
      title={value ? i18nText("app.article.image.imageinputdialog.aba31061") : i18nText("app.article.image.imageinputdialog.13ae2a60")}
      width={640}
      mask={{closable: false}}
      onCancel={() => onOpenChange(false)}
      destroyOnHidden
      footer={
        <div style={{display: 'flex', justifyContent: 'flex-end', gap: 8}}>
          <Button onClick={() => onOpenChange(false)}>{i18nText("app.article.image.imageinputdialog.e1ce6692")}</Button>
          <Button type="primary" onClick={handleSubmit}>
            {value ? i18nText("app.article.image.imageinputdialog.7cf8b53a") : i18nText("app.article.image.imageinputdialog.7c01f1e5")}
          </Button>
        </div>
      }
    >
      <div style={{display: 'flex', flexDirection: 'column', gap: 16}}>
        <label htmlFor="image-src" style={fieldStyle}>
          <span>{i18nText("app.article.image.imageinputdialog.db65d8e7")}</span>
          <Input
            id="image-src"
            value={attributes.src}
            status={error ? 'error' : undefined}
            placeholder="https://.../image.jpg"
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
          <label htmlFor="image-alt" style={fieldStyle}>
            <span>{i18nText("app.article.image.imageinputdialog.412a3297")}</span>
            <Input
              id="image-alt"
              value={attributes.alt ?? ''}
              placeholder={i18nText("app.article.image.imageinputdialog.0dab141a")}
              onChange={(event) => updateAttribute('alt', event.target.value)}
            />
          </label>

          <label htmlFor="image-title" style={fieldStyle}>
            <span>{i18nText("app.article.image.imageinputdialog.9439d1cb")}</span>
            <Input
              id="image-title"
              value={attributes.title ?? ''}
              placeholder={i18nText("app.article.image.imageinputdialog.7a8a1f88")}
              onChange={(event) => updateAttribute('title', event.target.value)}
            />
          </label>

          <label htmlFor="image-width" style={fieldStyle}>
            <span>{i18nText("app.article.image.imageinputdialog.bee2c25e")}</span>
            <InputNumber
              id="image-width"
              aria-label={i18nText("app.article.image.imageinputdialog.dc49744e")}
              style={{width: '100%'}}
              min={1}
              precision={0}
              suffix="px"
              placeholder={i18nText("app.article.image.imageinputdialog.8674be98")}
              value={attributes.width}
              onChange={(nextValue) =>
                updateAttribute(
                  'width',
                  typeof nextValue === 'number' ? nextValue : null,
                )
              }
            />
          </label>

          <label htmlFor="image-height" style={fieldStyle}>
            <span>{i18nText("app.article.image.imageinputdialog.feab32c6")}</span>
            <InputNumber
              id="image-height"
              aria-label={i18nText("app.article.image.imageinputdialog.087fcf11")}
              style={{width: '100%'}}
              min={1}
              precision={0}
              suffix="px"
              placeholder={i18nText("app.article.image.imageinputdialog.e355fd16")}
              value={attributes.height}
              onChange={(nextValue) =>
                updateAttribute(
                  'height',
                  typeof nextValue === 'number' ? nextValue : null,
                )
              }
            />
          </label>
        </div>

        <Typography.Text type="secondary">
          {i18nText("app.article.image.imageinputdialog.7b6bd501")}
        </Typography.Text>
      </div>
    </Modal>
  );
}
