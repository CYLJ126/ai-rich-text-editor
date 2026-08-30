import type {ReactNodeViewProps} from '@tiptap/react';
import {NodeViewWrapper} from '@tiptap/react';
import {EditIcon, ExternalLinkIcon, TrashIcon} from 'lucide-react';
import {type PointerEvent as ReactPointerEvent, useRef, useState} from 'react';
import {useEditorStore} from '@/components/Article/stores/editorStore';
import {Button} from '@/components/ui/button';
import {resolveVideoSource, VideoPlayer} from '@/components/Video';
import {DEFAULT_VIDEO_ATTRIBUTES, type VideoAttributes, VideoInputDialog,} from './video-input-dialog';
import {calculateVideoWidthPercent, type VideoResizeSide,} from './video-resize';

const VIDEO_RESIZE_HANDLES = [
  {
    corner: 'top-left',
    side: 'left',
    label: '拖动左上角调整视频大小',
    className: '-top-2 -left-2 cursor-nwse-resize',
  },
  {
    corner: 'top-right',
    side: 'right',
    label: '拖动右上角调整视频大小',
    className: '-top-2 -right-2 cursor-nesw-resize',
  },
  {
    corner: 'bottom-left',
    side: 'left',
    label: '拖动左下角调整视频大小',
    className: '-bottom-2 -left-2 cursor-nesw-resize',
  },
  {
    corner: 'bottom-right',
    side: 'right',
    label: '拖动右下角调整视频大小',
    className: '-right-2 -bottom-2 cursor-nwse-resize',
  },
] as const satisfies readonly {
  corner: string;
  side: VideoResizeSide;
  label: string;
  className: string;
}[];

interface VideoResizeState {
  pointerId: number;
  startX: number;
  startWidth: number;
  containerWidth: number;
  side: VideoResizeSide;
  widthPercent: number;
}

export function VideoView({
  node,
  updateAttributes,
  deleteNode,
}: ReactNodeViewProps) {
  const operationMode = useEditorStore((state) => state.operationMode);
  const [isDialogOpen, setIsDialogOpen] = useState(false);
  const [resizingWidthPercent, setResizingWidthPercent] = useState<number>();
  const resizeStateRef = useRef<VideoResizeState | undefined>(undefined);
  const attributes = node.attrs as VideoAttributes;
  const parsedWidthPercent = Number(attributes.widthPercent);
  const widthPercent = Number.isFinite(parsedWidthPercent)
    ? Math.min(100, Math.max(1, parsedWidthPercent))
    : DEFAULT_VIDEO_ATTRIBUTES.widthPercent;
  const displayedWidthPercent = resizingWidthPercent ?? widthPercent;

  const startResize = (
    event: ReactPointerEvent<HTMLButtonElement>,
    side: VideoResizeSide,
  ) => {
    event.preventDefault();
    event.stopPropagation();

    const wrapper = event.currentTarget.closest<HTMLElement>(
      '[data-node-view-wrapper]',
    );
    const containerWidth =
      wrapper?.parentElement?.getBoundingClientRect().width ?? 0;
    const startWidth = wrapper?.getBoundingClientRect().width ?? 0;
    if (!wrapper || containerWidth <= 0 || startWidth <= 0) return;

    resizeStateRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startWidth,
      containerWidth,
      side,
      widthPercent,
    };
    setResizingWidthPercent(widthPercent);
    event.currentTarget.setPointerCapture(event.pointerId);
  };

  const resize = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const resizeState = resizeStateRef.current;
    if (!resizeState || resizeState.pointerId !== event.pointerId) return;

    const nextWidthPercent = calculateVideoWidthPercent({
      startWidth: resizeState.startWidth,
      containerWidth: resizeState.containerWidth,
      deltaX: event.clientX - resizeState.startX,
      side: resizeState.side,
    });
    resizeState.widthPercent = nextWidthPercent;
    setResizingWidthPercent(nextWidthPercent);
  };

  const commitResize = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const resizeState = resizeStateRef.current;
    if (!resizeState || resizeState.pointerId !== event.pointerId) return;

    resizeStateRef.current = undefined;
    updateAttributes({widthPercent: resizeState.widthPercent});
    setResizingWidthPercent(undefined);
  };

  const cancelResize = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (resizeStateRef.current?.pointerId !== event.pointerId) return;

    resizeStateRef.current = undefined;
    setResizingWidthPercent(undefined);
  };

  const openOriginalVideo = () => {
    const src = attributes.src.trim();
    if (!resolveVideoSource(src).isValid) return;

    window.open(src, '_blank', 'noopener,noreferrer');
  };

  return (
    <NodeViewWrapper
      className="group relative my-3 not-prose"
      style={{width: `${displayedWidthPercent}%`}}
      onDoubleClick={() => {
        if (operationMode === 'edit') setIsDialogOpen(true);
      }}
    >
      <VideoPlayer
        src={attributes.src}
        className={
          resizingWidthPercent === undefined
            ? undefined
            : 'pointer-events-none select-none'
        }
        title={attributes.title}
        poster={attributes.poster || undefined}
        controls={attributes.controls}
        autoPlay={attributes.autoplay}
        muted={attributes.muted}
        loop={attributes.loop}
        playsInline={attributes.playsInline}
        preload={attributes.preload}
        aspectRatio={attributes.aspectRatio}
        bilibiliOptions={{
          danmaku: attributes.bilibiliDanmaku,
          page: attributes.bilibiliPage ?? undefined,
          startTime: attributes.bilibiliStartTime ?? undefined,
          showPoster: attributes.bilibiliShowPoster,
        }}
      />

      {operationMode === 'edit' &&
        VIDEO_RESIZE_HANDLES.map((handle) => (
          <button
            key={handle.corner}
            type="button"
            aria-label={handle.label}
            title={handle.label}
            data-video-resize-handle={handle.corner}
            contentEditable={false}
            className={`absolute z-20 size-4 touch-none ${handle.className}`}
            onPointerDown={(event) => startResize(event, handle.side)}
            onPointerMove={resize}
            onPointerUp={commitResize}
            onPointerCancel={cancelResize}
          />
        ))}

      {operationMode === 'edit' && (
        <div
          className="pointer-events-auto absolute top-1 right-5 z-10 flex gap-1"
          contentEditable={false}
          onMouseDown={(event) => event.stopPropagation()}
        >
          <Button
            type="button"
            variant="secondary"
            size="icon"
            className="size-7 cursor-pointer opacity-40 hover:opacity-100"
            title="修改视频"
            onClick={() => setIsDialogOpen(true)}
          >
            <EditIcon className="size-4" strokeWidth={3} />
          </Button>
          <Button
            type="button"
            variant="secondary"
            size="icon"
            className="size-7 cursor-pointer opacity-40 hover:opacity-100"
            title="删除视频"
            onClick={deleteNode}
          >
            <TrashIcon className="size-4" strokeWidth={3} />
          </Button>
          <Button
            type="button"
            variant="secondary"
            size="icon"
            className="size-7 cursor-pointer opacity-40 hover:opacity-100"
            title="在新标签页打开原视频"
            onClick={(event) => {
              event.stopPropagation();
              openOriginalVideo();
            }}
          >
            <ExternalLinkIcon className="size-4" strokeWidth={3} />
          </Button>
        </div>
      )}

      <VideoInputDialog
        value={attributes}
        isOpen={isDialogOpen}
        onOpenChange={setIsDialogOpen}
        onSubmit={(nextAttributes) => {
          updateAttributes(nextAttributes);
          setIsDialogOpen(false);
        }}
      />
    </NodeViewWrapper>
  );
}
