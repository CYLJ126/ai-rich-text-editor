import {i18nText} from '@/utils/i18n';
import {
  AimOutlined,
  CopyOutlined,
  LeftOutlined,
  RightOutlined,
  ZoomInOutlined,
  ZoomOutOutlined,
} from '@ant-design/icons';
import type { Editor } from '@tiptap/core';
import { App, Button, Modal, Tooltip } from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import styles from './image-preview.less';

interface PreviewImage {
  src: string;
  alt: string;
  position: number;
}

interface ImagePreviewProps {
  editor: Editor;
  open: boolean;
  initialPosition?: number;
  onClose: () => void;
}

const MIN_SCALE = 0.1;
const MAX_SCALE = 5;
const SCALE_STEP = 0.1;

function collectImages(editor: Editor): PreviewImage[] {
  const images: PreviewImage[] = [];
  editor.state.doc.descendants((node, position) => {
    if (node.type.name !== 'image' || !node.attrs.src) return;
    images.push({
      src: node.attrs.src,
      alt: node.attrs.alt ?? '',
      position,
    });
  });
  return images;
}

function normalizeScale(value: number) {
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, value));
}

async function getClipboardPng(src: string) {
  const response = await fetch(src);
  const sourceBlob = await response.blob();
  const bitmap = await createImageBitmap(sourceBlob);
  const canvas = document.createElement('canvas');
  canvas.width = bitmap.width;
  canvas.height = bitmap.height;
  canvas.getContext('2d')?.drawImage(bitmap, 0, 0);
  bitmap.close();

  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (blob) resolve(blob);
      else reject(new Error(i18nText("app.article.image.imagepreview.e068a8ee")));
    }, 'image/png');
  });
}

