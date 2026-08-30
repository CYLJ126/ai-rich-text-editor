import type {ReactNodeViewProps} from '@tiptap/react';
import {NodeViewWrapper} from '@tiptap/react';
import {DownloadIcon, EditIcon, TrashIcon} from 'lucide-react';
import {type AudioHTMLAttributes, useState} from 'react';
import {useEditorStore} from '@/components/Article/stores/editorStore';
import {Button} from '@/components/ui/button';
import {type AudioAttributes, AudioInputDialog} from './audio-input-dialog';

const getAudioFileName = (src: string) => {
  try {
    const fileName = decodeURIComponent(
      new URL(src, window.location.href).pathname.split('/').pop() || '',
    );
    return fileName || 'audio.mp3';
  } catch {
    return 'audio.mp3';
  }
};

export function AudioView({
  node,
  updateAttributes,
  deleteNode,
}: ReactNodeViewProps) {
  const operationMode = useEditorStore((state) => state.operationMode);
  const [isDialogOpen, setIsDialogOpen] = useState(false);
  const attributes = node.attrs as AudioAttributes;
  const remotePlaybackAttributes: AudioHTMLAttributes<HTMLAudioElement> & {
    disableRemotePlayback: boolean;
  } = {
    disableRemotePlayback: attributes.disableremoteplayback,
  };

  const downloadAudio = async () => {
    const fileName = getAudioFileName(attributes.src);

    try {
      const response = await fetch(attributes.src, {
        credentials:
          attributes.crossorigin === 'use-credentials'
            ? 'include'
            : 'same-origin',
      });
      if (!response.ok) throw new Error('音频下载失败');

      const objectUrl = URL.createObjectURL(await response.blob());
      const link = document.createElement('a');
      link.href = objectUrl;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(objectUrl), 100);
    } catch {
      // 跨域服务器未开放 CORS 时，回退为浏览器直接下载或打开音频地址。
      const link = document.createElement('a');
      link.href = attributes.src;
      link.download = fileName;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      document.body.appendChild(link);
      link.click();
      link.remove();
    }
  };

  return (
    <NodeViewWrapper className="group relative my-3 w-full">
      <audio
        {...remotePlaybackAttributes}
        className="block w-full max-w-4xl"
        src={attributes.src}
        controls
        autoPlay={attributes.autoplay}
        loop={attributes.loop}
        muted={attributes.muted}
        preload={attributes.preload ?? undefined}
        controlsList={attributes.controlslist}
        crossOrigin={attributes.crossorigin || undefined}
        onDoubleClick={() => {
          if (operationMode === 'edit') setIsDialogOpen(true);
        }}
      />

      {operationMode === 'edit' && (
        <div
          className="pointer-events-auto absolute top-1 right-1 z-10 flex gap-1"
          contentEditable={false}
          onMouseDown={(event) => event.stopPropagation()}
        >
          <Button
            type="button"
            variant="secondary"
            size="icon"
            className="size-7 cursor-pointer opacity-40 hover:opacity-100"
            title="修改音频"
            onClick={() => setIsDialogOpen(true)}
          >
            <EditIcon className="size-4" strokeWidth={3} />
          </Button>
          <Button
            type="button"
            variant="secondary"
            size="icon"
            className="size-7 cursor-pointer opacity-40 hover:opacity-100"
            title="下载音频"
            onClick={downloadAudio}
          >
            <DownloadIcon className="size-4" strokeWidth={3} />
          </Button>
          <Button
            type="button"
            variant="secondary"
            size="icon"
            className="size-7 cursor-pointer opacity-40 hover:opacity-100"
            title="删除音频"
            onClick={deleteNode}
          >
            <TrashIcon className="size-4" strokeWidth={3} />
          </Button>
        </div>
      )}

      <AudioInputDialog
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
