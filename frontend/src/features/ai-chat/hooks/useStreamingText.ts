import {useCallback, useEffect, useLayoutEffect, useRef, useState} from 'react';

const segmenter = new Intl.Segmenter(undefined, {granularity: 'grapheme'});

/** Display live answers in small steps without changing the authoritative SSE text/status. */
export function useStreamingText(text: string, streaming: boolean, succeeded: boolean) {
  const [displayed, setDisplayed] = useState(text);
  const [motion] = useState(() => window.matchMedia('(prefers-reduced-motion: reduce)'));
  const target = useRef(text);
  const visible = useRef(text);
  const wasStreaming = useRef(streaming);
  const immediate = useRef(motion.matches || document.hidden);
  const frame = useRef<number | null>(null);
  const lastFrame = useRef(0);

  const finish = useCallback(() => {
    if (frame.current !== null) cancelAnimationFrame(frame.current);
    frame.current = null;
    visible.current = target.current;
    setDisplayed(target.current);
  }, []);

  const advance = useCallback(function advance(now: number) {
    frame.current = null;
    if (now - lastFrame.current >= 16) {
      const remaining = target.current.slice(visible.current.length);
      const step = remaining.length > 80 ? 3 : remaining.length > 24 ? 2 : 1;
      let added = '';
      let count = 0;
      for (const {segment} of segmenter.segment(remaining)) {
        added += segment;
        if (++count === step) break;
      }
      visible.current += added;
      setDisplayed(visible.current);
      lastFrame.current = now;
    }
    if (visible.current !== target.current) frame.current = requestAnimationFrame(advance);
  }, []);

  useLayoutEffect(() => {
    target.current = text;
    if (streaming) wasStreaming.current = true;
    if (!streaming && !succeeded) wasStreaming.current = false;
    // Existing history renders immediately; failure/stop and corrected results
    // flush the queue. Success drains any remaining live text without a jump.
    if (!wasStreaming.current || immediate.current || !text.startsWith(visible.current)) {
      finish();
    } else if (visible.current !== text && frame.current === null) {
      lastFrame.current = 0;
      frame.current = requestAnimationFrame(advance);
    }
  }, [text, streaming, succeeded, advance, finish]);

  useEffect(() => {
    const update = () => {
      immediate.current = motion.matches || document.hidden;
      if (immediate.current) finish();
    };
    motion.addEventListener('change', update);
    document.addEventListener('visibilitychange', update);
    return () => {
      motion.removeEventListener('change', update);
      document.removeEventListener('visibilitychange', update);
      if (frame.current !== null) cancelAnimationFrame(frame.current);
      frame.current = null;
    };
  }, [motion, finish]);

  return {text: displayed, isTyping: displayed !== text, finish};
}
