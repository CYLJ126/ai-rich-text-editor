import {render, waitFor} from '@testing-library/react';
import {describe, expect, it} from 'vitest';
import {VideoPlayer} from './VideoPlayer';

describe('VideoPlayer with ReactPlayer v3', () => {
  it('applies the requested aspect ratio to the player container', () => {
    const { container } = render(
      <VideoPlayer
        src="https://vjs.zencdn.net/v/oceans.mp4"
        aspectRatio="1 / 1"
      />,
    );

    expect(container.firstElementChild).toHaveStyle({
      width: '100%',
      aspectRatio: '1 / 1',
    });
  });

  it('applies poster and title to the real native video element', async () => {
    const { container } = render(
      <VideoPlayer
        src="https://vjs.zencdn.net/v/oceans.mp4"
        poster="https://picsum.photos/800/600"
      />,
    );

    await waitFor(() => {
      const video = container.querySelector('video');
      expect(video).toHaveAttribute('poster', 'https://picsum.photos/800/600');
      expect(video).toHaveAttribute('title', '视频播放器');
    });
  });
});
