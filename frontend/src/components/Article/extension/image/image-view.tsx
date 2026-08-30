import type { ReactNodeViewProps } from '@tiptap/react';
import { NodeViewWrapper } from '@tiptap/react';
import {
  type PointerEvent as ReactPointerEvent,
  useEffect,
  useRef,
  useState,
} from 'react';
import { useEditorStore } from '@/components/Article/stores/editorStore';
import { type ImageAttributes, ImageInputDialog } from './image-input-dialog';
import { ImagePreview } from './image-preview';
import { calculateImageDimensions, type ImageResizeSide } from './image-resize';

const IMAGE_RESIZE_HANDLES = [
  {
    corner: 'top-left',
    side: 'left',
    label: '拖动左上角调整图片大小',
    className: '-top-2 -left-2 cursor-nwse-resize',
  },
  {
    corner: 'top-right',
    side: 'right',
    label: '拖动右上角调整图片大小',
    className: '-top-2 -right-2 cursor-nesw-resize',
  },
  {
    corner: 'bottom-left',
    side: 'left',
    label: '拖动左下角调整图片大小',
    className: '-bottom-2 -left-2 cursor-nesw-resize',
  },
  {
    corner: 'bottom-right',
    side: 'right',
    label: '拖动右下角调整图片大小',
    className: '-right-2 -bottom-2 cursor-nwse-resize',
  },
] as const satisfies readonly {
  corner: string;
  side: ImageResizeSide;
  label: string;
  className: string;
}[];

interface ImageDimensions {
  width: number;
  height: number;
}

interface ImageResizeState extends ImageDimensions {
  pointerId: number;
  startX: number;
  containerWidth: number;
  side: ImageResizeSide;
  nextDimensions: ImageDimensions;
}

function getPositiveDimension(value: unknown) {
  const dimension = Number(value);
  return Number.isFinite(dimension) && dimension > 0 ? dimension : undefined;
}

