import {cleanup, render, screen} from '@testing-library/react';
import React from 'react';
import {afterEach, expect, it, vi} from 'vitest';
import MarkdownText from './MarkdownText';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it('流式首段直接进入 DOM，Markdown 不再等待或重排绘制帧', () => {
  const frame = vi.spyOn(window, 'requestAnimationFrame');
  const view = render(<MarkdownText text="首段文字" streaming/>);
  expect(screen.getByText('首段文字')).toBeTruthy();
  view.rerender(<MarkdownText text="首段文字继续追加" streaming/>);
  expect(screen.getByText('首段文字继续追加')).toBeTruthy();
  expect(frame).not.toHaveBeenCalled();
});
