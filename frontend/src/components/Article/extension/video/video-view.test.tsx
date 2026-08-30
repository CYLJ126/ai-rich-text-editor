import {fireEvent, render, screen} from '@testing-library/react';
import type {ReactNodeViewProps} from '@tiptap/react';
import type {HTMLAttributes, ReactNode} from 'react';
import {describe, expect, it, vi} from 'vitest';
import {VideoView} from './video-view';

vi.mock('@tiptap/react', () => ({
  NodeViewWrapper: ({children, ...props}: HTMLAttributes<HTMLDivElement>) => (
    <div data-node-view-wrapper="" {...props}>
      {children as ReactNode}
    </div>
  ),
}));
vi.mock('@/components/Article/stores/editorStore', () => ({
  useEditorStore: (selector: (state: {operationMode: string}) => unknown) =>
    selector({operationMode: 'edit'}),
}));
vi.mock('@/components/Video', () => ({
  VideoPlayer: () => <div data-testid="video-player" />,
  resolveVideoSource: () => ({isValid: true}),
}));
vi.mock('./video-input-dialog', () => ({
  DEFAULT_VIDEO_ATTRIBUTES: {widthPercent: 100},
  VideoInputDialog: () => null,
}));

describe('VideoView resizing', () => {
  it('previews the resized width and commits it when pointer dragging ends', () => {
    const updateAttributes = vi.fn();
    const {container} = render(
      <VideoView
        {...({
          node: {
            attrs: {
              src: 'https://cdn.example.com/video.mp4',
              widthPercent: 80,
            },
          },
          updateAttributes,
          deleteNode: vi.fn(),
        } as unknown as ReactNodeViewProps)}
      />,
    );
    const wrapper = container.querySelector<HTMLElement>(
      '[data-node-view-wrapper]',
    );
    const handle = screen.getByRole('button', {
      name: '拖动右下角调整视频大小',
    });

    expect(
      screen.getAllByRole('button', {name: /拖动.*角调整视频大小/}),
    ).toHaveLength(4);
    expect(wrapper).not.toBeNull();
    if (!wrapper) return;

    wrapper.getBoundingClientRect = () =>
      ({width: 800}) as DOMRect;
    container.getBoundingClientRect = () =>
      ({width: 1000}) as DOMRect;
    handle.setPointerCapture = vi.fn();

    fireEvent.pointerDown(handle, {pointerId: 1, clientX: 800});
    fireEvent.pointerMove(handle, {pointerId: 1, clientX: 600});

    expect(wrapper).toHaveStyle({width: '60%'});

    fireEvent.pointerUp(handle, {pointerId: 1, clientX: 600});
    expect(updateAttributes).toHaveBeenCalledWith({widthPercent: 60});
  });
});
