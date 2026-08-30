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
      setError('请输入图片地址');
      return;
    }
    if (!isValidImageSource(src)) {
      setError('请输入有效的 HTTP 或 HTTPS 图片地址');
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
      title={value ? '修改图片' : '插入图片'}
      width={640}
      mask={{closable: false}}
      onCancel={() => onOpenChange(false)}
      destroyOnHidden
      footer={
        <div style={{display: 'flex', justifyContent: 'flex-end', gap: 8}}>
          <Button onClick={() => onOpenChange(false)}>取消</Button>
          <Button type="primary" onClick={handleSubmit}>
            {value ? '更新' : '插入'}
          </Button>
        </div>
      }
    >
      <div style={{display: 'flex', flexDirection: 'column', gap: 16}}>
        <label htmlFor="image-src" style={fieldStyle}>
          <span>图片地址</span>
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
            <span>Alt 文本</span>
            <Input
              id="image-alt"
              value={attributes.alt ?? ''}
              placeholder="图片内容说明"
              onChange={(event) => updateAttribute('alt', event.target.value)}
            />
          </label>

          <label htmlFor="image-title" style={fieldStyle}>
            <span>图片标题</span>
            <Input
              id="image-title"
              value={attributes.title ?? ''}
              placeholder="鼠标悬停时显示的标题"
              onChange={(event) => updateAttribute('title', event.target.value)}
            />
          </label>

          <label htmlFor="image-width" style={fieldStyle}>
            <span>宽度</span>
            <InputNumber
              id="image-width"
              aria-label="图片宽度"
              style={{width: '100%'}}
              min={1}
              precision={0}
              suffix="px"
              placeholder="原始宽度"
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
            <span>高度</span>
            <InputNumber
              id="image-height"
              aria-label="图片高度"
              style={{width: '100%'}}
              min={1}
              precision={0}
              suffix="px"
              placeholder="原始高度"
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
          尺寸留空时使用图片原始大小；在编辑器中拖动图片四角会保持宽高比。
        </Typography.Text>
      </div>
    </Modal>
  );
}