export function ImagePreview({
  editor,
  open,
  initialPosition,
  onClose,
}: ImagePreviewProps) {
  const { message } = App.useApp();
  const [images, setImages] = useState<PreviewImage[]>([]);
  const [activeIndex, setActiveIndex] = useState(0);
  const [scale, setScale] = useState(1);
  const [fitScale, setFitScale] = useState(1);
  const [naturalSize, setNaturalSize] = useState({ width: 0, height: 0 });
  const [copying, setCopying] = useState(false);
  const [dragging, setDragging] = useState(false);
  const viewportRef = useRef<HTMLDivElement>(null);
  const imageRef = useRef<HTMLImageElement>(null);
  const panRef = useRef<
    | {
        pointerId: number;
        x: number;
        y: number;
        left: number;
        top: number;
      }
    | undefined
  >(undefined);
  const activeImage = images[activeIndex];

  useEffect(() => {
    if (!open) return;
    const nextImages = collectImages(editor);
    const nextIndex = Math.max(
      0,
      nextImages.findIndex((image) => image.position === initialPosition),
    );
    setImages(nextImages);
    setActiveIndex(nextIndex);
    setScale(1);
  }, [editor, initialPosition, open]);

  useEffect(() => {
    setScale(1);
    setFitScale(1);
    setNaturalSize({ width: 0, height: 0 });
    viewportRef.current?.scrollTo(0, 0);
  }, [activeIndex]);

  const changeScale = (delta: number) => {
    setScale((current) => normalizeScale(current + delta));
  };

  const turnPage = (step: number) => {
    setActiveIndex(
      (current) => (current + step + images.length) % images.length,
    );
  };

  useEffect(() => {
    if (!open || images.length <= 1) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      event.preventDefault();
      turnPage(event.key === 'ArrowLeft' ? -1 : 1);
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [images.length, open]);

  const fitImage = (image: HTMLImageElement) => {
    requestAnimationFrame(() => {
      const viewport = viewportRef.current;
      if (!viewport || image !== imageRef.current) return;
      const nextScale = Math.max(
        0.01,
        Math.min(
          1,
          (viewport.clientWidth - 40) / image.naturalWidth,
          (viewport.clientHeight - 40) / image.naturalHeight,
        ),
      );
      setNaturalSize({
        width: image.naturalWidth,
        height: image.naturalHeight,
      });
      setFitScale(nextScale);
      setScale(nextScale);
    });
  };

  useEffect(() => {
    if (open && imageRef.current?.complete) fitImage(imageRef.current);
  }, [activeImage?.src, open]);

  const startPan = (event: React.PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0 || !viewportRef.current) return;
    event.preventDefault();
    panRef.current = {
      pointerId: event.pointerId,
      x: event.clientX,
      y: event.clientY,
      left: viewportRef.current.scrollLeft,
      top: viewportRef.current.scrollTop,
    };
    event.currentTarget.setPointerCapture(event.pointerId);
    setDragging(true);
  };

  const movePan = (event: React.PointerEvent<HTMLDivElement>) => {
    const pan = panRef.current;
    if (!pan || pan.pointerId !== event.pointerId || !viewportRef.current)
      return;
    viewportRef.current.scrollLeft = pan.left - (event.clientX - pan.x);
    viewportRef.current.scrollTop = pan.top - (event.clientY - pan.y);
  };

  const stopPan = (event: React.PointerEvent<HTMLDivElement>) => {
    if (panRef.current?.pointerId !== event.pointerId) return;
    panRef.current = undefined;
    setDragging(false);
  };

  const copyImage = async () => {
    if (!activeImage) return;
    setCopying(true);
    try {
      const blob = await getClipboardPng(activeImage.src);
      await navigator.clipboard.write([
        new ClipboardItem({ 'image/png': blob }),
      ]);
      message.success(i18nText("app.article.image.imagepreview.56c04ac0"));
    } catch {
      message.error(i18nText("app.article.image.imagepreview.3b348d0f"));
    } finally {
      setCopying(false);
    }
  };

  const locateImage = () => {
    if (!activeImage) return;
    const nodeDom = editor.view.nodeDOM(activeImage.position);
    const element =
      nodeDom instanceof HTMLElement ? nodeDom : nodeDom?.parentElement;
    onClose();
    window.setTimeout(() => {
      element?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      element?.animate(
        [
          { boxShadow: '0 0 0 3px rgba(22, 119, 255, 0.9)' },
          { boxShadow: '0 0 0 3px rgba(22, 119, 255, 0)' },
        ],
        { duration: 1200 },
      );
    }, 150);
  };

  return (
    <Modal
      open={open}
      onCancel={onClose}
      footer={null}
      width="100vw"
      centered
      style={{ maxWidth: '100vw' }}
      afterOpenChange={(visible) => {
        if (visible && imageRef.current) fitImage(imageRef.current);
      }}
      styles={{
        container: {
          height: '100vh',
          padding: '14px 16px 10px',
          boxSizing: 'border-box',
          background: 'rgba(18, 18, 18, 0.9)',
          backdropFilter: 'blur(12px)',
          borderRadius: 0,
          boxShadow: 'none',
        },
        body: {
          display: 'flex',
          height: '100%',
          flexDirection: 'column',
          overflow: 'hidden',
          background: 'transparent',
        },
        mask: { background: 'rgba(0, 0, 0, 0.38)' },
      }}
      classNames={{ close: styles.closeButton }}
    >
      <div
        ref={viewportRef}
        className={`${styles.viewport} ${dragging ? styles.dragging : ''}`}
        onPointerDown={startPan}
        onPointerMove={movePan}
        onPointerUp={stopPan}
        onPointerCancel={stopPan}
        onWheel={(event) => {
          event.preventDefault();
          changeScale(event.deltaY < 0 ? SCALE_STEP : -SCALE_STEP);
        }}
      >
        <div className={styles.canvas}>
          {activeImage && (
            <img
              ref={imageRef}
              key={activeImage.position}
              src={activeImage.src}
              alt={activeImage.alt}
              draggable={false}
              className={styles.previewImage}
              style={{
                width: naturalSize.width * scale || undefined,
                height: naturalSize.height * scale || undefined,
              }}
              onLoad={(event) => fitImage(event.currentTarget)}
            />
          )}
        </div>
      </div>

      <div className={styles.toolbar}>
        <Tooltip title={i18nText("app.article.image.imagepreview.fd0a862b")}>
          <Button
            type="text"
            icon={<LeftOutlined />}
            disabled={images.length <= 1}
            onClick={() => turnPage(-1)}
          />
        </Tooltip>
        <span className={styles.counter}>
          {images.length ? `${activeIndex + 1} / ${images.length}` : '0 / 0'}
        </span>
        <Tooltip title={i18nText("app.article.image.imagepreview.a4eef18f")}>
          <Button
            type="text"
            icon={<RightOutlined />}
            disabled={images.length <= 1}
            onClick={() => turnPage(1)}
          />
        </Tooltip>
        <span className={styles.divider} />
        <Tooltip title={i18nText("app.article.image.imagepreview.21216d85")}>
          <Button
            type="text"
            icon={<ZoomOutOutlined />}
            disabled={scale <= MIN_SCALE}
            onClick={() => changeScale(-SCALE_STEP)}
          />
        </Tooltip>
        <Tooltip title={i18nText("app.article.image.imagepreview.0ac68cec")}>
          <Button type="text" onClick={() => setScale(fitScale)}>
            {Math.round(scale * 100)}%
          </Button>
        </Tooltip>
        <Tooltip title={i18nText("app.article.image.imagepreview.3f7c93c4")}>
          <Button
            type="text"
            icon={<ZoomInOutlined />}
            disabled={scale >= MAX_SCALE}
            onClick={() => changeScale(SCALE_STEP)}
          />
        </Tooltip>
        <span className={styles.divider} />
        <Tooltip title={i18nText("app.article.image.imagepreview.539ae68a")}>
          <Button
            type="text"
            icon={<CopyOutlined />}
            loading={copying}
            onClick={copyImage}
          />
        </Tooltip>
        <Tooltip title={i18nText("app.article.image.imagepreview.2bcaf666")}>
          <Button type="text" icon={<AimOutlined />} onClick={locateImage}>
            {i18nText("app.article.image.imagepreview.6825f70e")}
          </Button>
        </Tooltip>
      </div>
    </Modal>
  );
}