export function ImageView({
  editor,
  getPos,
  node,
  updateAttributes,
}: ReactNodeViewProps) {
  const operationMode = useEditorStore((state) => state.operationMode);
  const [isDialogOpen, setIsDialogOpen] = useState(false);
  const [isPreviewOpen, setIsPreviewOpen] = useState(false);
  const [previewPosition, setPreviewPosition] = useState<number>();
  const [resizingDimensions, setResizingDimensions] =
    useState<ImageDimensions>();
  const resizeStateRef = useRef<ImageResizeState | undefined>(undefined);
  const previewTimerRef = useRef<number | undefined>(undefined);
  const attributes = node.attrs as ImageAttributes;
  const width = getPositiveDimension(attributes.width);
  const height = getPositiveDimension(attributes.height);
  const displayedWidth = resizingDimensions?.width ?? width;
  const displayedHeight = resizingDimensions?.height ?? height;

  useEffect(
    () => () => {
      if (previewTimerRef.current) window.clearTimeout(previewTimerRef.current);
    },
    [],
  );

  const openPreview = () => {
    const position = getPos();
    if (typeof position !== 'number') return;
    setPreviewPosition(position);
    setIsPreviewOpen(true);
  };

  const handleImageClick = () => {
    if (previewTimerRef.current) window.clearTimeout(previewTimerRef.current);
    previewTimerRef.current = window.setTimeout(() => {
      previewTimerRef.current = undefined;
      openPreview();
    }, 220);
  };

  const handleImageDoubleClick = () => {
    if (previewTimerRef.current) window.clearTimeout(previewTimerRef.current);
    if (operationMode === 'edit') {
      setIsDialogOpen(true);
    } else {
      openPreview();
    }
  };

  const startResize = (
    event: ReactPointerEvent<HTMLButtonElement>,
    side: ImageResizeSide,
  ) => {
    event.preventDefault();
    event.stopPropagation();

    const wrapper = event.currentTarget.closest<HTMLElement>(
      '[data-node-view-wrapper]',
    );
    const image = wrapper?.querySelector<HTMLImageElement>('img');
    const imageBounds = image?.getBoundingClientRect();
    const containerWidth =
      wrapper?.parentElement?.getBoundingClientRect().width ?? 0;
    if (
      !wrapper ||
      !imageBounds ||
      imageBounds.width <= 0 ||
      imageBounds.height <= 0 ||
      containerWidth <= 0
    ) {
      return;
    }

    const dimensions = {
      width: imageBounds.width,
      height: imageBounds.height,
    };
    resizeStateRef.current = {
      ...dimensions,
      pointerId: event.pointerId,
      startX: event.clientX,
      containerWidth,
      side,
      nextDimensions: dimensions,
    };
    setResizingDimensions(dimensions);
    event.currentTarget.setPointerCapture(event.pointerId);
  };

  const resize = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const resizeState = resizeStateRef.current;
    if (!resizeState || resizeState.pointerId !== event.pointerId) return;

    const nextDimensions = calculateImageDimensions({
      startWidth: resizeState.width,
      startHeight: resizeState.height,
      containerWidth: resizeState.containerWidth,
      deltaX: event.clientX - resizeState.startX,
      side: resizeState.side,
    });
    resizeState.nextDimensions = nextDimensions;
    setResizingDimensions(nextDimensions);
  };

  const commitResize = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const resizeState = resizeStateRef.current;
    if (!resizeState || resizeState.pointerId !== event.pointerId) return;

    resizeStateRef.current = undefined;
    updateAttributes(resizeState.nextDimensions);
    setResizingDimensions(undefined);
  };

  const cancelResize = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (resizeStateRef.current?.pointerId !== event.pointerId) return;

    resizeStateRef.current = undefined;
    setResizingDimensions(undefined);
  };

  return (
    <NodeViewWrapper
      className="group relative my-3 block w-fit max-w-full not-prose"
      style={{
        width: displayedWidth === undefined ? 'fit-content' : displayedWidth,
        maxWidth: '100%',
        aspectRatio:
          displayedWidth !== undefined && displayedHeight !== undefined
            ? `${displayedWidth} / ${displayedHeight}`
            : undefined,
      }}
    >
      <img
        src={attributes.src}
        alt={attributes.alt ?? ''}
        title={attributes.title ?? undefined}
        draggable={false}
        className={`tiptap-img block max-w-full cursor-zoom-in rounded-md shadow-[4px_5px_10px_rgba(0,0,0,0.22)] ${
          resizingDimensions === undefined
            ? ''
            : 'pointer-events-none select-none'
        }`}
        style={{
          width: displayedWidth === undefined ? undefined : '100%',
          height:
            displayedWidth !== undefined && displayedHeight !== undefined
              ? '100%'
              : (displayedHeight ?? 'auto'),
        }}
        onClick={handleImageClick}
        onDoubleClick={handleImageDoubleClick}
      />

      {operationMode === 'edit' &&
        IMAGE_RESIZE_HANDLES.map((handle) => (
          <button
            key={handle.corner}
            type="button"
            aria-label={handle.label}
            title={handle.label}
            data-image-resize-handle={handle.corner}
            contentEditable={false}
            className={`absolute z-20 size-4 touch-none ${handle.className}`}
            onPointerDown={(event) => startResize(event, handle.side)}
            onPointerMove={resize}
            onPointerUp={commitResize}
            onPointerCancel={cancelResize}
          />
        ))}

      <ImageInputDialog
        value={attributes}
        isOpen={isDialogOpen}
        onOpenChange={setIsDialogOpen}
        onSubmit={(nextAttributes) => {
          updateAttributes(nextAttributes);
          setIsDialogOpen(false);
        }}
      />
      <ImagePreview
        editor={editor}
        open={isPreviewOpen}
        initialPosition={previewPosition}
        onClose={() => setIsPreviewOpen(false)}
      />
    </NodeViewWrapper>
  );
}
